package org.siloserver.silo.tv.ui.screens.player.external

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.tv.ui.screens.settings.PickerOption
import org.siloserver.silo.tv.ui.screens.settings.SettingsFooterText
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroup
import org.siloserver.silo.tv.ui.screens.settings.SettingsToggleRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsValueRow
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsPickerSheet

/**
 * Settings → External Player: play movies and episodes in the Zidoo player via
 * PlexToZidoo, with Silo still tracking resume progress and skipping markers.
 */
@Composable
internal fun TvExternalPlayerSettingsGroup(
    settings: ExternalPlayerSettings = koinInject(),
) {
    val context = LocalContext.current
    val enabled by settings.enabled.collectAsState()
    val skipIntro by settings.skipIntro.collectAsState()
    val skipCredits by settings.skipCredits.collectAsState()
    val fitToTv by settings.fitToTv.collectAsState()
    val upNextCountdown by settings.upNextCountdownSeconds.collectAsState()
    var choosingCountdown by remember { mutableStateOf(false) }
    // Checked again on each toggle, so installing PlexToZidoo and coming back is noticed.
    val plexToZidooInstalled = remember(enabled) { isPlexToZidooInstalled(context) }
    SettingsGroup(title = "External Player") {
        SettingsToggleRow(
            label = "Play in Zidoo Player",
            checked = enabled,
            onCheckedChange = settings::setEnabled,
        )
        SettingsToggleRow(
            label = "Skip Intros Automatically",
            checked = skipIntro,
            onCheckedChange = settings::setSkipIntro,
            enabled = enabled,
        )
        SettingsToggleRow(
            label = "Skip Credits Automatically",
            checked = skipCredits,
            onCheckedChange = settings::setSkipCredits,
            enabled = enabled,
        )
        SettingsValueRow(
            label = "Up Next Countdown",
            value = upNextCountdownLabel(upNextCountdown),
            onClick = { choosingCountdown = true },
            enabled = enabled,
        )
        SettingsFooterText(
            text = if (plexToZidooInstalled) {
                "Movies and episodes open in the Zidoo player through PlexToZidoo, streamed from your Silo " +
                    "server, or from your network share where a PlexToZidoo path substitution matches. Silo " +
                    "keeps your place and skips the markers you choose. After an episode, Up Next opens the " +
                    "next one once its countdown ends, when Auto-Play Next Episode is on. Watch Party, " +
                    "Shuffle and casting from a phone still use Silo's player."
            } else {
                "PlexToZidoo isn't installed. Get it from github.com/bowlingbeeg/PlexToZidoo/releases " +
                    "before turning this on."
            },
        )
    }
    if (choosingCountdown) {
        TvSettingsPickerSheet(
            title = "Up Next Countdown",
            options = ExternalPlayerSettings.UP_NEXT_COUNTDOWN_OPTIONS.map {
                PickerOption(it.toString(), upNextCountdownLabel(it))
            },
            selectedId = upNextCountdown.toString(),
            onSelect = { id ->
                id.toIntOrNull()?.let(settings::setUpNextCountdownSeconds)
                choosingCountdown = false
            },
            onDismiss = { choosingCountdown = false },
        )
    }
    SettingsGroup(title = "Interface Size") {
        SettingsToggleRow(
            label = "Fit Interface to TV",
            checked = fitToTv,
            onCheckedChange = { value ->
                settings.setFitToTv(value)
                // The size is set as the screen is created, so rebuild it.
                context.findActivity()?.recreate()
            },
        )
        SettingsFooterText(
            text = "Shows Silo at the standard Android TV size on players that shrink apps with a lower " +
                "screen density, such as Zidoo. Turn off to use the player's own size.",
        )
    }
}

private fun upNextCountdownLabel(seconds: Int): String = when (seconds) {
    0 -> "Play right away"
    1 -> "1 second"
    else -> "$seconds seconds"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
