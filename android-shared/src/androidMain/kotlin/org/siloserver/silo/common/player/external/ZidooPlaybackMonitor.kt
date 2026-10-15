package org.siloserver.silo.common.player.external

import kotlinx.coroutines.delay
import kotlin.math.min

/** One position reading worth reporting to the server. Times are milliseconds. */
data class ZidooSample(
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
)

sealed interface ZidooMonitorOutcome {
    /** The Zidoo player never reported a loaded video inside the startup window. */
    data object PlayerDidNotStart : ZidooMonitorOutcome

    /** Playback ended. [lastPositionMs] is the last real position seen, 0 if none was. */
    data class Finished(val lastPositionMs: Long, val durationMs: Long) : ZidooMonitorOutcome
}

/**
 * Follows one Zidoo playback from launch to the end, the way the ZidooPlexMod
 * add-on does: wait for the player to report a loaded video, then read its
 * status every [POLL_INTERVAL_MS] until it stops answering.
 *
 * - Zero positions are not reported. The player can read 0 for a moment after
 *   it starts, and passing that on would overwrite a resume position.
 * - An occasional failed read is normal, so playback only counts as over after
 *   [MAX_MISSED_POLLS] failures in a row, or when the player reports no video or
 *   a stopped state.
 * - Skips only happen while the player is actually playing.
 */
class ZidooPlaybackMonitor(
    private val api: ZidooPlayerApi,
    private val planner: ZidooSkipPlanner,
    private val startupTimeoutMs: Long = 60_000L,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Runs until playback ends or the coroutine is cancelled.
     *
     * [onStarted] runs once, when the player first reports a loaded video.
     * [onSample] runs for each reading with a real position.
     * [giveUpStarting] is asked after each startup poll that finds no video;
     * true ends the wait before [startupTimeoutMs], for when the caller knows
     * the player has already closed. [keepWaiting] is asked once the timeout
     * has passed; true extends the wait, for when the caller can see the
     * player fetching the video although its status does not show it yet.
     */
    suspend fun run(
        onStarted: suspend () -> Unit,
        onSample: (ZidooSample) -> Unit,
        giveUpStarting: () -> Boolean = { false },
        keepWaiting: () -> Boolean = { false },
    ): ZidooMonitorOutcome {
        var status: ZidooPlayStatus = awaitPlayerStart(giveUpStarting, keepWaiting)
            ?: return ZidooMonitorOutcome.PlayerDidNotStart
        onStarted()

        var lastPositionMs = 0L
        var durationMs = status.durationMs
        while (status.durationMs > 0L && status.state != ZidooPlayState.STOPPED) {
            durationMs = status.durationMs
            if (status.positionMs > 0L) {
                lastPositionMs = status.positionMs
                onSample(ZidooSample(lastPositionMs, durationMs, status.state == ZidooPlayState.PAUSED))
                if (status.state == ZidooPlayState.PLAYING) skipIfDue(status)
            }
            status = nextReading() ?: return ZidooMonitorOutcome.Finished(lastPositionMs, durationMs)
        }
        return ZidooMonitorOutcome.Finished(lastPositionMs, durationMs)
    }

    /**
     * Polls until the player reports a loaded video. Null once [startupTimeoutMs]
     * has passed and [keepWaiting] says nothing is happening, or as soon as
     * [giveUp] says the player is not coming.
     */
    private suspend fun awaitPlayerStart(giveUp: () -> Boolean, keepWaiting: () -> Boolean): ZidooPlayStatus? {
        var waitedMs = 0L
        while (true) {
            pause(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
            val reading = api.status()
            if (reading != null && reading.durationMs > 0L) return reading
            if (giveUp() || waitedMs >= startupTimeoutMs && !keepWaiting()) return null
        }
    }

    /** The next answer from the player; null after [MAX_MISSED_POLLS] failed reads in a row. */
    private suspend fun nextReading(): ZidooPlayStatus? {
        repeat(MAX_MISSED_POLLS) {
            pause(POLL_INTERVAL_MS)
            api.status()?.let { return it }
        }
        return null
    }

    private suspend fun skipIfDue(status: ZidooPlayStatus) {
        val action = planner.nextAction(status.positionMs, status.durationMs) ?: return
        if (!api.seekTo(action.targetMs)) planner.release(action.kind)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_MISSED_POLLS = 3
    }
}

/**
 * The position to give the server when playback ends. A stop within a few
 * seconds of the end, which is where a credits skip leaves the player, counts
 * as having reached the end, so the server sees the title as finished.
 */
fun settledPositionMs(lastPositionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L || lastPositionMs <= 0L) return lastPositionMs
    val snapWindowMs = min(10_000L, durationMs / 20)
    return if (durationMs - lastPositionMs <= snapWindowMs) durationMs else lastPositionMs
}
