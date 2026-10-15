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
import org.siloserver.silo.model.playback.QUALITY_ORIGINAL_V3

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
 * Watch Party, phone casts and Shuffle stay on Silo's player: the first two
 * drive the player directly, and Shuffle relies on the built-in player moving
 * on to the next item. The choice is saved per back-stack entry, so "Play in
 * Silo" after a failed hand-off sticks.
 */
@Composable
fun handOffToZidooIfEnabled(
    entryId: String,
    args: ZidooHandoffArgs,
    onExit: () -> Unit,
    /** Opens the player for the next episode in place of this one; the count is for the next one. */
    onPlayNextEpisode: (contentId: String, autoAdvanceCount: Int) -> Unit,
    settings: ExternalPlayerSettings = koinInject(),
): Boolean {
    var playExternally by rememberSaveable(entryId) {
        mutableStateOf(
            settings.enabled.value &&
                args.roomId == null &&
                args.castLaunchId == null &&
                args.shuffleId == null,
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
            // unless one was chosen, and ask the server not to plan a
            // lower-quality transcode.
            preferredQualityOverride = args.preferredQuality ?: QUALITY_ORIGINAL_V3,
            playbackQualityIntent = QUALITY_ORIGINAL_V3,
            episodeSelectionHandoff = args.episodeSelectionHandoff,
        )
    }
    TvExternalPlayerScreen(
        request = request,
        onExit = onExit,
        onPlayInSilo = { playExternally = false },
        autoAdvanceCount = args.autoAdvanceCount,
        onPlayNextEpisode = onPlayNextEpisode,
    )
    return true
}
