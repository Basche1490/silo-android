package org.siloserver.silo.common.player.external

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZidooPlaybackMonitorTest {

    private val duration = 3_600_000L

    private fun playing(positionMs: Long, durationMs: Long = duration) =
        ZidooPlayStatus(durationMs, positionMs, ZidooPlayState.PLAYING)

    private fun paused(positionMs: Long) = ZidooPlayStatus(duration, positionMs, ZidooPlayState.PAUSED)

    /** Answers `status()` from a script, then null forever, like a player that has closed. */
    private class ScriptedPlayer(vararg readings: ZidooPlayStatus?) : ZidooPlayerApi {
        private val queue = ArrayDeque(readings.toList())
        val seeks = mutableListOf<Long>()
        var seekSucceeds = true

        override suspend fun status(): ZidooPlayStatus? = queue.removeFirstOrNull()

        override suspend fun seekTo(positionMs: Long): Boolean {
            seeks += positionMs
            return seekSucceeds
        }

        override suspend fun stop(): Boolean = true
    }

    private fun monitor(
        api: ZidooPlayerApi,
        settings: ZidooSkipSettings = ZidooSkipSettings(skipIntro = true, skipCredits = true),
        startupTimeoutMs: Long = 5_000L,
    ) = ZidooPlaybackMonitor(
        api = api,
        planner = ZidooSkipPlanner(
            intro = SkipRange(30.0, 90.0),
            credits = SkipRange(3_300.0, 3_600.0),
            settings = settings,
        ),
        startupTimeoutMs = startupTimeoutMs,
        pause = {},
    )

    @Test
    fun reportsEachPositionAndEndsAfterThreeMissedPolls() = runBlocking {
        val api = ScriptedPlayer(playing(5_000), playing(6_000), paused(6_500), null, null, null)
        val samples = mutableListOf<ZidooSample>()
        var started = 0

        val outcome = monitor(api).run(onStarted = { started++ }, onSample = { samples += it })

        assertEquals(1, started)
        assertEquals(
            listOf(
                ZidooSample(5_000, duration, paused = false),
                ZidooSample(6_000, duration, paused = false),
                ZidooSample(6_500, duration, paused = true),
            ),
            samples,
        )
        assertEquals(ZidooMonitorOutcome.Finished(6_500, duration), outcome)
    }

    @Test
    fun anOccasionalMissedPollDoesNotEndPlayback() = runBlocking {
        val api = ScriptedPlayer(playing(5_000), null, null, playing(8_000), null, null, null)
        val samples = mutableListOf<ZidooSample>()

        val outcome = monitor(api).run(onStarted = {}, onSample = { samples += it })

        assertEquals(listOf(5_000L, 8_000L), samples.map { it.positionMs })
        assertEquals(ZidooMonitorOutcome.Finished(8_000, duration), outcome)
    }

    @Test
    fun givesUpWhenThePlayerNeverStarts() = runBlocking {
        val api = ScriptedPlayer(null, ZidooPlayStatus(0, 0, ZidooPlayState.STOPPED), null, null, null, null)
        var started = false

        val outcome = monitor(api, startupTimeoutMs = 3_000).run(onStarted = { started = true }, onSample = {})

        assertEquals(ZidooMonitorOutcome.PlayerDidNotStart, outcome)
        assertEquals(false, started)
    }

    @Test
    fun stopsWaitingAsSoonAsTheCallerKnowsThePlayerClosed() = runBlocking {
        val player = ScriptedPlayer()
        var polls = 0
        val api = object : ZidooPlayerApi by player {
            override suspend fun status(): ZidooPlayStatus? = player.status().also { polls++ }
        }

        val outcome = monitor(api, startupTimeoutMs = 60_000)
            .run(onStarted = {}, onSample = {}, giveUpStarting = { polls >= 2 })

        assertEquals(ZidooMonitorOutcome.PlayerDidNotStart, outcome)
        assertEquals(2, polls)
    }

    @Test
    fun givingUpOnlyAppliesBeforeTheVideoLoads() = runBlocking {
        val player = ScriptedPlayer(null, playing(5_000), playing(6_000), null, null, null)
        var polls = 0
        val api = object : ZidooPlayerApi by player {
            override suspend fun status(): ZidooPlayStatus? = player.status().also { polls++ }
        }
        var started = false

        val outcome = monitor(api, startupTimeoutMs = 60_000)
            .run(onStarted = { started = true }, onSample = {}, giveUpStarting = { polls >= 2 })

        assertTrue(started)
        assertEquals(ZidooMonitorOutcome.Finished(6_000, duration), outcome)
    }

    @Test
    fun endsWhenThePlayerReportsNoVideoOrAStoppedState() = runBlocking {
        val noVideo = ScriptedPlayer(playing(5_000), ZidooPlayStatus(0, 0, ZidooPlayState.STOPPED))
        val stopped = ScriptedPlayer(playing(5_000), ZidooPlayStatus(duration, 7_000, ZidooPlayState.STOPPED))

        assertEquals(
            ZidooMonitorOutcome.Finished(5_000, duration),
            monitor(noVideo).run(onStarted = {}, onSample = {}),
        )
        assertEquals(
            ZidooMonitorOutcome.Finished(5_000, duration),
            monitor(stopped).run(onStarted = {}, onSample = {}),
        )
    }

    @Test
    fun ignoresZeroPositionsSoAResumePointIsNotOverwritten() = runBlocking {
        val api = ScriptedPlayer(playing(0), playing(0), playing(2_400_000), null, null, null)
        val samples = mutableListOf<Long>()

        val outcome = monitor(api).run(onStarted = {}, onSample = { samples += it.positionMs })

        assertEquals(listOf(2_400_000L), samples)
        assertEquals(ZidooMonitorOutcome.Finished(2_400_000, duration), outcome)
    }

    @Test
    fun seeksPastTheIntroWhileItPlays() = runBlocking {
        val api = ScriptedPlayer(playing(20_000), playing(31_000), playing(90_500), null, null, null)

        monitor(api).run(onStarted = {}, onSample = {})

        assertEquals(listOf(90_000L), api.seeks)
    }

    @Test
    fun retriesASkipWhoseSeekFailed() = runBlocking {
        val api = ScriptedPlayer(playing(31_000), playing(32_000), playing(33_000), null, null, null)
        api.seekSucceeds = false

        monitor(api).run(onStarted = {}, onSample = {})

        // Every poll inside the intro asks again, because no seek ever landed.
        assertEquals(listOf(90_000L, 90_000L, 90_000L), api.seeks)
    }

    @Test
    fun doesNotSkipWhilePaused() = runBlocking {
        val api = ScriptedPlayer(paused(45_000), paused(46_000), null, null, null)

        monitor(api).run(onStarted = {}, onSample = {})

        assertTrue(api.seeks.isEmpty())
    }

    @Test
    fun doesNotSkipWhenSkippingIsOff() = runBlocking {
        val api = ScriptedPlayer(playing(45_000), playing(3_400_000), null, null, null)

        monitor(api, ZidooSkipSettings(skipIntro = false, skipCredits = false))
            .run(onStarted = {}, onSample = {})

        assertTrue(api.seeks.isEmpty())
    }

    @Test
    fun settlesAStopNearTheEndAsTheEnd() {
        assertEquals(duration, settledPositionMs(duration - 1_000, duration))
        assertEquals(duration, settledPositionMs(duration - 10_000, duration))
        assertEquals(duration - 10_001, settledPositionMs(duration - 10_001, duration))
        assertEquals(1_200_000L, settledPositionMs(1_200_000, duration))
        // A short clip uses 5% of its length rather than ten seconds.
        assertEquals(20_000L, settledPositionMs(19_000, 20_000))
        assertEquals(18_999L, settledPositionMs(18_999, 20_000))
        assertEquals(0L, settledPositionMs(0, duration))
    }

    @Test
    fun parsesPlayerStatusTheWayTheKodiAddonReadsIt() {
        val playing = parseZidooStatus(
            """{"status":200,"video":{"duration":5400000,"currentPosition":123456,"status":1,"title":"x"}}""",
        )
        val paused = parseZidooStatus("""{"status":200,"video":{"duration":5400000,"currentPosition":1,"status":0}}""")
        val other = parseZidooStatus("""{"status":200,"video":{"duration":5400000,"currentPosition":1,"status":3}}""")
        val fractional = parseZidooStatus("""{"status":200,"video":{"duration":5400000.0,"currentPosition":99.0,"status":1}}""")

        assertEquals(ZidooPlayStatus(5_400_000, 123_456, ZidooPlayState.PLAYING), playing)
        assertEquals(ZidooPlayState.PAUSED, paused?.state)
        assertEquals(ZidooPlayState.STOPPED, other?.state)
        assertEquals(ZidooPlayStatus(5_400_000, 99, ZidooPlayState.PLAYING), fractional)
    }

    @Test
    fun rejectsStatusBodiesItCannotTrust() {
        assertNull(parseZidooStatus("""{"status":500,"video":{"duration":1,"currentPosition":1,"status":1}}"""))
        assertNull(parseZidooStatus("""{"status":200}"""))
        assertNull(parseZidooStatus("""{"status":200,"video":{"currentPosition":1,"status":1}}"""))
        assertNull(parseZidooStatus("""{"status":200,"video":"nope"}"""))
        assertNull(parseZidooStatus("not json"))
        assertNull(parseZidooStatus("[1,2]"))
        assertNull(parseZidooStatus(""))
    }

    @Test
    fun readsTheSeekAndStopAcknowledgements() {
        assertTrue(isZidooOk("""{"status":200,"msg":"ok"}"""))
        assertEquals(false, isZidooOk("""{"status":404}"""))
        assertEquals(false, isZidooOk("garbage"))
    }
}
