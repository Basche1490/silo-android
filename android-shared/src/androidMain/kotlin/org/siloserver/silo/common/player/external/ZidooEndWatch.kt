package org.siloserver.silo.common.player.external

/**
 * Decides whether an episode the Zidoo player has closed played to its end,
 * where Up Next is offered. Silo's own player offers Up Next when playback
 * crosses the start of the credits, or when the video ends. This applies the
 * same rule to the positions the Zidoo player reports:
 *
 * - Playback crossed into the credits while playing, and is still in them.
 *   Seeking into the credits, or resuming inside them, does not count.
 * - A credits marker in the first half of the episode is not the end credits
 *   (some files mark the opening credits that way), so it never counts.
 * - Stopping in the last [END_WINDOW_MS], or close enough to the end that the
 *   position is saved as finished, counts with or without a marker.
 *
 * Feed it every position reading with [onSample], then ask [reachedEnd].
 */
class ZidooEndWatch(creditsStartSeconds: Double?) {
    private val creditsStartMs: Long? = creditsStartSeconds
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { (it * 1000.0).toLong() }

    private var previousPositionMs = -1L
    private var crossedIntoCredits = false

    /** One position reading of the playback, in the order the player reported them. */
    fun onSample(positionMs: Long, durationMs: Long) {
        val previous = previousPositionMs
        previousPositionMs = positionMs
        val credits = creditsStartMs ?: return
        if (previous < 0L || durationMs <= 0L || credits < durationMs / 2) return
        val playedInto = previous < credits && positionMs >= credits && positionMs - previous <= MAX_PLAYING_STEP_MS
        if (playedInto) crossedIntoCredits = true
    }

    /** Whether playback that stopped at [lastPositionMs] of [durationMs] reached the end. */
    fun reachedEnd(lastPositionMs: Long, durationMs: Long): Boolean {
        if (lastPositionMs <= 0L || durationMs <= 0L) return false
        if (settledPositionMs(lastPositionMs, durationMs) >= durationMs) return true
        if (durationMs - lastPositionMs <= END_WINDOW_MS) return true
        val credits = creditsStartMs ?: return false
        return crossedIntoCredits && lastPositionMs >= credits
    }

    companion object {
        /** Without the credits, how close to the end a stop still counts as the end. */
        const val END_WINDOW_MS = 30_000L

        /**
         * The largest step between two readings that is still playback rather
         * than a seek. Readings come every second, or a few seconds apart when
         * the player misses one.
         */
        const val MAX_PLAYING_STEP_MS = 8_000L
    }
}
