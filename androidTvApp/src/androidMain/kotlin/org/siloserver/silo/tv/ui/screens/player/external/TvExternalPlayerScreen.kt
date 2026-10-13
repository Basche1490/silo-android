package org.siloserver.silo.tv.ui.screens.player.external

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.tv.ui.components.TvDialogAction
import org.siloserver.silo.tv.ui.components.TvDialogActionStyle
import org.siloserver.silo.tv.ui.components.TvDialogCard
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
    onExit: () -> Unit,
    onPlayInSilo: () -> Unit,
    coordinator: ZidooHandoffCoordinator = koinInject(),
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
            runId = coordinator.start(request) { intent -> context.startActivity(intent) }
        } else if (coordinator.state.value.runId != runId) {
            onExit()
        }
    }

    LaunchedEffect(state, runId) {
        val current = state
        if (runId != 0L && current is ZidooHandoffState.Finished && current.runId == runId) {
            coordinator.acknowledge(runId)
            onExit()
        }
    }

    // Tell the run when this screen goes behind PlexToZidoo and comes back. A
    // return before the Zidoo player reported a video means it closed without
    // playing, so the run reports that within seconds instead of waiting.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, runId) {
        val observer = LifecycleEventObserver { _, event ->
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

            is ZidooHandoffState.Playing -> TvDialogCard(
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

            is ZidooHandoffState.Launched -> Progress(current.title, "Opening the Zidoo player…")
            is ZidooHandoffState.Saving -> Progress(current.title, "Saving your progress…")
            is ZidooHandoffState.Preparing,
            is ZidooHandoffState.Finished,
            ZidooHandoffState.Idle,
            -> Progress(title = null, status = "Getting ready…")
        }
    }
}

@Composable
private fun FailedCard(
    failure: ZidooHandoffState.Failed,
    onOpenPlexToZidoo: () -> Unit,
    onPlayInSilo: () -> Unit,
    onBack: () -> Unit,
) {
    val path = failure.serverPath
    val actions = buildList {
        if (path != null) {
            add(TvDialogAction("Open PlexToZidoo", onOpenPlexToZidoo, TvDialogActionStyle.Primary))
        }
        if (failure.canPlayInSilo) {
            val style = if (path == null) TvDialogActionStyle.Primary else TvDialogActionStyle.Default
            add(TvDialogAction("Play in Silo", onPlayInSilo, style))
        }
        add(TvDialogAction("Back", onBack))
    }
    if (path == null) {
        TvDialogCard(
            title = "Couldn't play in the Zidoo player",
            message = failure.message,
            actions = actions,
            initialFocusIndex = 0,
        )
        return
    }
    // The Zidoo player was asked to play and didn't: almost always a path
    // substitution that is missing or points at the wrong share. Show the
    // exact path, because PlexToZidoo's rule has to match its start.
    TvDialogCard(
        title = "Couldn't play in the Zidoo player",
        message = failure.message,
        actions = actions,
        initialFocusIndex = 0,
        width = 640.dp,
    ) {
        Text(
            text = path,
            style = TvPlayerType.Row.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(TvPlayerChrome.Chip, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text(text = substitutionHelp(path), style = TvPlayerType.Body)
    }
}

/** What to enter in PlexToZidoo for [serverPath], using its own setting names. */
internal fun substitutionHelp(serverPath: String): String {
    val prefix = examplePrefix(serverPath)
    val start = if (prefix != null) "the start of that path, such as $prefix" else "the start of that path"
    return "In PlexToZidoo, under Substitutions, set \u201cPart of the path to replace\u201d to $start, and " +
        "\u201cReplaced with\u201d to the same folder on your network share, such as smb://192.168.1.20/video. " +
        "If a rule is already there, check the share name and the SMB username and password."
}

/** The first two folders of [serverPath] (for example `/mnt/media`), or null for a path without them. */
internal fun examplePrefix(serverPath: String): String? {
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
