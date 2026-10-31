package org.siloserver.silo.common.player.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZidooEndWatchTest {

    private val durationMs = 2_700_000L // a 45-minute episode

    /** Feeds one reading per second from [fromSeconds] to [toSeconds] inclusive; returns the last position. */
    private fun ZidooEndWatch.play(fromSeconds: Int, toSeconds: Int): Long {
        for (second in fromSeconds..toSeconds) onSample(second * 1000L, durationMs)
        return toSeconds * 1000L
    }

    @Test
    fun stoppingPartwayDoesNotOfferUpNextEvenWithAnEarlyCreditsMarker() {
        // The opening credits, two minutes in, marked as credits.
        val watch = ZidooEndWatch(creditsStartSeconds = 120.0)

        val stoppedAt = watch.play(0, 1_200)

        assertFalse(watch.reachedEnd(stoppedAt, durationMs))
    }

    @Test
    fun playingIntoTheEndCreditsOffersUpNext() {
        val watch = ZidooEndWatch(creditsStartSeconds = 2_580.0)

        val stoppedAt = watch.play(2_500, 2_600)

        assertTrue(watch.reachedEnd(stoppedAt, durationMs))
    }

    @Test
    fun seekingIntoTheCreditsDoesNotOfferUpNext() {
        val watch = ZidooEndWatch(creditsStartSeconds = 2_580.0)
        watch.play(1_000, 1_010)

        val stoppedAt = watch.play(2_600, 2_605)

        assertFalse(watch.reachedEnd(stoppedAt, durationMs))
    }

    @Test
    fun resumingInsideTheCreditsDoesNotOfferUpNextUntilTheEnd() {
        val watch = ZidooEndWatch(creditsStartSeconds = 2_580.0)

        assertFalse(watch.reachedEnd(watch.play(2_600, 2_610), durationMs))
        assertTrue(watch.reachedEnd(watch.play(2_611, 2_680), durationMs))
    }

    @Test
    fun rewindingOutOfTheCreditsDoesNotOfferUpNext() {
        val watch = ZidooEndWatch(creditsStartSeconds = 2_580.0)
        watch.play(2_570, 2_590)

        val stoppedAt = watch.play(1_500, 1_510)

        assertFalse(watch.reachedEnd(stoppedAt, durationMs))
    }

    @Test
    fun stoppingInTheLastHalfMinuteOffersUpNextWithoutAMarker() {
        val watch = ZidooEndWatch(creditsStartSeconds = null)

        assertFalse(watch.reachedEnd(watch.play(2_000, 2_660), durationMs))
        assertTrue(watch.reachedEnd(watch.play(2_661, 2_680), durationMs))
    }
}
