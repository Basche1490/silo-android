package org.siloserver.silo.common.player.external

/**
 * Translates Silo's track choices into the Zidoo player's `audio_idx` and
 * `subtitle_idx`.
 *
 * Both functions answer null when the choice cannot be carried over safely.
 * PlexToZidoo then omits the extra and the Zidoo player falls back to its own
 * language preferences, which beats selecting the wrong track.
 */
object ZidooTrackMapping {

    /**
     * Silo's audio index is a position in the file's audio-track list, and so
     * is Zidoo's 0-based `audio_idx`.
     */
    fun audioIndex(siloAudioOrdinal: Int?, audioTrackCount: Int): Int? =
        siloAudioOrdinal?.takeIf { it in 0 until audioTrackCount }

    /**
     * Silo numbers subtitle choices with the external sidecar files first and
     * the tracks embedded in the file after them. The Zidoo player's
     * `subtitle_idx` is 1-based and starts with the embedded tracks, so only an
     * embedded choice maps cleanly. Sidecar files are ordered by the Zidoo
     * player itself, and "off" (-1) has no equivalent extra.
     */
    fun subtitleIndex(siloCombinedIndex: Int?, externalTrackCount: Int, embeddedTrackCount: Int): Int? {
        val index = siloCombinedIndex ?: return null
        val embeddedOrdinal = index - externalTrackCount
        if (index < 0 || embeddedOrdinal < 0 || embeddedOrdinal >= embeddedTrackCount) return null
        return embeddedOrdinal + 1
    }
}
