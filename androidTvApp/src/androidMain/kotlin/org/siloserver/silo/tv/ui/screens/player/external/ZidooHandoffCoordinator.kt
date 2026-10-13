package org.siloserver.silo.tv.ui.screens.player.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.player.external.PlexToZidooLaunch
import org.siloserver.silo.common.player.external.SkipRange
import org.siloserver.silo.common.player.external.ZidooMonitorOutcome
import org.siloserver.silo.common.player.external.ZidooPlaybackMonitor
import org.siloserver.silo.common.player.external.ZidooPlayerApi
import org.siloserver.silo.common.player.external.ZidooSkipPlanner
import org.siloserver.silo.common.player.external.ZidooTrackMapping
import org.siloserver.silo.common.player.external.settledPositionMs
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.model.catalog.TimeRange

/** Where one hand-off to the Zidoo player stands. [runId] ties a state to the run that produced it. */
sealed interface ZidooHandoffState {
    val runId: Long

    data object Idle : ZidooHandoffState {
        override val runId: Long = 0L
    }

    /** Asking the Silo server for a playback session. */
    data class Preparing(override val runId: Long) : ZidooHandoffState

    /** PlexToZidoo has been opened; waiting for the Zidoo player to load the file. */
    data class Launched(override val runId: Long, val title: String) : ZidooHandoffState

    data class Playing(override val runId: Long, val title: String) : ZidooHandoffState

    /** The Zidoo player closed; sending the final position to the server. */
    data class Saving(override val runId: Long, val title: String) : ZidooHandoffState

    data class Finished(override val runId: Long) : ZidooHandoffState

    data class Failed(
        override val runId: Long,
        val message: String,
        /** True when the built-in player is a sensible next step. */
        val canPlayInSilo: Boolean,
    ) : ZidooHandoffState
}

/** States during which the Zidoo player may be running on Silo's behalf. */
internal fun ZidooHandoffState.isActive(): Boolean = when (this) {
    is ZidooHandoffState.Preparing,
    is ZidooHandoffState.Launched,
    is ZidooHandoffState.Playing,
    is ZidooHandoffState.Saving,
    -> true
    ZidooHandoffState.Idle,
    is ZidooHandoffState.Finished,
    is ZidooHandoffState.Failed,
    -> false
}

/**
 * Plays a title in the Zidoo player instead of Silo's own player.
 *
 * One run does what the ZidooPlexMod add-on does for Plex, with Silo as the
 * source of truth:
 * 1. Start a normal Silo playback session. That session is what records resume
 *    progress, and it supplies the file path, the resume point, the audio and
 *    subtitle choice, and the intro and credits markers.
 * 2. Open PlexToZidoo, which maps the server path to the user's SMB or NFS
 *    share and starts the Zidoo player at the resume point.
 * 3. Read the Zidoo player's position every second: skip the intro or credits
 *    when they are reached, and feed the position to the session.
 * 4. When the Zidoo player closes, send the final position and stop the
 *    session, so the title resumes there next time (or counts as watched).
 *
 * Runs live in a process-wide scope rather than a screen's, because the Silo
 * screen sits behind the Zidoo player for the whole playback.
 * [ZidooHandoffService] keeps the process alive meanwhile.
 */
