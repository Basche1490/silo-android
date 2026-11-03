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
 * The rules follow the ZidooPlexMod add-on and Silo's own player:
 * - A file may have several intros or several credits ranges (for example two
 *   credits ranges around a mid-credits scene). Each one triggers while the
 *   position is inside it, and the jump goes to that range's own end, never
 *   over the content between two of them. Where ranges overlap, the one that
 *   started last is the one playing, as in Silo's `MarkerTimeline`.
 * - A jump never lands closer than [FINAL_MARGIN_MS] to the end of the file, so
 *   the player still reaches its natural end (and the server sees the title
 *   finished) rather than being left parked on the last frame.
 * - Each range triggers once per playback. Seeking back into it afterwards
 *   does not skip it again. Ranges that end together count as one.
 */
class ZidooSkipPlanner(
    intros: List<SkipRange>,
    credits: List<SkipRange>,
    private val settings: ZidooSkipSettings,
) {
    private val ranges: Map<SkipKind, List<SkipRange>> = mapOf(
        SkipKind.INTRO to usableInOrder(intros),
        SkipKind.CREDITS to usableInOrder(credits),
    )

    /** The ends, in milliseconds, of the ranges already skipped, per kind. */
    private val done = mutableMapOf<SkipKind, MutableSet<Long>>()

    /** The end of the range each kind skipped last, for [release]. */
    private val lastSkipped = mutableMapOf<SkipKind, Long>()

    /**
     * The jump to make for the current [positionMs], or null when nothing
     * should be skipped. The caller reports a failed seek with [release] so the
     * range can trigger again on the next poll.
     */
    fun nextAction(positionMs: Long, durationMs: Long): SkipAction? {
        if (positionMs <= 0L || durationMs <= 0L) return null
        if (settings.skipIntro) {
            actionFor(SkipKind.INTRO, positionMs, durationMs)?.let { return it }
        }
        if (settings.skipCredits) {
            actionFor(SkipKind.CREDITS, positionMs, durationMs)?.let { return it }
        }
        return null
    }

    /** Lets the range [kind] skipped last trigger again; used when its seek did not happen. */
    fun release(kind: SkipKind) {
        lastSkipped.remove(kind)?.let { end -> done[kind]?.remove(end) }
    }

    private fun actionFor(kind: SkipKind, positionMs: Long, durationMs: Long): SkipAction? {
        val playing = ranges.getValue(kind).lastOrNull { range ->
            positionMs >= range.startMs() && positionMs < range.targetMs(durationMs)
        } ?: return null
        val end = playing.endMs()
        val skipped = done.getOrPut(kind) { mutableSetOf() }
        if (!skipped.add(end)) return null
        lastSkipped[kind] = end
        return SkipAction(kind, playing.targetMs(durationMs))
    }

    private fun SkipRange.startMs(): Long = max(0L, (startSeconds * 1000.0).toLong())

    private fun SkipRange.endMs(): Long = ceil(endSeconds * 1000.0).toLong()

    private fun SkipRange.targetMs(durationMs: Long): Long = min(endMs(), durationMs - FINAL_MARGIN_MS)

    companion object {
        /** Same margin the Kodi add-on keeps before the end of the file. */
        const val FINAL_MARGIN_MS = 1_000L

        private fun usableInOrder(ranges: List<SkipRange>): List<SkipRange> =
            ranges.filter { it.isUsable }.sortedWith(compareBy({ it.startSeconds }, { it.endSeconds }))
    }
}
