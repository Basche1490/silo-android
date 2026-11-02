package org.siloserver.silo.common.player.external

import android.util.Log
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.Request as UpstreamRequest

/**
 * Serves one Silo stream to the Zidoo player on this box, at
 * `http://127.0.0.1:<port>/<secret>/<fileName>`.
 *
 * Silo's own player signs every media request with the account's current
 * access token in a header. The Zidoo player can only be given an address, so
 * the relay stands in for it: each request it receives is forwarded to
 * [upstreamUrl] with `Authorization: Bearer <token>`, using whatever token
 * [accessToken] returns at that moment. That gives the Zidoo player:
 *
 * - a sign-in that never runs out mid-film. Access tokens last hours, but the
 *   app renews its token while playback progress is reported, and a refused
 *   request is retried once with the renewed token;
 * - an address with the file's own extension, as Plex's links have;
 * - no credential in the address, so none ends up in PlexToZidoo's debug
 *   screen or the Zidoo player's playback history.
 *
 * Only GET and HEAD on the exact path are served, `Range` and `If-Range` are
 * passed through, and every response to the player closes its connection. The
 * secret path keeps other apps on the box from using the relay.
 *
 * Requests to the server go through [client], Silo's own video player's
 * connection pool in the app, so they connect as that player does: kept-alive
 * connections reused across requests (each would otherwise pay a new TLS
 * handshake to a server across the internet), a failed address tried last,
 * and the server's local address while the box is at home (with Silo's
 * local-route marker in [upstreamHeaders]). IPv4 addresses are tried before
 * IPv6 ones; see [Ipv4FirstDns].
 *
 * The relay notes what it sees, so a hand-off that fails can say where:
 * [report].
 *
 * Blocking I/O on its own threads: one accepts, one per connection.
 */
