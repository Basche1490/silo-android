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
 * - A playback resuming at [startPositionMs] is held to that point while it
 *   gets going ([ResumeGuard]). The Zidoo player does not always start there:
 *   with a large file it can start from the beginning instead, sometimes after
 *   first reporting the resume point. Readings short of the resume point are
 *   not reported or used for skips, which would skip an intro the viewer is
 *   past or save a position from before the resume point, and a player
 *   playing short of it is sent there.
 */
class ZidooPlaybackMonitor(
    private val api: ZidooPlayerApi,
    private val planner: ZidooSkipPlanner,
    /** Where playback was asked to start, in milliseconds; 0 for the start of the file. */
    private val startPositionMs: Long = 0L,
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
        val resume = ResumeGuard(startPositionMs, durationMs)
        while (status.durationMs > 0L && status.state != ZidooPlayState.STOPPED) {
            durationMs = status.durationMs
            val shortOfResumePoint = resume.isShort(status)
            if (resume.shouldSeek()) api.seekTo(resume.resumeAtMs)
            if (!shortOfResumePoint && status.positionMs > 0L) {
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
 * Holds a resuming playback to its resume point for its first
 * [WATCH_PLAYING_READINGS] readings of actual playback.
 *
 * A reading is short of the resume point when the player has not reached it
 * yet, or, once it has, when it is back within [RESTART_WINDOW_MS] of the
 * start: a player that reports the resume point and then starts the file over
 * looks like that. A viewer rewinding to anywhere later is left alone. Every
 * [SEEK_EVERY_PLAYING_READINGS]th reading that is playing and short asks the
 * player to go to the resume point, up to [MAX_SEEKS] times. Readings while the
 * player loads or is paused never ask: a large file can take many seconds to
 * start, and a jump asked for meanwhile is ignored.
 */
internal class ResumeGuard(startPositionMs: Long, durationMs: Long) {
    /** The resume point, or 0 when the playback starts from the beginning or the file has no room for it. */
    val resumeAtMs: Long =
        startPositionMs.takeIf { it > TOLERANCE_MS && (durationMs <= 0L || it < durationMs - TOLERANCE_MS) } ?: 0L

    private val detectsRestart = resumeAtMs - TOLERANCE_MS > 2 * RESTART_WINDOW_MS
    private var playingReadingsLeft = if (resumeAtMs > 0L) WATCH_PLAYING_READINGS else 0
    private var reached = false
    private var playingShortReadings = 0
    private var seeks = 0
    private var seekDue = false

    /** Whether [status] is short of the resume point, so neither reported nor used for skips. */
    fun isShort(status: ZidooPlayStatus): Boolean {
        seekDue = false
        if (playingReadingsLeft <= 0) return false
        val playing = status.state == ZidooPlayState.PLAYING
        if (playing) playingReadingsLeft--
        val position = status.positionMs
        val short = if (!reached) {
            position < resumeAtMs - TOLERANCE_MS
        } else {
            detectsRestart && position < RESTART_WINDOW_MS
        }
        if (!short) {
            if (position >= resumeAtMs - TOLERANCE_MS) reached = true
            playingShortReadings = 0
        } else if (playing) {
            playingShortReadings++
            if (playingShortReadings % SEEK_EVERY_PLAYING_READINGS == 0 && seeks < MAX_SEEKS) {
                seeks++
                seekDue = true
            }
        }
        return short
    }

    /** True right after the [isShort] reading that calls for a jump to [resumeAtMs]. */
    fun shouldSeek(): Boolean = seekDue

    companion object {
        /** How far before the resume point the player may land and still count as there (it starts on a keyframe). */
        const val TOLERANCE_MS = 10_000L

        /** How close to the start a player that had reached the resume point counts as having started over. */
        const val RESTART_WINDOW_MS = 60_000L

        /** Readings of actual playback the resume point is held for. */
        const val WATCH_PLAYING_READINGS = 30

        /** Playing readings short of the resume point between each request to go there. */
        const val SEEK_EVERY_PLAYING_READINGS = 3

        const val MAX_SEEKS = 5
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
