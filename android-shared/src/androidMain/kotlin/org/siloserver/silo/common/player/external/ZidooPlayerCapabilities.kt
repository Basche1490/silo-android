package org.siloserver.silo.common.player.external

import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_DECLARED
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP

/**
 * What a hand-off tells the Silo server, so the server sends the file as it is.
 *
 * The server plans every session around what the player can handle, and
 * otherwise converts the file. Silo's own capability check describes Silo's
 * own player and the audio route behind it: an HDMI output that carries fewer
 * channels than a 5.1 or 7.1 track, Blu-ray `.m2ts` files that Silo's player
 * cannot open, or a Dolby Vision Profile 7 file can each make the server
 * convert. The Zidoo player handles all of these itself, as it would playing
 * the file from a share: it decodes DTS to PCM or passes it through, as the
 * Zidoo's own audio settings say, and plays Profile 7 natively. So a hand-off
 * starts from the detected capabilities and widens them to the Zidoo player's:
 *
 * - Video codecs are declared (`declared` evidence: the server checks the
 *   codec name only), adding the Blu-ray and DVD codecs.
 * - Every common audio codec is decodable, and the original-file delivery has
 *   no channel ceiling.
 * - Blu-ray, DVD and older containers are accepted.
 * - The original-file delivery claims `client_managed_dynamic_range_v1` and no
 *   client transformations: the player presents HDR and Dolby Vision itself.
 *
 * Everything else, including the delivery's subtitle and audio-track claims,
 * stays as detected.
 */
object ZidooPlayerCapabilities {
    /** The server's claim that the original-file player presents the source's dynamic range itself. */
    const val CLIENT_MANAGED_DYNAMIC_RANGE_V1_CLAIM = "client_managed_dynamic_range_v1"

    /** Video codecs every Zidoo player decodes, by the server's (ffprobe) names. */
    val VIDEO_CODECS: List<String> = listOf("h264", "hevc", "mpeg1video", "mpeg2video", "mpeg4", "vc1", "wmv3")

    /** Audio codecs the Zidoo player decodes or passes through, by the server's names. */
    val AUDIO_CODECS: List<String> = listOf(
        "aac", "ac3", "eac3", "eac3_joc", "truehd", "mlp", "dts", "dts_hd",
        "flac", "alac", "mp3", "mp2", "opus", "vorbis",
        "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s16be", "pcm_s24be", "pcm_f32le", "pcm_bluray", "pcm_dvd",
        "wmav2", "wmapro",
    )

    /** Containers the Zidoo player opens, by the server's names. */
    val CONTAINERS: List<String> = listOf(
        "mkv", "matroska", "webm", "mp4", "m4v", "mov", "avi",
        "ts", "mpegts", "m2ts", "mts", "mpeg", "mpg", "vob", "wmv", "asf", "flv", "ogg",
    )

    /** [detected] widened to the Zidoo player's codecs and containers. */
    fun capabilities(detected: ClientCodecCapabilities): ClientCodecCapabilities = detected.copy(
        videoEvidence = CAPABILITY_EVIDENCE_DECLARED,
        codecsVideo = detected.codecsVideo.union(VIDEO_CODECS),
        codecsVideoHardware = detected.codecsVideoHardware.union(VIDEO_CODECS),
        codecsAudio = detected.codecsAudio.union(AUDIO_CODECS),
        containers = detected.containers.union(CONTAINERS),
    )

    /** [detected] with its original-file delivery describing the Zidoo player. */
    fun playbackContext(detected: ClientPlaybackContext): ClientPlaybackContext = detected.copy(
        deliveries = detected.deliveries.mapValues { (deliveryClass, delivery) ->
            if (deliveryClass != DELIVERY_CLASS_ORIGINAL_HTTP) {
                delivery
            } else {
                delivery.copy(
                    enabled = true,
                    supportedOnDevice = true,
                    containers = delivery.containers.union(CONTAINERS),
                    videoCodecs = delivery.videoCodecs.union(VIDEO_CODECS),
                    audioDecodeCodecs = delivery.audioDecodeCodecs.union(AUDIO_CODECS),
                    maxChannels = null,
                    transformations = emptyList(),
                    validatedClaims = delivery.validatedClaims.union(listOf(CLIENT_MANAGED_DYNAMIC_RANGE_V1_CLAIM)),
                )
            }
        },
    )

    /** [this] followed by whatever of [extra] it lacks, compared without case. */
    private fun List<String>.union(extra: List<String>): List<String> {
        val seen = mapTo(HashSet()) { it.lowercase() }
        return this + extra.filter { seen.add(it.lowercase()) }
    }
}