class ZidooStreamRelay(
    private val upstreamUrl: String,
    fileName: String,
    /** The access token to sign the next request with; called on a relay thread. */
    private val accessToken: () -> String?,
    /** Sends the requests to the server; Silo passes its video player's pooled client. */
    client: OkHttpClient = OkHttpClient(),
    /** Headers added to every request to the server, such as Silo's local-route marker. */
    private val upstreamHeaders: Map<String, String> = emptyMap(),
    /** Where the relay notes connections, requests and answers; never with the token or secret path. */
    private val log: (String) -> Unit = {},
    /** Upper bound for waiting on a renewed token after the server refused one. */
    private val tokenRenewalWaitMs: Long = 15_000L,
) : Closeable {

    private val client = client.newBuilder()
        // HTTP/1.1, as before, so the server's headers (Content-Length above
        // all, which tells the player the file's size) arrive as they always have.
        .protocols(listOf(Protocol.HTTP_1_1))
        .dns(Ipv4FirstDns)
        .connectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(UPSTREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val path = "/" + randomSecret() + "/" + safeFileName(fileName)
    private val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
    private val active = AtomicInteger(0)

    @Volatile
    private var closed = false

    @Volatile
    private var firstServedAtMs = 0L

    @Volatile
    private var lastServedAtMs = 0L

    /** Requests for the video the player has made. */
    private val requestCount = AtomicInteger(0)

    /** Connections the player has opened, counted for [log]. */
    private val connectionCount = AtomicInteger(0)

    /** The longest the server took to answer a request, connecting included. */
    @Volatile
    private var slowestAnswerMs = 0L

    /** The last thing that went wrong between the relay and the server, in words. */
    @Volatile
    private var lastProblem: String? = null

    /** True when the player received media within the last [windowMs]: it is playing, or buffering to play. */
    fun servedWithin(windowMs: Long): Boolean =
        lastServedAtMs != 0L && System.currentTimeMillis() - lastServedAtMs <= windowMs

    /**
     * True when the player kept receiving media over at least [spanMs]. Opening
     * a file the player then refuses takes seconds; this means it played.
     */
    fun servedForAtLeast(spanMs: Long): Boolean =
        firstServedAtMs != 0L && lastServedAtMs - firstServedAtMs >= spanMs

    /**
     * What the relay saw, for a hand-off the Zidoo player didn't play: whether
     * the player asked for the video at all, how long the server took to
     * answer, and the last thing that went wrong between Silo and the server.
     */
    fun report(): String {
        val asked = requestCount.get()
        if (asked == 0) return "The Zidoo player never asked Silo for the video."
        val slowest = String.format(Locale.ROOT, "%.1f", slowestAnswerMs / 1000.0)
        return buildString {
            append("The Zidoo player asked Silo for the video ").append(asked).append(if (asked == 1) " time" else " times")
            append(", and your server took up to ").append(slowest).append(" seconds to answer.")
            lastProblem?.let { append(' ').append(it) }
        }
    }

    /** The address to give the Zidoo player. */
    val url: String = "http://$LOOPBACK:${server.localPort}$path"

    init {
        log("listening on 127.0.0.1:${server.localPort}")
        Thread({ acceptLoop() }, "ZidooStreamRelay-accept").apply { isDaemon = true }.start()
    }

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) log("stopped accepting connections (${describe(e)})")
                break
            }
            val connection = connectionCount.incrementAndGet()
            if (active.incrementAndGet() > MAX_CONNECTIONS) {
                active.decrementAndGet()
                log("connection $connection refused: $MAX_CONNECTIONS already open")
                socket.closeQuietly()
                continue
            }
            Thread({
                // Nothing may escape a relay thread: an uncaught exception
                // would end Silo's process, and the film with it.
                try {
                    serve(socket, connection)
                } catch (e: Exception) {
                    Log.w(TAG, "Relay request failed", e)
                } finally {
                    active.decrementAndGet()
                    socket.closeQuietly()
                }
            }, "ZidooStreamRelay-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket, connection: Int) {
        socket.soTimeout = REQUEST_READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val request = readRequest(input)
        if (request == null) {
            log("connection $connection closed without a request")
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            log("connection $connection: ${request.method.take(12)} refused (405)")
            return writeSimple(output, 405, "Method Not Allowed")
        }
        if (request.target.substringBefore('?') != path) {
            log("connection $connection: request for another address refused (404)")
            return writeSimple(output, 404, "Not Found")
        }
        val number = requestCount.incrementAndGet()
        val logged = number <= MAX_LOGGED_REQUESTS
        if (logged) {
            log("request $number: ${request.method}" + (request.headers["range"]?.let { " $it" } ?: ""))
        } else if (number == MAX_LOGGED_REQUESTS + 1) {
            log("further requests are not logged")
        }
        val sent = longArrayOf(0L)
        val askedAt = System.nanoTime()
        var response: Response? = null
        var responseStarted = false
        try {
            var token = accessToken()
            response = fetch(request, token)
            if (response.code == 401) {
                // The token ran out. The app renews it within one progress
                // report, so wait for a different one and try once more.
                response.close()
                token = awaitRenewedToken(token)
                response = fetch(request, token)
            }
            val code = response.code
            if (code >= 400) lastProblem = "Your server refused the video (HTTP $code)."
            if (logged) log("request $number: server answered $code in ${(System.nanoTime() - askedAt) / 1_000_000} ms")
            val header = StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ')
                .append(response.message.takeIf { it.isNotBlank() && '\n' !in it && '\r' !in it } ?: "Status")
                .append("\r\n")
            for (name in RELAYED_RESPONSE_HEADERS) {
                response.header(name)?.let { header.append(name).append(": ").append(it).append("\r\n") }
            }
            header.append("Connection: close\r\n\r\n")
            responseStarted = true
            output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method == "GET") {
                response.body?.byteStream()?.use { copyCounting(it, output, media = code < 300, sent) }
            }
            output.flush()
            if (logged) log("request $number: sent ${megabytes(sent[0])} MB, complete")
        } catch (e: IOException) {
            // The server or the player went away. Once a response has started
            // there is nothing more to say; the player reconnects if it can.
            if (!responseStarted) {
                if (!closed) lastProblem = "Silo couldn't get the video from your server (${describe(e)})."
                lastProblem?.let { if (logged) log("request $number: $it") }
                writeSimple(output, 502, "Bad Gateway")
            } else if (logged) {
                log("request $number: stopped after ${megabytes(sent[0])} MB (${describe(e)})")
            }
        } finally {
            response?.close()
        }
    }

    private fun copyCounting(from: InputStream, to: OutputStream, media: Boolean, sent: LongArray) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = try {
                from.read(buffer)
            } catch (e: IOException) {
                if (!closed) lastProblem = "The video stopped arriving from your server (${describe(e)})."
                throw e
            }
            if (read < 0) return
            if (media) {
                val now = System.currentTimeMillis()
                if (firstServedAtMs == 0L) firstServedAtMs = now
                lastServedAtMs = now
            }
            to.write(buffer, 0, read)
            sent[0] += read
        }
    }

    private fun fetch(request: Request, token: String?): Response {
        val upstream = UpstreamRequest.Builder()
            .url(upstreamUrl)
            .method(request.method, null)
            // Never compressed: the player needs the file's own bytes and ranges.
            .header("Accept-Encoding", "identity")
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                for (name in FORWARDED_REQUEST_HEADERS) {
                    request.headers[name.lowercase(Locale.ROOT)]?.let { header(name, it) }
                }
                upstreamHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()
        val startedAt = System.nanoTime()
        try {
            return client.newCall(upstream).execute()
        } finally {
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            if (tookMs > slowestAnswerMs) slowestAnswerMs = tookMs
        }
    }

    private fun awaitRenewedToken(refused: String?): String? {
        val deadline = System.currentTimeMillis() + tokenRenewalWaitMs
        while (true) {
            val current = accessToken()
            if (current != refused || System.currentTimeMillis() >= deadline || closed) return current
            try {
                Thread.sleep(TOKEN_POLL_MS)
            } catch (_: InterruptedException) {
                return current
            }
        }
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>)

    /** Reads the request line and headers; null for anything malformed or too large. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = HashMap<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return Request(parts[0].uppercase(Locale.ROOT), parts[1], headers)
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length <= MAX_LINE) {
            when (val byte = input.read()) {
                -1 -> return null
                '\n'.code -> return line.toString().removeSuffix("\r")
                else -> line.append(byte.toChar())
            }
        }
        return null
    }

    private fun writeSimple(output: OutputStream, code: Int, reason: String) {
        try {
            output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        } catch (_: IOException) {
        }
    }

    /** [e] in a few words for [report]: its kind, and its message when it has one. */
    private fun describe(e: Throwable): String =
        listOfNotNull(e.javaClass.simpleName, e.message?.take(160)).joinToString(": ")

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        } catch (_: SocketException) {
        }
    }

    companion object {
        private const val TAG = "ZidooStreamRelay"
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_CONNECTIONS = 16
        private const val MAX_LOGGED_REQUESTS = 30
        private const val MAX_HEADERS = 64
        private const val MAX_LINE = 8_192
        private const val BUFFER_BYTES = 256 * 1024
        private const val REQUEST_READ_TIMEOUT_MS = 30_000
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 15_000L
        // Generous: a paused player stops reading, and the server keeps the
        // connection until its own write deadline.
        private const val UPSTREAM_READ_TIMEOUT_MS = 120_000L
        private const val TOKEN_POLL_MS = 500L

        private val FORWARDED_REQUEST_HEADERS = listOf("Range", "If-Range")
        private val RELAYED_RESPONSE_HEADERS = listOf(
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified",
        )

        private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)

        private fun randomSecret(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** [fileName] reduced to characters any player's URL parser accepts, keeping its extension. */
        internal fun safeFileName(fileName: String): String {
            val cleaned = fileName.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .trim('.')
            return cleaned.ifEmpty { "video" }
        }

        /**
         * The file name to serve under: "video" plus the extension of [serverFilePath],
         * else one for [container], so the Zidoo player sees the format it is getting.
         */
        fun fileNameFor(serverFilePath: String?, container: String?): String {
            val fromPath = serverFilePath
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            val ext = fromPath ?: when (container?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "mkv", "matroska" -> "mkv"
                "mpegts", "mpeg-ts" -> "ts"
                "mov", "qt" -> "mov"
                else -> container.trim().lowercase(Locale.ROOT).takeIf { it.all { c -> c.isLetterOrDigit() } } ?: "mkv"
            }
            return "video.$ext"
        }
    }
}

/**
 * The system's addresses for a host, IPv4 ones first. A home server's name
 * often has an IPv6 address that answers inside the home but is blocked from
 * outside it, and Android lists IPv6 first on a network that has IPv6. Tried
 * in that order, a connection from away can wait out the whole connect
 * timeout on IPv6 before trying IPv4. IPv6 is still tried when IPv4 fails.
 */
internal object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).sortedBy { it !is Inet4Address }
}
