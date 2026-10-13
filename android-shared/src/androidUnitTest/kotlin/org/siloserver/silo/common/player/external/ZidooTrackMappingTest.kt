package org.siloserver.silo.common.player.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZidooTrackMappingTest {

    @Test
    fun audioKeepsTheSamePosition() {
        assertEquals(0, ZidooTrackMapping.audioIndex(0, audioTrackCount = 3))
        assertEquals(2, ZidooTrackMapping.audioIndex(2, audioTrackCount = 3))
    }

    @Test
    fun audioOutsideTheFileIsDropped() {
        assertNull(ZidooTrackMapping.audioIndex(3, audioTrackCount = 3))
        assertNull(ZidooTrackMapping.audioIndex(-1, audioTrackCount = 3))
        assertNull(ZidooTrackMapping.audioIndex(null, audioTrackCount = 3))
        assertNull(ZidooTrackMapping.audioIndex(0, audioTrackCount = 0))
    }

    @Test
    fun embeddedSubtitlesBecomeOneBasedAfterTheSidecars() {
        // Two sidecar files (Silo 0 and 1), then three embedded tracks (Silo 2, 3, 4).
        assertEquals(1, ZidooTrackMapping.subtitleIndex(2, externalTrackCount = 2, embeddedTrackCount = 3))
        assertEquals(3, ZidooTrackMapping.subtitleIndex(4, externalTrackCount = 2, embeddedTrackCount = 3))
        // No sidecars: Silo 0 is the first embedded track.
        assertEquals(1, ZidooTrackMapping.subtitleIndex(0, externalTrackCount = 0, embeddedTrackCount = 1))
    }

    @Test
    fun sidecarOffAndUnknownSubtitlesAreLeftToTheZidooPlayer() {
        assertNull(ZidooTrackMapping.subtitleIndex(1, externalTrackCount = 2, embeddedTrackCount = 3))
        assertNull(ZidooTrackMapping.subtitleIndex(-1, externalTrackCount = 2, embeddedTrackCount = 3))
        assertNull(ZidooTrackMapping.subtitleIndex(5, externalTrackCount = 2, embeddedTrackCount = 3))
        assertNull(ZidooTrackMapping.subtitleIndex(null, externalTrackCount = 0, embeddedTrackCount = 3))
    }
}
