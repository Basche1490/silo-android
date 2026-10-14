package org.siloserver.silo.tv.ui.screens.player.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.resolvePlaybackStreamUrl
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.player.external.PlexToZidooLaunch
import org.siloserver.silo.common.player.external.SkipRange
import org.siloserver.silo.common.player.external.ZidooMonitorOutcome
import org.siloserver.silo.common.player.external.ZidooPlaybackMonitor
import org.siloserver.silo.common.player.external.ZidooPlayerApi
import org.siloserver.silo.common.player.external.ZidooSkipPlanner
import org.siloserver.silo.common.player.external.ZidooStreamRelay
import org.siloserver.silo.common.player.external.ZidooTrackMapping
import org.siloserver.silo.common.player.external.settledPositionMs
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery

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
        /** Set when the Zidoo player was asked to play and didn't, so the screen can say what was sent. */
        val notPlayed: NotPlayed? = null,
    ) : ZidooHandoffState

    /** What PlexToZidoo was given for a title the Zidoo player then didn't play. */
    data class NotPlayed(
        /** The file path its substitution rules were matched against, if Silo had one. */
        val serverPath: String?,
        /** Scheme, host and port of the Silo stream it plays when no rule matches, if one was sent. */
        val streamOrigin: String?,
    )
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
    /**
     * The capabilities the session is planned for: the Zidoo player's rather
     * than Silo's own, so the server sends the file untouched. Null, or a
     * failure, plans for Silo's player as the starter normally does.
     */
    private val zidooStartParams: suspend (VideoPlaybackStartRequest) -> StartParams? = { null },
    private val newRelay: (upstream: String, fileName: String, accessToken: () -> String?) -> ZidooStreamRelay =
        { upstream, fileName, accessToken -> ZidooStreamRelay(upstream, fileName, accessToken) },
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

    /** The run whose screen went behind PlexToZidoo or the Zidoo player. */
    @Volatile
    private var hiddenRunId = 0L

    /** The run whose screen came back before the Zidoo player reported a video. */
    private val returnedBeforePlayback = MutableStateFlow(0L)

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

    /**
     * Abandons [runId] if nothing is playing for it: it has not opened
     * PlexToZidoo yet, or Silo came back without the Zidoo player ever
     * reporting a video. Returns true when it did so.
     */
    fun cancelIfNotPlaying(runId: Long): Boolean {
        val current = _state.value
        if (current.runId != runId) return false
        val nothingPlaying = current is ZidooHandoffState.Preparing ||
            (current is ZidooHandoffState.Launched && returnedBeforePlayback.value == runId)
        if (!nothingPlaying) return false
        job?.cancel()
        _state.value = ZidooHandoffState.Idle
        return true
    }

    /** Tells the run that its screen went behind another app. Only a pause after PlexToZidoo opened counts. */
    fun onScreenHidden(runId: Long) {
        val current = _state.value
        if (current is ZidooHandoffState.Launched && current.runId == runId) hiddenRunId = runId
    }

    /**
     * Tells the run that its screen is in front again. Coming back after
     * PlexToZidoo opened but before the Zidoo player reported a video means
     * the player closed without playing, most often because it could not open
     * the file. The run then stops waiting for it after [RETURN_GRACE_MS]
     * instead of the monitor's full startup timeout.
     */
    fun onScreenShown(runId: Long) {
        val current = _state.value
        if (current is ZidooHandoffState.Launched && current.runId == runId && hiddenRunId == runId) {
            returnedBeforePlayback.value = runId
        }
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
        var relay: ZidooStreamRelay? = null
        try {
            // Checked first, so a box without PlexToZidoo never opens a server session.
            if (!plexToZidooInstalled()) return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)

            val ready = when (val result = starter.start(withZidooCapabilities(request))) {
                is VideoPlaybackStartResult.Ready -> result
                is VideoPlaybackStartResult.Error -> return fail(runId, result.message, canPlayInSilo = true)
                is VideoPlaybackStartResult.ServerUnreachable ->
                    return fail(runId, "Silo can't reach your server right now.", canPlayInSilo = false)
            }
            val id = ready.sessionId
                ?: return fail(runId, "The server didn't open a playback session.", canPlayInSilo = true)
            sessionId = id

            // Checked before the session is confirmed, so this failure rolls
            // the unpublished session back. Either is enough: PlexToZidoo
            // plays the stream whenever none of its rules match the path.
            val fileId = ready.fileId ?: ready.mediaFileId
            val version = ready.versions.firstOrNull { it.fileId == fileId }
            val path = version?.filePath?.takeIf { it.isNotBlank() }
            val upstream = originalStreamUpstream(ready)
            if (path == null && upstream == null) return fail(runId, NO_PATH_MESSAGE, canPlayInSilo = true)

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

            // The Zidoo player reaches the stream through the relay, which
            // signs each request with the current token; the direct link with
            // the token in it is only for a box where the relay cannot start.
            relay = upstream?.let { startRelay(it, path, ready) }
            val directLink = upstream?.takeIf { ready.accessToken.isNotBlank() }
                ?.let { PlexToZidooLaunch.withQueryParameter(it, "token", ready.accessToken) }
            val streamUrl = relay?.url ?: directLink

            val subtitleTracks = version?.subtitleTracks.orEmpty()
            val title = displayTitle(ready)
            val intent = plexToZidooIntent(
                PlexToZidooLaunch.Request(
                    serverFilePath = path,
                    title = title,
                    startPositionMs = (ready.sourceStartPositionSeconds.coerceAtLeast(0.0) * 1000).toLong(),
                    audioIndex = ZidooTrackMapping.audioIndex(
                        siloAudioOrdinal = ready.playbackPlan?.selectedTracks?.audioIndex ?: ready.audioTrackIndex,
                        audioTrackCount = version?.audioTracks.orEmpty().size,
                    ),
                    subtitleIndex = ZidooTrackMapping.subtitleIndex(
                        siloCombinedIndex = ready.playbackPlan?.selectedTracks?.subtitleIndex
                            ?: request.subtitleTrackIndex,
                        externalTrackCount = subtitleTracks.count { it.external },
                        embeddedTrackCount = subtitleTracks.count { !it.external },
                    ),
                    streamUrl = streamUrl,
                ),
            ) ?: return fail(runId, NO_PATH_MESSAGE, canPlayInSilo = true)

            val openPlexToZidoo = launcher.getAndSet(null)
            if (openPlexToZidoo == null || _state.value.runId != runId) return
            // Silo is still in the foreground here, which Android requires
            // before it allows a foreground service to start. The service
            // stops itself once this run leaves the active states.
            ZidooHandoffService.start(appContext)
            // Launched before PlexToZidoo opens, so the screen's pause that the
            // launch causes is already seen as leaving for the Zidoo player.
            setState(runId, ZidooHandoffState.Launched(runId, title))
            zidooPaused = false
            try {
                openPlexToZidoo(intent)
            } catch (_: ActivityNotFoundException) {
                return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Android refused to open PlexToZidoo", e)
                return fail(runId, LAUNCH_REFUSED_MESSAGE, canPlayInSilo = true)
            }
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
                // Silo back in front before any video loaded means the Zidoo
                // player has closed. Allow a moment in case it is still coming.
                val playerNotComing = AtomicBoolean(false)
                val returnWatch = launch {
                    returnedBeforePlayback.first { it == runId }
                    delay(RETURN_GRACE_MS)
                    playerNotComing.set(true)
                }
                try {
                    monitor.run(
                        giveUpStarting = playerNotComing::get,
                        // A player still fetching the video through the relay is
                        // starting or playing, even while its status says nothing;
                        // giving up then would stop the session under it.
                        keepWaiting = { relay?.servedWithin(RELAY_ACTIVE_WINDOW_MS) == true },
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
                    returnWatch.cancel()
                }
            }
            when (outcome) {
                ZidooMonitorOutcome.PlayerDidNotStart -> return if (relay?.servedForAtLeast(UNTRACKED_PLAYBACK_MIN_MS) == true) {
                    // It streamed for minutes, so it played; its status never
                    // answered, so Silo could not follow it.
                    fail(runId, UNTRACKED_PLAYBACK_MESSAGE, canPlayInSilo = false)
                } else fail(
                    runId,
                    PLAYER_DID_NOT_START_MESSAGE,
                    canPlayInSilo = true,
                    notPlayed = ZidooHandoffState.NotPlayed(
                        serverPath = path,
                        streamOrigin = upstream?.takeIf { streamUrl != null }?.let { originOf(it) },
                    ),
                )
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
            relay?.close()
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

    private fun fail(
        runId: Long,
        message: String,
        canPlayInSilo: Boolean,
        notPlayed: ZidooHandoffState.NotPlayed? = null,
    ) =
        setState(runId, ZidooHandoffState.Failed(runId, message, canPlayInSilo, notPlayed))

    /** [request] planned for the Zidoo player's capabilities, when they can be worked out. */
    private suspend fun withZidooCapabilities(request: VideoPlaybackStartRequest): VideoPlaybackStartRequest {
        val params = try {
            zidooStartParams(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not describe the Zidoo player; planning for Silo's player", e)
            null
        }
        return if (params != null) request.copy(recoveryStartParams = params) else request
    }

    /**
     * Silo's stream of the original file, as the plan names it (with its signed
     * `st` reference, without a sign-in). Null when the server planned anything
     * but the original file (a remux or transcode would need Silo's own
     * player), or routed it through a node that needs extra headers.
     */
    private fun originalStreamUpstream(ready: VideoPlaybackStartResult.Ready): String? {
        val original = ready.playMethod == PlayMethod.DIRECT &&
            (ready.delivery == null || ready.delivery == PlaybackDelivery.ORIGINAL_HTTP)
        if (!original || ready.requestHeaders.isNotEmpty()) return null
        val url = resolvePlaybackStreamUrl(ready.serverUrl, ready.streamUrl)
        return url.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /** Starts the relay for [upstream]; null, and a log line, when this box will not let it listen. */
    private fun startRelay(upstream: String, path: String?, ready: VideoPlaybackStartResult.Ready): ZidooStreamRelay? =
        try {
            newRelay(upstream, ZidooStreamRelay.fileNameFor(path, ready.container)) {
                // Read on each request, so a token the app renewed during the
                // film is used as soon as it exists.
                runBlocking { sessionManager.getAccessToken() } ?: ready.accessToken
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the stream relay; giving the Zidoo player a direct link", e)
            null
        }

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

        /** The intent that opens PlexToZidoo for [request]; null when it has neither a path nor a stream. */
        fun plexToZidooIntent(request: PlexToZidooLaunch.Request): Intent? {
            val uri = PlexToZidooLaunch.buildUri(request) ?: return null
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/*")
                setClassName(PlexToZidooLaunch.PACKAGE_NAME, PlexToZidooLaunch.ACTIVITY_CLASS)
            }
        }

        const val NO_PATH_MESSAGE =
            "Your Silo server would convert this file for this player rather than send the original, and " +
                "it didn't share where the file is stored, so PlexToZidoo has nothing it can open. Sign in " +
                "with an account that can see file paths (an admin, or one with the Metadata curation " +
                "permission) and add a path substitution in PlexToZidoo."

        const val NOT_INSTALLED_MESSAGE =
            "PlexToZidoo isn't installed on this player. Install it from " +
                "github.com/bowlingbeeg/PlexToZidoo/releases, or turn off External Player in Settings."

        const val LAUNCH_REFUSED_MESSAGE =
            "Android wouldn't open PlexToZidoo. Open PlexToZidoo once by itself, then try again."

        /** The screen adds what PlexToZidoo was given and what to check. */
        const val PLAYER_DID_NOT_START_MESSAGE = "The Zidoo player didn't play this file."

        const val UNTRACKED_PLAYBACK_MESSAGE =
            "The Zidoo player played this, but Silo couldn't read its position, so it didn't save your place " +
                "or skip markers. Silo reads it from the Zidoo player's control interface, which needs firmware " +
                "6.4.42 or later (1.1.42 or later on the Z9X 8K line)."

        /** How recently the relay must have sent media for the player to count as fetching. */
        private const val RELAY_ACTIVE_WINDOW_MS = 20_000L

        /** How long the relay must have streamed for a run the status never confirmed to count as played. */
        private const val UNTRACKED_PLAYBACK_MIN_MS = 90_000L

        /** `scheme://host[:port]` of [url], for telling the user which server the Zidoo player had to reach. */
        internal fun originOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val pathStart = url.indexOfAny(charArrayOf('/', '?', '#'), startIndex = schemeEnd + 3)
            return if (pathStart < 0) url else url.substring(0, pathStart)
        }

        /**
         * How long a run keeps waiting for the Zidoo player after Silo is back
         * in front. The player is closed by then; this only absorbs a slow
         * status reading.
         */
        const val RETURN_GRACE_MS = 2_000L
    }
}
