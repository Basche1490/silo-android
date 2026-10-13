package org.siloserver.silo.tv.ui.screens.player.external

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    BackHandler {
        coordinator.cancelIfPreparing(runId)
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
            is ZidooHandoffState.Failed -> TvDialogCard(
                title = "Couldn't play in the Zidoo player",
                message = current.message,
                actions = buildList {
                    if (current.canPlayInSilo) {
                        add(
                            TvDialogAction(
                                label = "Play in Silo",
                                onClick = {
                                    coordinator.acknowledge(runId)
                                    onPlayInSilo()
                                },
                                style = TvDialogActionStyle.Primary,
                            ),
                        )
                    }
                    add(
                        TvDialogAction(
                            label = "Back",
                            onClick = {
                                coordinator.acknowledge(runId)
                                onExit()
                            },
                        ),
                    )
                },
                initialFocusIndex = 0,
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
