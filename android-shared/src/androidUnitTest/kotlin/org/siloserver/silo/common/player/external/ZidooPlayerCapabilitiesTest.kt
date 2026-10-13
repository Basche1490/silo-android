package org.siloserver.silo.common.player.external

import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_DECLARED
import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_EXACT
import org.siloserver.silo.model.playback.CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import org.siloserver.silo.model.playback.DELIVERY_CLASS_PROGRESSIVE
import org.siloserver.silo.model.playback.DeliveryCapability
import org.siloserver.silo.model.playback.PlaybackTransformationV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZidooPlayerCapabilitiesTest {

    // What Silo's own check reports on a box behind a TV without DTS:
    // Silo's FFmpeg decodes DTS, but m2ts and VC-1 are missing.
    private val detected = ClientCodecCapabilities(
        videoEvidence = CAPABILITY_EVIDENCE_EXACT,
        codecsVideo = listOf("hevc", "h264", "av1"),
        codecsVideoHardware = listOf("hevc", "h264", "av1"),
        codecsAudio = listOf("aac", "AC3", "dts"),
        containers = listOf("mkv", "mp4"),
        maxResolution = "2160p",
    )

    @Test
    fun declaresTheZidooPlayersCodecsAndContainers() {
        val caps = ZidooPlayerCapabilities.capabilities(detected)

        assertEquals(CAPABILITY_EVIDENCE_DECLARED, caps.videoEvidence)
        assertTrue("vc1" in caps.codecsVideo && "mpeg2video" in caps.codecsVideoHardware)
        assertTrue("truehd" in caps.codecsAudio && "dts_hd" in caps.codecsAudio)
        assertTrue("m2ts" in caps.containers && "vob" in caps.containers)
        // What was detected stays, first and once, whatever its case.
        assertEquals(listOf("hevc", "h264", "av1"), caps.codecsVideo.take(3))
        assertEquals(listOf("aac", "AC3", "dts"), caps.codecsAudio.take(3))
        assertEquals(1, caps.codecsAudio.count { it.equals("ac3", ignoreCase = true) })
        assertEquals("2160p", caps.maxResolution)
    }

    @Test
    fun liftsTheChannelCeilingAndLetsThePlayerManageDynamicRange() {
        val progressive = DeliveryCapability(maxChannels = 2, containers = listOf("mp4"))
        val context = ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "1.0.0",
            deliveries = mapOf(
                DELIVERY_CLASS_ORIGINAL_HTTP to DeliveryCapability(
                    containers = listOf("mkv"),
                    audioDecodeCodecs = listOf("aac"),
                    maxChannels = 2,
                    transformations = listOf(PlaybackTransformationV3(name = "client_dv7_to_dv81")),
                    validatedClaims = listOf(CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM),
                ),
                DELIVERY_CLASS_PROGRESSIVE to progressive,
            ),
        )

        val original = ZidooPlayerCapabilities.playbackContext(context).deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP)

        assertNull(original.maxChannels)
        assertTrue(original.transformations.isEmpty())
        assertEquals(
            listOf(CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM, ZidooPlayerCapabilities.CLIENT_MANAGED_DYNAMIC_RANGE_V1_CLAIM),
            original.validatedClaims,
        )
        assertTrue("dts" in original.audioDecodeCodecs && "m2ts" in original.containers)
        // Only the original-file delivery describes the Zidoo player.
        assertEquals(progressive, ZidooPlayerCapabilities.playbackContext(context).deliveries[DELIVERY_CLASS_PROGRESSIVE])
    }
}
