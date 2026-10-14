package org.siloserver.silo.common.player.external

import android.util.Log
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * passed through, and every response closes its connection. The secret path
 * keeps other apps on the box from using the relay.
 *
 * Blocking I/O on its own threads: one accepts, one per connection.
 */
class ZidooStreamRelay(
    private val upstreamUrl: String,
    fileName: String,
    /** The access token to sign the next request with; called on a relay thread. */
    private val accessToken: () -> String?,
    /** Upper bound for waiting on a renewed token after the server refused one. */
    private val tokenRenewalWaitMs: Long = 15_000L,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : Closeable {

    private val path = "/" + randomSecret() + "/" + safeFileName(fileName)
    private val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
    private val active = AtomicInteger(0)

    @Volatile
    private var closed = false

    private val served = AtomicLong(0)

    @Volatile
    private var firstServedAtMs = 0L

    @Volatile
    private var lastServedAtMs = 0L

    /** Media bytes sent to the player so far. */
    val bytesServed: Long get() = served.get()

    /** True when the player received media within the last [windowMs]: it is playing, or buffering to play. */
    fun servedWithin(windowMs: Long): Boolean =
        lastServedAtMs != 0L && System.currentTimeMillis() - lastServedAtMs <= windowMs

    /**
     * True when the player kept receiving media over at least [spanMs]. Opening
     * a file the player then refuses takes seconds; this means it played.
     */
    fun servedForAtLeast(spanMs: Long): Boolean =
        firstServedAtMs != 0L && lastServedAtMs - firstServedAtMs >= spanMs

    /** The address to give the Zidoo player. */
    val url: String = "http://$LOOPBACK:${server.localPort}$path"

    init {
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
            } catch (_: Exception) {
                break
            }
            if (active.incrementAndGet() > MAX_CONNECTIONS) {
                active.decrementAndGet()
                socket.closeQuietly()
                continue
            }
            Thread({
                // Nothing may escape a relay thread: an uncaught exception
                // would end Silo's process, and the film with it.
                try {
                    serve(socket)
                } catch (e: Exception) {
                    Log.w(TAG, "Relay request failed", e)
                } finally {
                    active.decrementAndGet()
                    socket.closeQuietly()
                }
            }, "ZidooStreamRelay-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = REQUEST_READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val request = readRequest(input) ?: return
        if (request.method != "GET" && request.method != "HEAD") {
            return writeSimple(output, 405, "Method Not Allowed")
        }
        if (request.target.substringBefore('?') != path) {
            return writeSimple(output, 404, "Not Found")
        }
        var connection: HttpURLConnection? = null
        var responseStarted = false
        try {
            var token = accessToken()
            connection = openUpstream(request, token)
            if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // The token ran out. The app renews it within one progress
                // report, so wait for a different one and try once more.
                connection.disconnect()
                token = awaitRenewedToken(token)
                connection = openUpstream(request, token)
            }
            val code = connection.responseCode
            val header = StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ')
                .append(connection.responseMessage?.takeIf { it.isNotBlank() && '\n' !in it && '\r' !in it } ?: "Status")
                .append("\r\n")
            for (name in RELAYED_RESPONSE_HEADERS) {
                connection.getHeaderField(name)?.let { header.append(name).append(": ").append(it).append("\r\n") }
            }
            header.append("Connection: close\r\n\r\n")
            responseStarted = true
            output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method == "GET") {
                val body: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
                body?.use { copyCounting(it, output, media = code < 300) }
            }
            output.flush()
        } catch (_: IOException) {
            // The server or the player went away. Once a response has started
            // there is nothing more to say; the player reconnects if it can.
            if (!responseStarted) writeSimple(output, 502, "Bad Gateway")
        } finally {
            connection?.disconnect()
        }
    }

    private fun copyCounting(from: InputStream, to: OutputStream, media: Boolean) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) return
            if (media) {
                val now = System.currentTimeMillis()
                if (firstServedAtMs == 0L) firstServedAtMs = now
                lastServedAtMs = now
                served.addAndGet(read.toLong())
            }
            to.write(buffer, 0, read)
        }
    }

    private fun openUpstream(request: Request, token: String?): HttpURLConnection =
        openConnection(URI(upstreamUrl).toURL()).apply {
            requestMethod = request.method
            instanceFollowRedirects = true
            connectTimeout = UPSTREAM_CONNECT_TIMEOUT_MS
            readTimeout = UPSTREAM_READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            for (name in FORWARDED_REQUEST_HEADERS) {
                request.headers[name.lowercase(Locale.ROOT)]?.let { setRequestProperty(name, it) }
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
        private const val MAX_HEADERS = 64
        private const val MAX_LINE = 8_192
        private const val BUFFER_BYTES = 256 * 1024
        private const val REQUEST_READ_TIMEOUT_MS = 30_000
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 15_000
        // Generous: a paused player stops reading, and the server keeps the
        // connection until its own write deadline.
        private const val UPSTREAM_READ_TIMEOUT_MS = 120_000
        private const val TOKEN_POLL_MS = 500L

        private val FORWARDED_REQUEST_HEADERS = listOf("Range", "If-Range")
        private val RELAYED_RESPONSE_HEADERS = listOf(
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified",
        )

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
