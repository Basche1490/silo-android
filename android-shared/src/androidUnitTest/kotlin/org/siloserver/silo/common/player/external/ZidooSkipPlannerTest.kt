package org.siloserver.silo.common.player.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZidooSkipPlannerTest {

    private val intro = SkipRange(startSeconds = 30.0, endSeconds = 90.0)
    private val credits = SkipRange(startSeconds = 2_700.0, endSeconds = 3_000.0)
    private val both = ZidooSkipSettings(skipIntro = true, skipCredits = true)

    private fun planner(
        settings: ZidooSkipSettings = both,
        intro: SkipRange? = this.intro,
        credits: SkipRange? = this.credits,
    ) = ZidooSkipPlanner(intro, credits, settings)

    @Test
    fun skipsToTheEndOfTheIntroOnceInsideIt() {
        val planner = planner()

        assertNull(planner.nextAction(positionMs = 29_999, durationMs = 3_600_000))
        assertEquals(
            SkipAction(SkipKind.INTRO, 90_000),
            planner.nextAction(positionMs = 30_000, durationMs = 3_600_000),
        )
    }

    @Test
    fun doesNotSkipTheSameMarkerTwice() {
        val planner = planner()
        planner.nextAction(positionMs = 45_000, durationMs = 3_600_000)

        // The viewer scrubbed back into the intro: leave it alone.
        assertNull(planner.nextAction(positionMs = 40_000, durationMs = 3_600_000))
    }

    @Test
    fun releaseLetsAFailedSeekTryAgain() {
        val planner = planner()
        val first = planner.nextAction(positionMs = 45_000, durationMs = 3_600_000)!!

        planner.release(first.kind)

        assertEquals(first, planner.nextAction(positionMs = 46_000, durationMs = 3_600_000))
    }

    @Test
    fun skipsCreditsToJustBeforeTheEndOfTheFile() {
        // The credits marker runs past the file, so the jump stops one second short.
        val planner = planner(credits = SkipRange(3_500.0, 3_700.0))

        assertEquals(
            SkipAction(SkipKind.CREDITS, 3_599_000),
            planner.nextAction(positionMs = 3_550_000, durationMs = 3_600_000),
        )
    }

    @Test
    fun skipsOnlyWhatIsEnabled() {
        val introOnly = planner(ZidooSkipSettings(skipIntro = true, skipCredits = false))
        val creditsOnly = planner(ZidooSkipSettings(skipIntro = false, skipCredits = true))
        val none = planner(ZidooSkipSettings(skipIntro = false, skipCredits = false))

        assertNull(introOnly.nextAction(positionMs = 2_800_000, durationMs = 3_600_000))
        assertNull(creditsOnly.nextAction(positionMs = 45_000, durationMs = 3_600_000))
        assertNull(none.nextAction(positionMs = 45_000, durationMs = 3_600_000))
        assertEquals(SkipKind.INTRO, introOnly.nextAction(positionMs = 45_000, durationMs = 3_600_000)?.kind)
        assertEquals(SkipKind.CREDITS, creditsOnly.nextAction(positionMs = 2_800_000, durationMs = 3_600_000)?.kind)
    }

    @Test
    fun appliesTheOffsetToBothEnds() {
        val planner = planner(ZidooSkipSettings(skipIntro = true, skipCredits = false, offsetMs = 2_000))

        assertNull(planner.nextAction(positionMs = 31_999, durationMs = 3_600_000))
        assertEquals(
            SkipAction(SkipKind.INTRO, 92_000),
            planner.nextAction(positionMs = 32_000, durationMs = 3_600_000),
        )
    }

    @Test
    fun ignoresMarkersThatCouldNotProduceASafeTarget() {
        val planner = planner(
            intro = SkipRange(startSeconds = 50.0, endSeconds = 50.0),
            credits = SkipRange(startSeconds = Double.NaN, endSeconds = 10.0),
        )

        assertNull(planner.nextAction(positionMs = 50_000, durationMs = 3_600_000))
        assertNull(planner.nextAction(positionMs = 10_000, durationMs = 3_600_000))
    }

    @Test
    fun staysQuietUntilThePlayerHasAPosition() {
        val planner = planner(intro = SkipRange(0.0, 60.0))

        assertNull(planner.nextAction(positionMs = 0, durationMs = 3_600_000))
        assertNull(planner.nextAction(positionMs = 1_000, durationMs = 0))
        assertEquals(60_000, planner.nextAction(positionMs = 1_000, durationMs = 3_600_000)?.targetMs)
    }

    @Test
    fun doesNotJumpWhenTheMarkerIsAlreadyInsideTheFinalMargin() {
        val planner = planner(credits = SkipRange(3_599.5, 3_600.0))

        assertNull(planner.nextAction(positionMs = 3_599_600, durationMs = 3_600_000))
    }
}