class ZidooHandoffCoordinator(
    private val appContext: Context,
    private val starter: VideoPlaybackStarter,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    private val sessionManager: PlaybackSessionManager,
    private val settings: ExternalPlayerSettings,
    private val zidoo: ZidooPlayerApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val plexToZidooInstalled: () -> Boolean = { isPlexToZidooInstalled(appContext) },
    /**
     * Told whenever [isHandingOff] may have changed: the Zidoo player paused or
     * resumed, or a hand-off ended. The TV app forwards it to the profile
     * tracker, as the phone does for Cast. Called on the coordinator's scope.
     */
    private val onPlaybackActivityChanged: () -> Unit = {},
    /**
     * How long after a hand-off ends before [onPlaybackActivityChanged] hears
     * about it. Silo normally comes back to the front within a second of the
     * Zidoo player closing; waiting lets that return land first, so coming
     * back from a film is treated the same way every time.
     */
    private val endNoticeDelayMs: Long = 5_000L,
    private val newMonitor: (ZidooPlayerApi, ZidooSkipPlanner) -> ZidooPlaybackMonitor =
        { api, planner -> ZidooPlaybackMonitor(api, planner) },
) {
    private val _state = MutableStateFlow<ZidooHandoffState>(ZidooHandoffState.Idle)
    val state: StateFlow<ZidooHandoffState> = _state.asStateFlow()

    init {
        // The app has one coordinator (a Koin single); see isHandingOffNow.
        current = this
    }

    private var job: Job? = null

    /** The Zidoo player's paused flag from the latest sample of the current run. */
    @Volatile
    private var zidooPaused = false

    // Seeded from the clock so an id a screen saved before Silo was killed
    // cannot match a run in the new process.
    private var lastRunId = System.currentTimeMillis()

    /**
     * Starts a run and returns its id.
     *
     * [launch] opens PlexToZidoo. Pass one that starts the activity from the
     * current Activity, so PlexToZidoo joins Silo's task and closing the Zidoo
     * player lands back in Silo rather than on the home screen. The run drops
     * its reference to [launch] as soon as it has been used, so the Activity
     * is not held for the length of the film.
     */
    fun start(request: VideoPlaybackStartRequest, launch: (Intent) -> Unit): Long {
        val previous = job
        val runId = ++lastRunId
        _state.value = ZidooHandoffState.Preparing(runId)
        val launcher = AtomicReference(launch)
        job = scope.launch {
            // Let an earlier run finish closing its session first, so that
            // cleanup cannot land on top of this run's session.
            previous?.cancelAndJoin()
            run(runId, request, launcher)
        }
        return runId
    }

    /** Abandons [runId] if it has not reached the Zidoo player yet. Returns true when it did so. */
    fun cancelIfPreparing(runId: Long): Boolean {
        val current = _state.value
        if (current !is ZidooHandoffState.Preparing || current.runId != runId) return false
        job?.cancel()
        _state.value = ZidooHandoffState.Idle
        return true
    }

    /** Clears a finished or failed [runId] once its screen has acted on it. */
    fun acknowledge(runId: Long) {
        val current = _state.value
        if (current.runId == runId &&
            (current is ZidooHandoffState.Finished || current is ZidooHandoffState.Failed)
        ) {
            _state.value = ZidooHandoffState.Idle
        }
    }

    /**
     * True while a run is handing off, playing or saving, and the Zidoo player
     * is not sitting paused. Silo counts that as using the profile; like a
     * phone's Cast session, a player left paused does not hold the profile.
     */
    fun isHandingOff(): Boolean = _state.value.isActive() && !zidooPaused

    /** Asks the Zidoo player to stop; the run then saves progress and finishes as usual. */
    fun stopZidooPlayer() {
        scope.launch { zidoo.stop() }
    }

    private suspend fun run(
        runId: Long,
        request: VideoPlaybackStartRequest,
        launcher: AtomicReference<((Intent) -> Unit)?>,
    ) {
        var sessionId: String? = null
        var sessionClosed = false
        var launched = false
        try {
            // Checked first, so a box without PlexToZidoo never opens a server session.
            if (!plexToZidooInstalled()) return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)

            val ready = when (val result = starter.start(request)) {
                is VideoPlaybackStartResult.Ready -> result
                is VideoPlaybackStartResult.Error -> return fail(runId, result.message, canPlayInSilo = true)
                is VideoPlaybackStartResult.ServerUnreachable ->
                    return fail(runId, "Silo can't reach your server right now.", canPlayInSilo = false)
            }
            val id = ready.sessionId
                ?: return fail(runId, "The server didn't open a playback session.", canPlayInSilo = true)
            sessionId = id

            // Checked before the session is confirmed, so this failure rolls
            // the unpublished session back.
            val fileId = ready.fileId ?: ready.mediaFileId
            val version = ready.versions.firstOrNull { it.fileId == fileId }
            val path = version?.filePath?.takeIf { it.isNotBlank() }
                ?: return fail(runId, NO_PATH_MESSAGE, canPlayInSilo = true)

            // The starter leaves the session unpublished until its owner takes
            // it; confirm it, as the built-in player does once it has mounted.
            val confirmed = withContext(NonCancellable) {
                sessionLifecycle.settlePendingPublicationIfCurrent(id, confirm = true) {
                    sessionManager.confirmVideoSessionPublication(id)
                }
            }
            // A non-cancellable block does not throw when it returns, so check
            // here: Back pressed during the confirm must not open PlexToZidoo.
            currentCoroutineContext().ensureActive()
            if (!confirmed) return fail(runId, "Playback could not be started. Try again.", canPlayInSilo = true)

            val subtitleTracks = version.subtitleTracks.orEmpty()
            val title = displayTitle(ready)
            val intent = plexToZidooIntent(
                PlexToZidooLaunch.Request(
                    serverFilePath = path,
                    title = title,
                    startPositionMs = (ready.sourceStartPositionSeconds.coerceAtLeast(0.0) * 1000).toLong(),
                    audioIndex = ZidooTrackMapping.audioIndex(
                        siloAudioOrdinal = ready.playbackPlan?.selectedTracks?.audioIndex ?: ready.audioTrackIndex,
                        audioTrackCount = version.audioTracks.orEmpty().size,
                    ),
                    subtitleIndex = ZidooTrackMapping.subtitleIndex(
                        siloCombinedIndex = ready.playbackPlan?.selectedTracks?.subtitleIndex
                            ?: request.subtitleTrackIndex,
                        externalTrackCount = subtitleTracks.count { it.external },
                        embeddedTrackCount = subtitleTracks.count { !it.external },
                    ),
                ),
            )

            val openPlexToZidoo = launcher.getAndSet(null)
            if (openPlexToZidoo == null || _state.value.runId != runId) return
            // Silo is still in the foreground here, which Android requires
            // before it allows a foreground service to start. The service
            // stops itself once this run leaves the active states.
            ZidooHandoffService.start(appContext)
            try {
                openPlexToZidoo(intent)
            } catch (_: ActivityNotFoundException) {
                return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Android refused to open PlexToZidoo", e)
                return fail(runId, LAUNCH_REFUSED_MESSAGE, canPlayInSilo = true)
            }
            setState(runId, ZidooHandoffState.Launched(runId, title))
            zidooPaused = false
            launched = true

            val monitor = newMonitor(
                zidoo,
                ZidooSkipPlanner(
                    intro = ready.intro?.toSkipRange(),
                    credits = ready.credits?.toSkipRange(),
                    settings = settings.skipSettings(),
                ),
            )
            val outcome = coroutineScope {
                // As in Silo's own player: when the server ends the session for
                // good (an administrator stopped it), playback ends too.
                val endedByServer = launch {
                    // The lifecycle repeats the notice on each progress tick, so
                    // a stop that fails is tried again on the next one.
                    sessionLifecycle.sessionEndedEvents.first { ended ->
                        ended == id && zidoo.stop()
                    }
                    Log.i(TAG, "The server ended this playback session; stopped the Zidoo player")
                }
                try {
                    monitor.run(
                        onStarted = { setState(runId, ZidooHandoffState.Playing(runId, title)) },
                        onSample = { sample ->
                            if (sample.paused != zidooPaused) {
                                zidooPaused = sample.paused
                                onPlaybackActivityChanged()
                            }
                            sessionLifecycle.reportPosition(
                                positionSec = sample.positionMs / 1000.0,
                                durationSec = sample.durationMs / 1000.0,
                                isPaused = sample.paused,
                                expectedSessionId = id,
                            )
                        },
                    )
                } finally {
                    endedByServer.cancel()
                }
            }
            when (outcome) {
                ZidooMonitorOutcome.PlayerDidNotStart ->
                    return fail(runId, PLAYER_DID_NOT_START_MESSAGE, canPlayInSilo = true)
                is ZidooMonitorOutcome.Finished -> {
                    setState(runId, ZidooHandoffState.Saving(runId, title))
                    if (outcome.lastPositionMs > 0L) {
                        sessionLifecycle.reportPosition(
                            positionSec = settledPositionMs(outcome.lastPositionMs, outcome.durationMs) / 1000.0,
                            durationSec = outcome.durationMs / 1000.0,
                            isPaused = true,
                            expectedSessionId = id,
                        )
                    }
                    // Stopping flushes that final position, then closes the session.
                    val stopped = withContext(NonCancellable) { sessionLifecycle.stop(expectedSessionId = id) }
                    if (!stopped) Log.w(TAG, "Another playback took over before the final position was saved")
                    sessionClosed = true
                    setState(runId, ZidooHandoffState.Finished(runId))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Zidoo hand-off failed", e)
            fail(runId, "Something went wrong handing playback to the Zidoo player.", canPlayInSilo = true)
        } finally {
            launcher.set(null)
            val unclosed = sessionId?.takeUnless { sessionClosed }
            if (unclosed != null) withContext(NonCancellable) { retire(unclosed) }
            // A run that ended without reaching a final state (it was
            // cancelled) must not leave the service and screen waiting on it.
            if (_state.value.runId == runId && _state.value.isActive()) {
                _state.value = ZidooHandoffState.Idle
            }
            zidooPaused = false
            if (launched) {
                scope.launch {
                    delay(endNoticeDelayMs)
                    onPlaybackActivityChanged()
                }
            }
        }
    }

    /**
     * Closes a session this run will not finish. It writes no progress sample
     * of its own; the session's regular reports may already have recorded the
     * start position, exactly as a failed start in the built-in player does.
     */
    private suspend fun retire(sessionId: String) {
        try {
            val rolledBack = sessionLifecycle.settlePendingPublicationIfCurrent(sessionId, confirm = false) {
                sessionManager.rollbackUnpublishedVideoSession(sessionId)
            }
            if (rolledBack || sessionLifecycle.retireUnpublishedSession(sessionId)) return
            // Ownership has moved on. This records the session as an orphan
            // rather than risk stopping the newer playback that replaced it.
            sessionManager.abandonActiveVideoSessionIfCurrent(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not close playback session $sessionId", e)
        }
    }

    /** Publishes [state] only while [runId] is still the current run. */
    private fun setState(runId: Long, state: ZidooHandoffState) {
        if (_state.value.runId == runId) _state.value = state
    }

    private fun fail(runId: Long, message: String, canPlayInSilo: Boolean) =
        setState(runId, ZidooHandoffState.Failed(runId, message, canPlayInSilo))

    private fun displayTitle(ready: VideoPlaybackStartResult.Ready): String {
        val series = ready.seriesTitle?.takeIf { it.isNotBlank() }
        val season = ready.seasonNumber
        val episode = ready.episodeNumber
        return if (series != null && season != null && episode != null) {
            "%s S%02dE%02d · %s".format(series, season, episode, ready.title)
        } else {
            ready.title
        }
    }

    private fun TimeRange.toSkipRange() = SkipRange(startSeconds = start, endSeconds = end)

    companion object {
        private const val TAG = "ZidooHandoff"

        @Volatile
        private var current: ZidooHandoffCoordinator? = null

        /**
         * True while a hand-off is under way anywhere in the app. Lets Silo
         * code outside the fork (the profile-away tracker) ask without a
         * dependency-injection edit, which keeps upstream merges clean. False
         * when no coordinator has been created, since then nothing is playing.
         */
        fun isHandingOffNow(): Boolean = current?.isHandingOff() == true

        fun plexToZidooIntent(request: PlexToZidooLaunch.Request): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(PlexToZidooLaunch.buildUri(request)), "video/*")
                setClassName(PlexToZidooLaunch.PACKAGE_NAME, PlexToZidooLaunch.ACTIVITY_CLASS)
            }

        const val NO_PATH_MESSAGE =
            "Your Silo server didn't share where this file is stored, so PlexToZidoo can't find it. " +
                "Sign in with an account that can see file paths (an admin, or one with the " +
                "Metadata curation permission)."

        const val NOT_INSTALLED_MESSAGE =
            "PlexToZidoo isn't installed on this player. Install it from " +
                "github.com/bowlingbeeg/PlexToZidoo/releases, or turn off External Player in Settings."

        const val LAUNCH_REFUSED_MESSAGE =
            "Android wouldn't open PlexToZidoo. Open PlexToZidoo once by itself, then try again."

        const val PLAYER_DID_NOT_START_MESSAGE =
            "The Zidoo player didn't start. Open PlexToZidoo, turn on its debug screen, and check that its " +
                "path substitution turns your server path into a working SMB or NFS location."
    }
}
