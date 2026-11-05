package org.siloserver.silo.tv.ui.screens.player.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.ShuffleNextPick
import org.siloserver.silo.playback.ShufflePlayback
import org.siloserver.silo.playback.nextEpisodeAfter
import org.siloserver.silo.repository.CatalogRepository
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
import org.siloserver.silo.common.player.external.ZidooEndWatch
import org.siloserver.silo.common.player.external.ZidooHandoffLog
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
import org.siloserver.silo.domain.player.BufferingQualityMode
import org.siloserver.silo.domain.player.MarkerKind
import org.siloserver.silo.domain.player.MarkerTimeline
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.network.httpOrigin
import org.siloserver.silo.network.localroute.LocalRouteHeaders
import org.siloserver.silo.network.localroute.LocalRoutes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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

    /**
     * [upNext] is what to offer next, when the title played to its end and has
     * one: the next episode, or a shuffle's next random pick.
     */
    data class Finished(override val runId: Long, val upNext: UpNextEpisode? = null) : ZidooHandoffState

    /** What plays after the title that just finished, as Silo's own Up Next names it. */
    data class UpNextEpisode(
        val contentId: String,
        val label: String,
        /** Set when a running shuffle picked it rather than the series order. */
        val shuffle: UpNextShuffle? = null,
    )

    /** A shuffle's Up Next, as Silo's own player shows it ("Up Next at Random"). */
    data class UpNextShuffle(
        /** What the shuffle plays from, such as "Movies" or "Breaking Bad · Season 2". */
        val scopeLabel: String?,
        /** True while Pick Another or Play Now waits for the server. */
        val busy: Boolean = false,
        /** Why the last Pick Another or Play Now didn't work. */
        val message: String? = null,
    )

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
        /** What Silo's relay saw, when the stream went through it. */
        val relayReport: String? = null,
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
 * 2. Open PlexToZidoo, which starts the Zidoo player at the resume point on
 *    Silo's stream (through [ZidooStreamRelay]), or on the user's SMB or NFS
 *    share when one of PlexToZidoo's path substitutions matches the file.
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
    /** Looks up the next episode for Up Next. */
    private val catalogRepository: CatalogRepository,
    /**
     * The capabilities the session is planned for: the Zidoo player's rather
     * than Silo's own, so the server sends the file untouched. A failure plans
     * for Silo's player, as the starter normally does.
     */
    private val zidooStartParams: suspend (VideoPlaybackStartRequest) -> StartParams,
    /**
     * Told whenever [isHandingOff] may have changed: the Zidoo player paused or
     * resumed, or a hand-off ended. The TV app forwards it to the profile
     * tracker, as the phone does for Cast. Called on the coordinator's scope.
     */
    private val onPlaybackActivityChanged: () -> Unit,
    /** Carries the relay's requests to the server: Silo's own video player's pooled client. */
    private val relayClient: OkHttpClient,
    /** Settings → External Player → Hand-off Log: what each step of the latest hand-off did. */
    private val handoffLog: ZidooHandoffLog,
    /** The running shuffle [shuffleId], for a title that is one of its picks. */
    private val shufflePlayback: (shuffleId: String) -> ShufflePlayback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** True while a run is asking the server for its playback session; see bufferingQualityModeNow. */
    @Volatile
    private var startingSession = false

    /** The run whose screen went behind PlexToZidoo or the Zidoo player. */
    @Volatile
    private var hiddenRunId = 0L

    /** The run whose screen came back before the Zidoo player reported a video. */
    private val returnedBeforePlayback = MutableStateFlow(0L)

    /** The shuffle behind a finished run's Up Next, and the pick that just played. */
    private data class FinishedShuffle(val runId: Long, val shuffle: ShufflePlayback, val playedContentId: String)

    private var finishedShuffle: FinishedShuffle? = null

    /** Pick Another or Play Now, while it waits for the server. */
    private var shuffleJob: Job? = null
    private var shuffleAdvancing = false

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
     *
     * [shuffleId] names the running shuffle the title is a pick of; Up Next
     * then offers the shuffle's next random pick instead of the next episode.
     */
    fun start(request: VideoPlaybackStartRequest, shuffleId: String? = null, launch: (Intent) -> Unit): Long {
        val previous = job
        val runId = ++lastRunId
        _state.value = ZidooHandoffState.Preparing(runId)
        finishedShuffle = null
        shuffleJob?.cancel()
        shuffleAdvancing = false
        val launcher = AtomicReference(launch)
        job = scope.launch {
            // Let an earlier run finish closing its session first, so that
            // cleanup cannot land on top of this run's session.
            previous?.cancelAndJoin()
            run(runId, request, shuffleId, launcher)
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
        if (current.runId == runId && current.isActive()) handoffLog.add("Silo went behind another app")
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
        if (current.runId == runId && current.isActive()) handoffLog.add("Silo is in front again")
        if (current is ZidooHandoffState.Launched && current.runId == runId && hiddenRunId == runId) {
            returnedBeforePlayback.value = runId
        }
    }

    /**
     * Up Next "Pick Another": the shuffle replaces its next pick, which the
     * finished [runId]'s Up Next then shows. With nothing else to pick, Up
     * Next closes.
     */
    fun pickAnotherShuffle(runId: Long) {
        val finished = finishedShuffle?.takeIf { it.runId == runId } ?: return
        if (shuffleJob?.isActive == true) return
        markShuffleBusy(runId)
        shuffleJob = scope.launch {
            val result = finished.shuffle.pickAnother()
            val failed = result !is ApiResult.Success && !finished.shuffle.exhausted
            updateShuffleUpNext(runId, finished, if (failed) SHUFFLE_PICK_FAILED else null)
        }
    }

    /**
     * Up Next "Play Now", or its countdown ending: moves the shuffle past the
     * pick that played and passes [play] the one to play next, from the
     * beginning. The server moves on only once, so a retry plays the same pick.
     */
    fun playNextShufflePick(runId: Long, play: (contentId: String) -> Unit) {
        val finished = finishedShuffle?.takeIf { it.runId == runId } ?: return
        if (shuffleAdvancing) return
        shuffleAdvancing = true
        // A Pick Another still in flight decides what plays; wait for it.
        val pending = shuffleJob
        markShuffleBusy(runId)
        shuffleJob = scope.launch {
            try {
                pending?.join()
                markShuffleBusy(runId)
                val result = finished.shuffle.advance(finished.playedContentId)
                val target = (result as? ApiResult.Success)?.data?.current?.contentId
                if (target == null) {
                    val message = SHUFFLE_CONTINUE_FAILED.takeUnless { finished.shuffle.exhausted }
                    updateShuffleUpNext(runId, finished, message)
                    return@launch
                }
                if (_state.value.runId != runId) return@launch
                handoffLog.add("Shuffle moved on")
                play(target)
            } finally {
                shuffleAdvancing = false
            }
        }
    }

    /** Up Next "Stop Shuffling": ends the shuffle on the server. */
    fun stopShuffle(runId: Long) {
        val finished = finishedShuffle?.takeIf { it.runId == runId } ?: return
        finishedShuffle = null
        shuffleJob?.cancel()
        finished.shuffle.stopInBackground()
    }

    /** Shows the shuffle's Up Next as waiting for the server. */
    private fun markShuffleBusy(runId: Long) {
        val finished = _state.value as? ZidooHandoffState.Finished ?: return
        val upNext = finished.upNext ?: return
        val shuffle = upNext.shuffle ?: return
        if (finished.runId != runId || shuffle.busy) return
        _state.value = finished.copy(upNext = upNext.copy(shuffle = shuffle.copy(busy = true, message = null)))
    }

    /** Shows the shuffle's current next pick, or closes Up Next when there is none. */
    private fun updateShuffleUpNext(runId: Long, finished: FinishedShuffle, message: String?) {
        val state = _state.value as? ZidooHandoffState.Finished ?: return
        if (state.runId != runId) return
        val shown = state.upNext ?: return
        val upNext = if (message != null) {
            shown.copy(shuffle = shown.shuffle?.copy(busy = false, message = message))
        } else {
            finished.shuffle.nextPickAfter(finished.playedContentId)?.toUpNext(finished.shuffle.scopeLabel)
        }
        _state.value = state.copy(upNext = upNext)
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
        shuffleId: String?,
        launcher: AtomicReference<((Intent) -> Unit)?>,
    ) {
        var sessionId: String? = null
        var sessionClosed = false
        var launched = false
        var relay: ZidooStreamRelay? = null
        var nextEpisode: Deferred<ZidooHandoffState.UpNextEpisode?>? = null
        try {
            handoffLog.startRun("Hand-off started (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
            // A shuffle replaces the series order: Up Next offers its next pick.
            val shuffle = shuffleId?.takeIf { it.isNotBlank() }?.let(shufflePlayback)
            if (shuffle != null) handoffLog.add("Shuffle pick")
            // Checked first, so a box without PlexToZidoo never opens a server session.
            if (!isPlexToZidooInstalled(appContext)) return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)

            startingSession = true
            val started = try {
                starter.start(withZidooCapabilities(request))
            } finally {
                startingSession = false
            }
            val ready = when (val result = started) {
                is VideoPlaybackStartResult.Ready -> result
                is VideoPlaybackStartResult.Error -> return fail(runId, result.message, canPlayInSilo = true)
                is VideoPlaybackStartResult.ServerUnreachable ->
                    return fail(runId, "Silo can't reach your server right now.", canPlayInSilo = false)
            }
            handoffLog.add(
                "Session ready: ${displayTitle(ready)}, ${ready.playMethod}/${ready.delivery ?: "-"}, " +
                    "file ${ready.fileId ?: ready.mediaFileId}, start ${ready.sourceStartPositionSeconds.toLong()} s",
            )
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
            // signs each request with the current token. The direct link
            // carries the token in its address, as Plex's links do: for when
            // Settings asks for it, or a box where the relay cannot start.
            relay = upstream?.takeUnless { settings.directStreamLink.value }?.let { startRelay(it, path, ready) }
            val directToken = sessionManager.getAccessToken()?.takeIf { it.isNotBlank() } ?: ready.accessToken
            val directLink = upstream?.takeIf { directToken.isNotBlank() }
                ?.let { PlexToZidooLaunch.withQueryParameter(it, "token", directToken) }
            val streamUrl = relay?.url ?: directLink
            Log.i(TAG, if (relay != null) "Streaming through the relay" else "Streaming by direct link")
            handoffLog.add(
                when {
                    relay != null -> "Stream: through Silo's relay, from ${upstream?.let { originOf(it) }}"
                    directLink != null -> "Stream: direct link to ${upstream?.let { originOf(it) }}"
                    else -> "Stream: none, the share path only"
                },
            )

            // Looked up while the episode plays, so Up Next is ready the
            // moment the Zidoo player closes. A shuffle asks its server when
            // the title ends instead, as Silo's own player does.
            if (shuffle == null) nextEpisode = scope.async { findNextEpisode(ready, request.libraryId) }

            val subtitleTracks = version?.subtitleTracks.orEmpty()
            val title = displayTitle(ready)
            val startPositionMs = (ready.sourceStartPositionSeconds.coerceAtLeast(0.0) * 1000).toLong()
            val intent = plexToZidooIntent(
                PlexToZidooLaunch.Request(
                    serverFilePath = path,
                    title = title,
                    startPositionMs = startPositionMs,
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
            ZidooHandoffService.start(appContext)?.let { handoffLog.add("Keep-alive service didn't start: $it") }
            // Launched before PlexToZidoo opens, so the screen's pause that the
            // launch causes is already seen as leaving for the Zidoo player.
            setState(runId, ZidooHandoffState.Launched(runId, title))
            zidooPaused = false
            try {
                openPlexToZidoo(intent)
                handoffLog.add("PlexToZidoo opened")
            } catch (_: ActivityNotFoundException) {
                return fail(runId, NOT_INSTALLED_MESSAGE, canPlayInSilo = true)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Android refused to open PlexToZidoo", e)
                return fail(runId, LAUNCH_REFUSED_MESSAGE, canPlayInSilo = true)
            }
            launched = true

            // Every intro and credits occurrence, read the way Silo's own player reads them.
            val markers = MarkerTimeline.resolve(
                ready.markerSegments,
                intro = ready.intro,
                credits = ready.credits,
                recap = ready.recap,
                preview = ready.preview,
            )
            val monitor = ZidooPlaybackMonitor(
                api = zidoo,
                startPositionMs = startPositionMs,
                startupTimeoutMs = PLAYER_START_TIMEOUT_MS,
                planner = ZidooSkipPlanner(
                    intros = markers.ranges(MarkerKind.Intro).map { it.toSkipRange() },
                    credits = markers.ranges(MarkerKind.Credits).map { it.toSkipRange() },
                    settings = settings.skipSettings(),
                ),
            )
            // Whether the episode plays to its end, for Up Next: past the last credits range, as in Silo.
            val endWatch = ZidooEndWatch(creditsStartSeconds = markers.upNextCreditsStart())
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
                    handoffLog.add("The server ended the session; stopped the Zidoo player")
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
                        onStarted = {
                            handoffLog.add("Zidoo player reports the video")
                            setState(runId, ZidooHandoffState.Playing(runId, title))
                        },
                        onSample = { sample ->
                            endWatch.onSample(sample.positionMs, sample.durationMs)
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
            if (outcome == ZidooMonitorOutcome.PlayerDidNotStart) {
                handoffLog.add("Zidoo player never reported the video" + (relay?.let { ". Relay: ${it.report()}" } ?: ""))
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
                        relayReport = relay?.report(),
                    ),
                )
                is ZidooMonitorOutcome.Finished -> {
                    handoffLog.add("Zidoo player closed at ${outcome.lastPositionMs / 1000} s of ${outcome.durationMs / 1000} s")
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
                    val lookup = nextEpisode
                    val upNext = when {
                        !endWatch.reachedEnd(outcome.lastPositionMs, outcome.durationMs) -> null
                        shuffle != null -> withTimeoutOrNull(UP_NEXT_LOOKUP_WAIT_MS) {
                            shuffleUpNext(shuffle, request.contentId)
                        }
                        lookup != null -> withTimeoutOrNull(UP_NEXT_LOOKUP_WAIT_MS) { lookup.await() }
                        else -> null
                    }
                    if (shuffle != null && upNext != null) {
                        finishedShuffle = FinishedShuffle(runId, shuffle, request.contentId)
                    }
                    setState(runId, ZidooHandoffState.Finished(runId, upNext))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Zidoo hand-off failed", e)
            handoffLog.add("Hand-off failed: $e")
            fail(runId, "Something went wrong handing playback to the Zidoo player.", canPlayInSilo = true)
        } finally {
            relay?.close()
            nextEpisode?.cancel()
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
                    delay(END_NOTICE_DELAY_MS)
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
    ) {
        handoffLog.add("Failed: $message")
        setState(runId, ZidooHandoffState.Failed(runId, message, canPlayInSilo, notPlayed))
    }

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

    /**
     * The episode after [ready]'s, from the current season and the next regular
     * one, exactly as Silo's own Up Next finds it. Null for a film, at the end
     * of a series, or when the catalog can't be read.
     */
    private suspend fun findNextEpisode(
        ready: VideoPlaybackStartResult.Ready,
        libraryId: Int?,
    ): ZidooHandoffState.UpNextEpisode? {
        val seriesId = ready.seriesId ?: return null
        val season = ready.seasonNumber ?: return null
        val episode = ready.episodeNumber ?: return null
        return try {
            val pool = (catalogRepository.getEpisodes(seriesId, season, libraryId = libraryId) as? ApiResult.Success)
                ?.data?.episodes?.toMutableList() ?: return null
            val nextSeason = (catalogRepository.getSeasons(seriesId, libraryId = libraryId) as? ApiResult.Success)
                ?.data?.seasons
                ?.filter { !it.isSpecials && it.seasonNumber > season }
                ?.minByOrNull { it.seasonNumber }
            if (nextSeason != null) {
                (catalogRepository.getEpisodes(seriesId, nextSeason.seasonNumber, libraryId = libraryId) as? ApiResult.Success)
                    ?.data?.episodes?.let { pool += it }
            }
            val next = nextEpisodeAfter(pool, season, episode) ?: return null
            val name = next.title?.takeIf { it.isNotBlank() }
            ZidooHandoffState.UpNextEpisode(
                contentId = next.contentId,
                label = "S%02dE%02d".format(next.seasonNumber, next.episodeNumber) + (name?.let { " · $it" } ?: ""),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up the next episode", e)
            null
        }
    }

    /**
     * The shuffle's pick after [playedContentId], read fresh: the server
     * replaces a next pick that can no longer play. Null when nothing else in
     * the shuffle can play.
     */
    private suspend fun shuffleUpNext(shuffle: ShufflePlayback, playedContentId: String): ZidooHandoffState.UpNextEpisode? {
        shuffle.refresh()
        return shuffle.nextPickAfter(playedContentId)?.toUpNext(shuffle.scopeLabel)
    }

    /** Starts the relay for [upstream]; null, and a log line, when this box will not let it listen. */
    private fun startRelay(upstream: String, path: String?, ready: VideoPlaybackStartResult.Ready): ZidooStreamRelay? =
        try {
            ZidooStreamRelay(
                upstreamUrl = upstream,
                fileName = ZidooStreamRelay.fileNameFor(path, ready.container),
                accessToken = {
                    // Read on each request, so a token the app renewed during
                    // the film is used as soon as it exists.
                    runBlocking { sessionManager.getAccessToken() } ?: ready.accessToken
                },
                client = relayClient,
                upstreamHeaders = localRouteMarker(upstream, ready),
                log = { handoffLog.add("Relay: $it") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the stream relay; giving the Zidoo player a direct link", e)
            null
        }

    /**
     * Silo's local-route marker for [upstream], as its own player sets it for
     * a stream: while the box is on the server's local network and the stream
     * is on the saved address, the relay then fetches it from the server's
     * local address. The hand-off's session was admitted with the local route
     * allowed, so the server already counts it as local.
     */
    private fun localRouteMarker(upstream: String, ready: VideoPlaybackStartResult.Ready): Map<String, String> {
        val route = LocalRoutes.state.active.value ?: return emptyMap()
        val onSavedAddress = httpOrigin(ready.serverUrl) == route.savedOrigin && httpOrigin(upstream) == route.savedOrigin
        val streamPath = upstream.toHttpUrlOrNull()?.encodedPath.orEmpty()
        if (!onSavedAddress || !streamPath.startsWith("/api/")) return emptyMap()
        return mapOf(LocalRouteHeaders.MEDIA to route.generation.toString())
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

    /** A shuffle pick as Up Next names it: "Series · S01E02 · Title" for an episode, the title for a film. */
    private fun ShuffleNextPick.toUpNext(scopeLabel: String?): ZidooHandoffState.UpNextEpisode {
        val label = if (isEpisode) {
            listOfNotNull(
                seriesTitle?.takeIf { it.isNotBlank() },
                "S%02dE%02d".format(seasonNumber, episodeNumber),
                title.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        } else {
            title
        }
        return ZidooHandoffState.UpNextEpisode(contentId, label, ZidooHandoffState.UpNextShuffle(scopeLabel))
    }

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

        /**
         * The buffering-quality mode Silo's session manager plans a start
         * with. While a hand-off asks for its session it is NEVER, so the
         * request carries `learned_limit: ignore`: a bandwidth limit the
         * server learned from Silo's own player buffering on this box would
         * otherwise plan a transcode, which the Zidoo player cannot be given.
         * Every other start keeps the default, as in upstream Silo's TV app.
         */
        fun bufferingQualityModeNow(): BufferingQualityMode =
            if (current?.startingSession == true) BufferingQualityMode.NEVER else BufferingQualityMode.Default

        /** The intent that opens PlexToZidoo for [request]; null when it has neither a path nor a stream. */
        private fun plexToZidooIntent(request: PlexToZidooLaunch.Request): Intent? {
            val uri = PlexToZidooLaunch.buildUri(request) ?: return null
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/*")
                setClassName(PlexToZidooLaunch.PACKAGE_NAME, PlexToZidooLaunch.ACTIVITY_CLASS)
            }
        }

        private const val NO_PATH_MESSAGE =
            "Your Silo server would convert this file for this player rather than send the original, and " +
                "it didn't share where the file is stored, so PlexToZidoo has nothing it can open. Sign in " +
                "with an account that can see file paths (an admin, or one with the Metadata curation " +
                "permission) and add a path substitution in PlexToZidoo."

        private const val NOT_INSTALLED_MESSAGE =
            "PlexToZidoo isn't installed on this player. Install it from " +
                "github.com/bowlingbeeg/PlexToZidoo/releases, or turn off External Player in Settings."

        private const val LAUNCH_REFUSED_MESSAGE =
            "Android wouldn't open PlexToZidoo. Open PlexToZidoo once by itself, then try again."

        /** The screen adds what PlexToZidoo was given and what to check. */
        private const val PLAYER_DID_NOT_START_MESSAGE = "The Zidoo player didn't play this file."

        private const val UNTRACKED_PLAYBACK_MESSAGE =
            "The Zidoo player played this, but Silo couldn't read its position, so it didn't save your place " +
                "or skip markers. Silo reads it from the Zidoo player's control interface, which needs firmware " +
                "6.4.42 or later (1.1.42 or later on the Z9X 8K line)."

        /**
         * How long Silo waits for the Zidoo player to report the video before
         * deciding it didn't play. Generous, because the wait starts before
         * PlexToZidoo opens it: PlexToZidoo's Debug data screen waits for its
         * PLAY button, and a large file from a server across the internet takes
         * a while to open. A player that closes without playing brings Silo
         * back to the front, which ends the wait within [RETURN_GRACE_MS].
         */
        private const val PLAYER_START_TIMEOUT_MS = 5 * 60_000L

        /** How long the end of a run waits for an Up Next lookup still in flight. */
        private const val UP_NEXT_LOOKUP_WAIT_MS = 5_000L

        // Silo's own player's wording (TvPlayerViewModel).
        private const val SHUFFLE_CONTINUE_FAILED = "Couldn't continue the shuffle."
        private const val SHUFFLE_PICK_FAILED = "Couldn't pick another."

        /** How recently the relay must have sent media for the player to count as fetching. */
        private const val RELAY_ACTIVE_WINDOW_MS = 20_000L

        /** How long the relay must have streamed for a run the status never confirmed to count as played. */
        private const val UNTRACKED_PLAYBACK_MIN_MS = 90_000L

        /** `scheme://host[:port]` of [url], for telling the user which server the Zidoo player had to reach. */
        private fun originOf(url: String): String {
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
        private const val RETURN_GRACE_MS = 2_000L

        /**
         * How long after a hand-off ends before onPlaybackActivityChanged hears
         * about it. Silo normally comes back to the front within a second of the
         * Zidoo player closing; waiting lets that return land first, so coming
         * back from a film is treated the same way every time.
         */
        private const val END_NOTICE_DELAY_MS = 5_000L
    }
}
