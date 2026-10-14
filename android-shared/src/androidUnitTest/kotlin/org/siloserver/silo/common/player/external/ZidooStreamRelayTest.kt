package org.siloserver.silo.common.player.external

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZidooStreamRelayTest {

    private val body = ByteArray(1_000_003) { (it % 251).toByte() }
    private val seenAuth = CopyOnWriteArrayList<String?>()
    private val seenQuery = CopyOnWriteArrayList<String?>()
    private val seenMethods = CopyOnWriteArrayList<String>()
    private val validToken = AtomicReference("fresh")

    // A stand-in for Silo's /api/v2/stream route: bearer auth, byte ranges, HEAD.
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v2/stream/") { exchange -> serveStream(exchange) }
        start()
    }
    private val upstreamUrl = "http://127.0.0.1:${upstream.address.port}/api/v2/stream/0b9e6a1c?st=signed.ref"
    private val relays = mutableListOf<ZidooStreamRelay>()

    @AfterTest
    fun tearDown() {
        relays.forEach { it.close() }
        upstream.stop(0)
    }

    private fun serveStream(exchange: HttpExchange) {
        val auth = exchange.requestHeaders.getFirst("Authorization")
        seenAuth += auth
        seenQuery += exchange.requestURI.rawQuery
        seenMethods += exchange.requestMethod
        if (auth != "Bearer ${validToken.get()}") {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        exchange.responseHeaders.add("Content-Type", "video/x-matroska")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("ETag", "\"abc\"")
        val range = exchange.requestHeaders.getFirst("Range")
        val (start, end) = range?.removePrefix("bytes=")?.split('-')?.let { (a, b) ->
            a.toInt() to (b.toIntOrNull() ?: (body.size - 1))
        } ?: (0 to body.size - 1)
        val length = end - start + 1
        val head = exchange.requestMethod == "HEAD"
        if (range != null) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
        }
        if (head) exchange.responseHeaders.add("Content-Length", length.toString())
        exchange.sendResponseHeaders(if (range != null) 206 else 200, if (head) -1 else length.toLong())
        if (!head) exchange.responseBody.use { it.write(body, start, length) }
        exchange.close()
    }

    private fun relay(token: () -> String? = { validToken.get() }) =
        ZidooStreamRelay(upstreamUrl, "video.mkv", token, tokenRenewalWaitMs = 3_000).also { relays += it }

    private fun open(url: String, method: String = "GET", range: String? = null): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
            connectTimeout = 5_000
            readTimeout = 10_000
        }

    @Test
    fun servesTheWholeFileUnderAnAddressWithItsExtensionAndNoCredential() {
        val relay = relay()
        assertTrue(relay.url.startsWith("http://127.0.0.1:") && relay.url.endsWith("/video.mkv"), relay.url)
        assertTrue("token" !in relay.url && "st=" !in relay.url)

        val connection = open(relay.url)
        assertEquals(200, connection.responseCode)
        assertEquals("video/x-matroska", connection.getHeaderField("Content-Type"))
        assertEquals(body.size.toString(), connection.getHeaderField("Content-Length"))
        assertContentEquals(body, connection.inputStream.use { it.readBytes() })
        assertEquals(body.size.toLong(), relay.bytesServed)
        assertTrue(relay.servedWithin(60_000))
        assertEquals("Bearer fresh", seenAuth.single())
        // The signed stream reference the server issued goes along unchanged.
        assertEquals("st=signed.ref", seenQuery.single())
    }

    @Test
    fun passesByteRangesThrough() {
        val connection = open(relay().url, range = "bytes=500000-500099")

        assertEquals(206, connection.responseCode)
        assertEquals("bytes 500000-500099/${body.size}", connection.getHeaderField("Content-Range"))
        assertContentEquals(body.copyOfRange(500_000, 500_100), connection.inputStream.use { it.readBytes() })
    }

    @Test
    fun answersHeadWithoutABody() {
        val connection = open(relay().url, method = "HEAD")

        assertEquals(200, connection.responseCode)
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
        assertEquals("video/x-matroska", connection.getHeaderField("Content-Type"))
        assertEquals("HEAD", seenMethods.single())
    }

    @Test
    fun retriesWithTheRenewedTokenWhenTheServerRefusesAnExpiredOne() {
        // The app still holds the old token for a moment after it expired.
        validToken.set("renewed")
        var calls = 0
        val relay = relay { if (++calls < 3) "expired" else "renewed" }

        val connection = open(relay.url, range = "bytes=0-9")

        assertEquals(206, connection.responseCode)
        assertContentEquals(body.copyOfRange(0, 10), connection.inputStream.use { it.readBytes() })
        assertEquals(listOf("Bearer expired", "Bearer renewed"), seenAuth.toList())
    }

    @Test
    fun keepsServingAfterThePlayerDropsAConnectionMidFile() {
        val relay = relay()
        // A seek: the player stops reading partway and opens a new range.
        val first = open(relay.url)
        first.inputStream.use { stream -> repeat(2_000) { stream.read() } }
        first.disconnect()

        val next = open(relay.url, range = "bytes=900000-900009")

        assertEquals(206, next.responseCode)
        assertContentEquals(body.copyOfRange(900_000, 900_010), next.inputStream.use { it.readBytes() })
    }

    @Test
    fun refusesOtherPathsAndMethods() {
        val relay = relay()
        val wrongPath = relay.url.substringBeforeLast('/').substringBeforeLast('/') + "/0000/video.mkv"

        assertEquals(404, open(wrongPath).responseCode)
        assertEquals(405, open(relay.url, method = "DELETE").responseCode)
        assertTrue(seenAuth.isEmpty(), "nothing reached the server")
        assertEquals(0L, relay.bytesServed)
        assertTrue(!relay.servedWithin(60_000))
    }

    @Test
    fun stopsServingOnceClosed() {
        val relay = relay()
        relay.close()

        assertFailsWith<ConnectException> { open(relay.url).responseCode }
    }

    @Test
    fun namesTheFileAfterTheSourceExtensionOrContainer() {
        assertEquals("video.mkv", ZidooStreamRelay.fileNameFor("/mnt/media/Movies/Heat (1995)/Heat.mkv", "mkv"))
        assertEquals("video.m2ts", ZidooStreamRelay.fileNameFor("/mnt/media/BD/STREAM/00001.M2TS", "m2ts"))
        assertEquals("video.ts", ZidooStreamRelay.fileNameFor(null, "mpegts"))
        assertEquals("video.mp4", ZidooStreamRelay.fileNameFor("/a/b/no-extension", "mp4"))
        assertEquals("video.mkv", ZidooStreamRelay.fileNameFor(null, null))
        assertEquals("video.mkv", ZidooStreamRelay.fileNameFor("/a/b/c.d/e", "matroska"))
    }
}
