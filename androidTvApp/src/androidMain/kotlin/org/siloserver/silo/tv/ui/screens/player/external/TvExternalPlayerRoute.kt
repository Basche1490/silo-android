package org.siloserver.silo.tv.ui.screens.player.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.player.video.EpisodeSelectionHandoff
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO

/** What the `TvRoute.Player` destination was opened with. */
data class ZidooHandoffArgs(
    val contentId: String,
    val libraryId: Int?,
    val preferredFileId: Int?,
    val preferredQuality: String?,
    val resumePositionOverride: Double?,
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
    val episodeSelectionHandoff: EpisodeSelectionHandoff?,
    val roomId: String?,
    val castLaunchId: String?,
    val shuffleId: String?,
    /** Episodes auto-played in a row before this one, for the "still watching?" limit. */
    val autoAdvanceCount: Int,
)

/**
 * Takes over the `TvRoute.Player` destination when Settings → External Player
 * is on, showing [TvExternalPlayerScreen] instead of Silo's own player.
 * Returns true when it did, in which case the caller must not compose the
 * built-in player.
 *
 * Watch Party and phone casts stay on Silo's player, because they drive the
 * player directly. A shuffle pick plays in the Zidoo player, and Up Next then
 * offers the shuffle's next random pick. The choice is saved per back-stack
 * entry, so "Play in Silo" after a failed hand-off sticks.
 */
@Composable
fun handOffToZidooIfEnabled(
    entryId: String,
    args: ZidooHandoffArgs,
    onExit: () -> Unit,
    /**
     * Opens the player for the next title in place of this one: the next
     * episode, or a shuffle's next pick with its [shuffleId]. The count is for
     * the next one.
     */
    onPlayNext: (contentId: String, autoAdvanceCount: Int, shuffleId: String?) -> Unit,
    settings: ExternalPlayerSettings = koinInject(),
): Boolean {
    var playExternally by rememberSaveable(entryId) {
        mutableStateOf(
            settings.enabled.value &&
                args.roomId == null &&
                args.castLaunchId == null,
        )
    }
    if (!playExternally) return false

    val request = remember(entryId) {
        VideoPlaybackStartRequest(
            libraryId = args.libraryId,
            contentId = args.contentId,
            preferredFileId = args.preferredFileId,
            roomId = null,
            resumePositionOverride = args.resumePositionOverride,
            audioTrackIndex = args.audioTrackIndex,
            subtitleTrackIndex = args.subtitleTrackIndex,
            // The Zidoo player reads the file itself, so pick the best version
            // unless one was chosen, whatever quality Silo's own player is set
            // to. Auto is how Silo asks for the source (it retired Original);
            // the Zidoo capabilities make the source playable.
            preferredQualityOverride = args.preferredQuality ?: PLAYBACK_QUALITY_AUTO,
            playbackQualityIntent = PLAYBACK_QUALITY_AUTO,
            episodeSelectionHandoff = args.episodeSelectionHandoff,
        )
    }
    TvExternalPlayerScreen(
        request = request,
        shuffleId = args.shuffleId?.takeIf { it.isNotBlank() },
        onExit = onExit,
        onPlayInSilo = { playExternally = false },
        autoAdvanceCount = args.autoAdvanceCount,
        onPlayNext = onPlayNext,
    )
    return true
}
