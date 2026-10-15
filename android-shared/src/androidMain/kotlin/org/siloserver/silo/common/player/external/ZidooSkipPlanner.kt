package org.siloserver.silo.common.player.external

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A marker range in seconds, as Silo reports intro and credits. */
data class SkipRange(val startSeconds: Double, val endSeconds: Double) {
    /** A range that cannot produce a bogus skip target is finite, non-negative and non-empty. */
    val isUsable: Boolean
        get() = startSeconds.isFinite() && endSeconds.isFinite() && startSeconds >= 0.0 && endSeconds > startSeconds
}

data class ZidooSkipSettings(
    val skipIntro: Boolean,
    val skipCredits: Boolean,
)

enum class SkipKind { INTRO, CREDITS }

data class SkipAction(val kind: SkipKind, val targetMs: Long)

/**
 * Decides when the Zidoo player should jump over an intro or the credits.
 *
 * The rules follow the ZidooPlexMod add-on, so behaviour matches the setup this
 * client is meant to replace:
 * - A marker triggers while the position is inside it, and the jump goes to the
 *   marker's end.
 * - A jump never lands closer than [FINAL_MARGIN_MS] to the end of the file, so
 *   the player still reaches its natural end (and the server sees the title
 *   finished) rather than being left parked on the last frame.
 * - Each marker triggers once per playback. Seeking back into it afterwards
 *   does not skip it again.
 */
class ZidooSkipPlanner(
    private val intro: SkipRange?,
    private val credits: SkipRange?,
    private val settings: ZidooSkipSettings,
) {
    private val done = mutableSetOf<SkipKind>()

    /**
     * The jump to make for the current [positionMs], or null when nothing
     * should be skipped. The caller reports a failed seek with [release] so the
     * marker can trigger again on the next poll.
     */
    fun nextAction(positionMs: Long, durationMs: Long): SkipAction? {
        if (positionMs <= 0L || durationMs <= 0L) return null
        if (settings.skipIntro) {
            actionFor(SkipKind.INTRO, intro, positionMs, durationMs)?.let { return it }
        }
        if (settings.skipCredits) {
            actionFor(SkipKind.CREDITS, credits, positionMs, durationMs)?.let { return it }
        }
        return null
    }

    /** Lets [kind] trigger again; used when the seek that [nextAction] asked for did not happen. */
    fun release(kind: SkipKind) {
        done.remove(kind)
    }

    private fun actionFor(kind: SkipKind, range: SkipRange?, positionMs: Long, durationMs: Long): SkipAction? {
        if (kind in done) return null
        val usable = range?.takeIf { it.isUsable } ?: return null
        val start = max(0L, (usable.startSeconds * 1000.0).toLong())
        val end = min(ceil(usable.endSeconds * 1000.0).toLong(), durationMs - FINAL_MARGIN_MS)
        if (end <= start) return null
        if (positionMs < start || positionMs >= end) return null
        done.add(kind)
        return SkipAction(kind, end)
    }

    companion object {
        /** Same margin the Kodi add-on keeps before the end of the file. */
        const val FINAL_MARGIN_MS = 1_000L
    }
}
