package org.siloserver.silo.common.player.external

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexToZidooLaunchTest {

    @Test
    fun buildsTheDocumentedShape() {
        val uri = PlexToZidooLaunch.buildUri(
            PlexToZidooLaunch.Request(
                serverFilePath = "/media/Movies/Dune (2021)/Dune.mkv",
                title = "Dune: Part One",
                startPositionMs = 90_500,
            ),
        )

        assertEquals(
            "/silo/no-path-substitution-matched" +
                "?PlexToZidoo-ViewOffset=90500" +
                "&PlexToZidoo-Title=Dune%3A%20Part%20One" +
                "&PlexToZidoo-Path=/media/Movies/Dune%20%282021%29/Dune.mkv",
            uri,
        )
    }

    @Test
    fun decodesBackToTheSamePathTheWayPlexToZidooReadsIt() {
        // Characters that would break PlexToZidoo's `[^&]+` match or its URLDecoder.
        val path = "/media/TV/Tom & Jerry's #1 Fan?/S01E01 + extras – café.mkv"
        val parsed = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(
                PlexToZidooLaunch.Request(
                    serverFilePath = path,
                    title = "Tom & Jerry: 100% + more",
                    startPositionMs = 1_000,
                    audioIndex = 2,
                    subtitleIndex = 3,
                ),
            ),
        )

        assertEquals(path, parsed.params["Path"])
        assertEquals("Tom & Jerry: 100% + more", parsed.params["Title"])
        assertEquals("1000", parsed.params["ViewOffset"])
        assertEquals("2", parsed.params["AudioIndex"])
        assertEquals("3", parsed.params["SubtitleIndex"])
        // Once PlexToZidoo strips its parameters, only the placeholder is left.
        assertEquals(PlexToZidooLaunch.UNMAPPED_PLACEHOLDER, parsed.remainder)
    }

    @Test
    fun neverUsesAFileUriBecauseAndroidRefusesToSendOne() {
        val uri = assertNotNull(PlexToZidooLaunch.buildUri(PlexToZidooLaunch.Request("/media/a.mkv", "A", startPositionMs = 1)))

        // Android's FileUriExposedException check looks only at the scheme;
        // a scheme-less path passes it.
        assertFalse(uri.startsWith("file:"))
        assertTrue(uri.startsWith(PlexToZidooLaunch.UNMAPPED_PLACEHOLDER + "?"))
    }

    @Test
    fun omitsOptionalParametersThatAreNotSet() {
        val parsed = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(
                PlexToZidooLaunch.Request(serverFilePath = "/m/a.mkv", title = "  ", startPositionMs = 0),
            ),
        )

        assertEquals(setOf("ViewOffset", "Path"), parsed.params.keys)
        assertEquals("0", parsed.params["ViewOffset"])
    }

    @Test
    fun keepsAudioIndexZeroButNotSubtitleIndexZero() {
        // Zidoo audio_idx is 0-based and subtitle_idx is 1-based.
        val parsed = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(
                PlexToZidooLaunch.Request(
                    serverFilePath = "/m/a.mkv",
                    title = "A",
                    startPositionMs = 5,
                    audioIndex = 0,
                    subtitleIndex = 0,
                ),
            ),
        )

        assertEquals("0", parsed.params["AudioIndex"])
        assertFalse("SubtitleIndex" in parsed.params)
    }

    @Test
    fun clampsTheStartPositionToWhatPlexToZidooCanParse() {
        val negative = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(PlexToZidooLaunch.Request("/m/a.mkv", "A", startPositionMs = -5)),
        )
        val huge = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(PlexToZidooLaunch.Request("/m/a.mkv", "A", startPositionMs = Long.MAX_VALUE)),
        )

        assertEquals("0", negative.params["ViewOffset"])
        assertEquals(Int.MAX_VALUE.toString(), huge.params["ViewOffset"])
    }

    @Test
    fun passesWindowsPathsThroughUnchanged() {
        val path = "D:\\Media\\Films\\a b.mkv"
        val parsed = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(PlexToZidooLaunch.Request(path, "A", startPositionMs = 1)),
        )

        // The path parameter stays exactly as the server reported it, so the
        // user's substitution rules match it unchanged.
        assertEquals(path, parsed.params["Path"])
    }

    @Test
    fun leavesSilosStreamForTheZidooPlayerWhenNoRuleMatches() {
        val stream = "http://192.168.1.20:8080/api/v2/stream/abc?st=signed.ref&token=jwt.part-two_3"
        val uri = PlexToZidooLaunch.buildUri(
            PlexToZidooLaunch.Request(
                serverFilePath = "/mnt/media/Movies/Heat (1995)/Heat.mkv",
                title = "Heat",
                startPositionMs = 60_000,
                streamUrl = stream,
            ),
        )
        val parsed = parseLikePlexToZidoo(uri)

        assertEquals(
            stream +
                "&PlexToZidoo-ViewOffset=60000" +
                "&PlexToZidoo-Title=Heat" +
                "&PlexToZidoo-Path=/mnt/media/Movies/Heat%20%281995%29/Heat.mkv",
            uri,
        )
        assertEquals("/mnt/media/Movies/Heat (1995)/Heat.mkv", parsed.params["Path"])
        // What PlexToZidoo plays when none of its rules match the path.
        assertEquals(stream, parsed.remainder)
    }

    @Test
    fun playsTheStreamAsItIsWhenThereIsNoPath() {
        val stream = "https://silo.example/api/v2/stream/abc?st=x"
        val parsed = parseLikePlexToZidoo(
            PlexToZidooLaunch.buildUri(
                PlexToZidooLaunch.Request(serverFilePath = null, title = "A", startPositionMs = 0, streamUrl = stream),
            ),
        )

        assertEquals(setOf("ViewOffset", "Title", "PathMapped"), parsed.params.keys)
        assertEquals(stream, parsed.remainder)
    }

    @Test
    fun hasNothingToPlayWithoutAPathOrAStream() {
        assertNull(PlexToZidooLaunch.buildUri(PlexToZidooLaunch.Request(serverFilePath = " ", title = "A", startPositionMs = 0)))
        assertNull(
            PlexToZidooLaunch.buildUri(
                PlexToZidooLaunch.Request(serverFilePath = null, title = "A", startPositionMs = 0, streamUrl = ""),
            ),
        )
    }

    @Test
    fun addsAQueryParameterWithTheRightSeparator() {
        assertEquals("http://h/s?token=a.b", PlexToZidooLaunch.withQueryParameter("http://h/s", "token", "a.b"))
        assertEquals("http://h/s?st=1&token=a%2Bb", PlexToZidooLaunch.withQueryParameter("http://h/s?st=1", "token", "a+b"))
    }

    private class Parsed(val params: Map<String, String>, val remainder: String)

    /** The same regex, decoding and stripping PlexToZidoo's `Play.onStart` applies in ZDMC mode. */
    private fun parseLikePlexToZidoo(uri: String?): Parsed {
        val data = assertNotNull(uri)
        val matcher = Regex("[?&]PlexToZidoo-([^=]+)=([^&]+)")
        val params = matcher.findAll(data).associate { m ->
            m.groupValues[1] to URLDecoder.decode(m.groupValues[2], "UTF-8")
        }
        return Parsed(params, matcher.replace(data, ""))
    }
}
