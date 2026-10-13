package org.siloserver.silo.tv.ui.screens.player.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.tv.ui.screens.settings.SettingsFooterText
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroup
import org.siloserver.silo.tv.ui.screens.settings.SettingsToggleRow

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
        SettingsFooterText(
            text = if (plexToZidooInstalled) {
                "Movies and episodes open in the Zidoo player through PlexToZidoo. Set PlexToZidoo's path " +
                    "substitution to turn your Silo server's paths into SMB or NFS locations. Silo keeps your " +
                    "place and skips the markers you choose. Watch Party, Shuffle and casting from a phone " +
                    "still use Silo's player."
            } else {
                "PlexToZidoo isn't installed. Get it from github.com/bowlingbeeg/PlexToZidoo/releases " +
                    "before turning this on."
            },
        )
    }
}
