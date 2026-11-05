package org.siloserver.silo.tv.ui.screens.player.external

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.tv.ui.components.TvDialogAction
import org.siloserver.silo.tv.ui.components.TvDialogActionStyle
import org.siloserver.silo.tv.ui.components.TvDialogCard
import org.siloserver.silo.tv.ui.components.TvDialogDefaults
import org.siloserver.silo.tv.ui.screens.player.TvPlayerChrome
import org.siloserver.silo.tv.ui.screens.player.TvPlayerType

/**
 * Stands in for the built-in player while the Zidoo player plays the title.
 *
 * Most of the time it sits behind the Zidoo player and is only seen for a
 * moment before and after playback. Leaving it never stops a playback that is
 * already running: progress keeps being saved until the Zidoo player closes.
 */
@Composable
fun TvExternalPlayerScreen(
    request: VideoPlaybackStartRequest,
    /** The running shuffle [request] is a pick of, or null. */
    shuffleId: String?,
    onExit: () -> Unit,
    onPlayInSilo: () -> Unit,
    autoAdvanceCount: Int,
    onPlayNext: (contentId: String, autoAdvanceCount: Int, shuffleId: String?) -> Unit,
    coordinator: ZidooHandoffCoordinator = koinInject(),
    playerSettings: PlayerSettingsStore = koinInject(),
    externalSettings: ExternalPlayerSettings = koinInject(),
) {
    val context = LocalContext.current
    val state by coordinator.state.collectAsState()
    // Saved, so a screen restored after Silo was killed knows it already handed
    // off and does not open the Zidoo player a second time.
    var runId by rememberSaveable { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        if (runId == 0L) {
            // The composition's context is the Activity, so PlexToZidoo opens
            // in Silo's task and closing the Zidoo player returns here.
            runId = coordinator.start(request, shuffleId) { intent -> context.startActivity(intent) }
        } else if (coordinator.state.value.runId != runId) {
            onExit()
        }
    }

    LaunchedEffect(state, runId) {
        val current = state
        if (runId != 0L && current is ZidooHandoffState.Finished && current.runId == runId && current.upNext == null) {
            coordinator.acknowledge(runId)
            onExit()
        }
    }

    // Tell the run when this screen goes behind PlexToZidoo and comes back. A
    // return before the Zidoo player reported a video means it closed without
    // playing, so the run reports that within seconds instead of waiting.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Whether this screen is in front, and whether it has been long enough to
    // show the Playing card; see PLAYING_CARD_DELAY_MS. Both are set from the
    // lifecycle callbacks themselves, so the first frame after Silo comes back
    // already has them right.
    var inFront by remember { mutableStateOf(false) }
    var playingCardReady by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, runId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    inFront = false
                    playingCardReady = false
                }
                Lifecycle.Event.ON_RESUME -> inFront = true
                else -> Unit
            }
            if (runId == 0L) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> coordinator.onScreenHidden(runId)
                Lifecycle.Event.ON_RESUME -> coordinator.onScreenShown(runId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Closing the Zidoo player brings Silo back a moment before the run's next
    // status check sees that playback ended, so for that moment the run still
    // says Playing. Show the Playing card only once Silo has stayed in front
    // with the Zidoo player still playing, not as a flash on every stop.
    val playing = state.runId == runId && state is ZidooHandoffState.Playing
    LaunchedEffect(playing, inFront) {
        playingCardReady = false
        if (!playing || !inFront) return@LaunchedEffect
        delay(PLAYING_CARD_DELAY_MS)
        if (inFront) playingCardReady = true
    }

    BackHandler {
        coordinator.cancelIfNotPlaying(runId)
        coordinator.acknowledge(runId)
        onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvPlayerChrome.Ink),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state.takeIf { it.runId == runId } ?: ZidooHandoffState.Preparing(runId)) {
            is ZidooHandoffState.Failed -> FailedCard(
                failure = current,
                // The composition's context is the Activity, so PlexToZidoo's
                // settings open in Silo's task and Back returns to this card.
                onOpenPlexToZidoo = { openPlexToZidooSettings(context) },
                onPlayInSilo = {
                    coordinator.acknowledge(runId)
                    onPlayInSilo()
                },
                onBack = {
                    coordinator.acknowledge(runId)
                    onExit()
                },
            )

            is ZidooHandoffState.Finished -> current.upNext?.let { next ->
                UpNextCard(
                    next = next,
                    autoAdvanceCount = autoAdvanceCount,
                    playerSettings = playerSettings,
                    externalSettings = externalSettings,
                    onPlay = { automatic ->
                        // Play Now is someone watching: it resets the
                        // "still watching?" count, as in Silo's own player.
                        val count = if (automatic) autoAdvanceCount + 1 else 0
                        if (next.shuffle != null) {
                            // The server moves the shuffle on and names what plays.
                            coordinator.playNextShufflePick(runId) { target ->
                                coordinator.acknowledge(runId)
                                onPlayNext(target, count, shuffleId)
                            }
                        } else {
                            coordinator.acknowledge(runId)
                            onPlayNext(next.contentId, count, null)
                        }
                    },
                    onPickAnother = { coordinator.pickAnotherShuffle(runId) },
                    onStopShuffling = {
                        coordinator.stopShuffle(runId)
                        coordinator.acknowledge(runId)
                        onExit()
                    },
                    onBack = {
                        coordinator.acknowledge(runId)
                        onExit()
                    },
                )
            } ?: Progress(title = null, status = "Getting ready…")

            is ZidooHandoffState.Playing -> if (playingCardReady) {
                TvDialogCard(
                    title = current.title,
                    message = "Playing in the Zidoo player. Silo saves your place and skips markers until it closes.",
                    actions = listOf(
                        TvDialogAction(label = "Back", onClick = onExit),
                        TvDialogAction(
                            label = "Stop playback",
                            onClick = coordinator::stopZidooPlayer,
                            style = TvDialogActionStyle.Destructive,
                        ),
                    ),
                )
            } else {
                Progress(current.title, "Checking the Zidoo player…")
            }

            is ZidooHandoffState.Launched -> Progress(current.title, "Opening the Zidoo player…")
            is ZidooHandoffState.Saving -> Progress(current.title, "Saving your progress…")
            is ZidooHandoffState.Preparing,
            ZidooHandoffState.Idle,
            -> Progress(title = null, status = "Getting ready…")
        }
    }
}

