package org.siloserver.silo.tv.ui.screens.player.external

import kotlinx.coroutines.flow.first
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.external.ZidooPlayerCapabilities
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot

/**
 * Builds the capabilities a hand-off session is planned for: this box's, as
 * Silo's TV player detects them, widened to the Zidoo player's
 * ([ZidooPlayerCapabilities]).
 *
 * They reach the TV starter as the request's `recoveryStartParams`, the one
 * input through which it takes capabilities from its caller, so the starter
 * itself is unchanged. It reads only the capabilities and playback context
 * from them (and a null `allowAlternateVersions`, as for a fresh start).
 * Detection mirrors the starter's own: the same Dolby Vision policy and
 * forced-HDR setting, form factor `tv`, and the installed version.
 */
class ZidooStartParams(
    private val detector: PlaybackCapabilityDetector,
    private val playerSettings: PlayerSettingsStore,
) {
    suspend fun forRequest(request: VideoPlaybackStartRequest): StartParams {
        val dolbyVision = playerSettings.dolbyVisionPolicySnapshot()
        val detected = detector.detect(
            dolbyVision = dolbyVision,
            forceHdrPassthrough = playerSettings.forceHdrPassthroughFlow.first(),
        )
        val capabilities = ZidooPlayerCapabilities.capabilities(detected)
        val context = ZidooPlayerCapabilities.playbackContext(
            detector.detectPlaybackContext(
                formFactor = "tv",
                appVersion = detector.detectedAppVersion(),
                dolbyVision = dolbyVision,
                capabilities = detected,
            ),
        )
        return StartParams(
            contentId = request.contentId,
            // Not read by the starter, which resolves the file itself.
            fileId = request.preferredFileId ?: 0,
            capabilities = capabilities,
            clientPlaybackContext = context,
        )
    }
}