/**
 * How long Silo stays in front, with the Zidoo player still reported as
 * playing, before the Playing card (Back, Stop playback) appears. Longer than
 * the run takes to notice a closed player: one status check a second, and up
 * to three missed ones (ZidooPlaybackMonitor).
 */
private const val PLAYING_CARD_DELAY_MS = 5_000L

/**
 * Up Next after an episode the Zidoo player finished, as Silo's own player
 * offers it: with Auto-Play Next Episode on, the next episode opens after the
 * countdown chosen in Settings → External Player (10 seconds unless changed);
 * with it off, or once the Still Watching Prompt's limit is reached, it waits
 * for Play Now.
 *
 * In a shuffle it is "Up Next at Random": the next random pick, movie or
 * episode, with Pick Another and Stop Shuffling as in Silo's own player. A
 * new pick starts the countdown again.
 */
@Composable
private fun UpNextCard(
    next: ZidooHandoffState.UpNextEpisode,
    autoAdvanceCount: Int,
    playerSettings: PlayerSettingsStore,
    externalSettings: ExternalPlayerSettings,
    onPlay: (automatic: Boolean) -> Unit,
    onPickAnother: () -> Unit,
    onStopShuffling: () -> Unit,
    onBack: () -> Unit,
) {
    val shuffle = next.shuffle
    val autoPlay by playerSettings.autoPlayNextFlow.collectAsState(initial = null)
    val passOutThreshold by playerSettings.passOutThresholdFlow.collectAsState(initial = null)
    val countdownSeconds by externalSettings.upNextCountdownSeconds.collectAsState()
    val threshold = passOutThreshold
    // Threshold 0 is off. Both settings are read before anything counts down.
    val stillWatching = threshold != null && threshold > 0 && autoAdvanceCount >= threshold
    val countdownOn = autoPlay == true && threshold != null && !stillWatching
    // The length chosen in Settings, fixed for this card once it shows.
    val total = remember(next.contentId) { countdownSeconds }
    var remaining by remember(next.contentId) { mutableIntStateOf(total) }
    LaunchedEffect(next.contentId, countdownOn) {
        if (!countdownOn) return@LaunchedEffect
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
        onPlay(true)
    }
    TvDialogCard(
        title = if (shuffle != null) "Up Next at Random" else "Up Next",
        message = listOfNotNull(
            next.label,
            shuffle?.scopeLabel?.takeIf { it.isNotBlank() }?.let { "Shuffling $it" },
            "Still watching? Choose Play Now to keep going.".takeIf { autoPlay == true && stillWatching },
            shuffle?.message ?: "Asking your server…".takeIf { shuffle?.busy == true },
        ).joinToString("\n"),
        actions = buildList {
            add(TvDialogAction("Play Now", { onPlay(false) }, TvDialogActionStyle.Primary))
            if (shuffle != null) add(TvDialogAction("Pick Another", onPickAnother))
            add(TvDialogAction("Back", onBack))
            if (shuffle != null) add(TvDialogAction("Stop Shuffling", onStopShuffling))
        },
        initialFocusIndex = 0,
        width = if (shuffle != null) 640.dp else TvDialogDefaults.Width,
        content = if (countdownOn && total > 0) {
            { CountdownRing(seconds = remaining, totalSeconds = total) }
        } else {
            null
        },
    )
}

/**
 * The countdown as Silo's own Up Next shows it (TvPlayerScreen's
 * TvCountdownRing): a ring that drains as the seconds run out, with the
 * seconds left in the middle.
 */
@Composable
private fun CountdownRing(seconds: Int, totalSeconds: Int) {
    val progress = (seconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color = Color.White.copy(alpha = 0.12f), style = stroke)
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = stroke,
            )
        }
        Text(text = "$seconds", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FailedCard(
    failure: ZidooHandoffState.Failed,
    onOpenPlexToZidoo: () -> Unit,
    onPlayInSilo: () -> Unit,
    onBack: () -> Unit,
) {
    val notPlayed = failure.notPlayed
    val actions = buildList {
        if (notPlayed != null) {
            add(TvDialogAction("Open PlexToZidoo", onOpenPlexToZidoo, TvDialogActionStyle.Primary))
        }
        if (failure.canPlayInSilo) {
            val style = if (notPlayed == null) TvDialogActionStyle.Primary else TvDialogActionStyle.Default
            add(TvDialogAction("Play in Silo", onPlayInSilo, style))
        }
        add(TvDialogAction("Back", onBack))
    }
    if (notPlayed == null) {
        TvDialogCard(
            title = "Couldn't play in the Zidoo player",
            message = failure.message,
            actions = actions,
            initialFocusIndex = 0,
        )
        return
    }
    // The Zidoo player was asked to play and didn't. Say exactly what
    // PlexToZidoo was given, because its substitution rules have to match the
    // path, and otherwise the Zidoo player had to open Silo's stream.
    TvDialogCard(
        title = "Couldn't play in the Zidoo player",
        message = failure.message,
        actions = actions,
        initialFocusIndex = 0,
        width = 640.dp,
    ) {
        notPlayed.serverPath?.let { path ->
            Text(
                text = path,
                style = TvPlayerType.Row.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvPlayerChrome.Chip, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(text = notPlayedHelp(notPlayed), style = TvPlayerType.Body)
        // Where the chain broke, when the stream went through Silo's relay.
        notPlayed.relayReport?.let { report ->
            Text(text = report, style = TvPlayerType.RowDetail)
        }
    }
}

/** What to check, given what PlexToZidoo was sent, in PlexToZidoo's own setting names. */
private fun notPlayedHelp(notPlayed: ZidooHandoffState.NotPlayed): String {
    val path = notPlayed.serverPath
    val origin = notPlayed.streamOrigin
    return when {
        path != null && origin != null ->
            "PlexToZidoo opens the file from your network share when one of its path substitutions " +
                "matches the start of this path; check that rule's share name and SMB username and " +
                "password. With no matching rule, the Zidoo player streams it from Silo at $origin, and " +
                "adding a rule for this path is the way around a stream it can't open."
        path != null ->
            "Your Silo server would convert this file for this player rather than send the original, so " +
                "PlexToZidoo has to open it from your network share. " + substitutionHelp(path)
        origin != null -> "The Zidoo player couldn't open Silo's stream from $origin."
        else -> ""
    }
}

/** What to enter in PlexToZidoo for [serverPath], using its own setting names. */
private fun substitutionHelp(serverPath: String): String {
    val prefix = examplePrefix(serverPath)
    val start = if (prefix != null) "the start of that path, such as $prefix" else "the start of that path"
    return "In PlexToZidoo, under Substitutions, set \u201cPart of the path to replace\u201d to $start, and " +
        "\u201cReplaced with\u201d to the same folder on your network share, such as smb://192.168.1.20/video. " +
        "If a rule is already there, check the share name and the SMB username and password."
}

/** The first two folders of [serverPath] (for example `/mnt/media`), or null for a path without them. */
private fun examplePrefix(serverPath: String): String? {
    if (!serverPath.startsWith("/")) return null
    val folders = serverPath.split('/').filter { it.isNotEmpty() }.dropLast(1)
    if (folders.isEmpty()) return null
    return folders.take(2).joinToString(separator = "/", prefix = "/")
}

@Composable
private fun Progress(title: String?, status: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .padding(horizontal = TvPlayerChrome.SideInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(text = "ZIDOO PLAYER", style = TvPlayerType.Eyebrow)
        if (title != null) {
            Text(text = title, style = TvPlayerType.Title, textAlign = TextAlign.Center)
        }
        Text(text = status, style = TvPlayerType.RowDetail, textAlign = TextAlign.Center)
    }
}
