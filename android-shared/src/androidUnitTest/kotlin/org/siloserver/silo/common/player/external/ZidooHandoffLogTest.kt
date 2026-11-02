package org.siloserver.silo.common.player.external

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZidooHandoffLogTest {

    private val dir: File = Files.createTempDirectory("zidoo-log").toFile()
    private val file = File(dir, "zidoo-handoff.log")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun keepsTheLatestHandOffAcrossARestart() {
        ZidooHandoffLog(file).apply {
            add("Silo started")
            startRun("Hand-off started")
            add("PlexToZidoo opened")
        }

        // Silo was closed: a new process reads the same file and adds to it.
        val afterRestart = ZidooHandoffLog(file).apply { add("Silo started") }

        val messages = afterRestart.lines().map { it.substringAfter("  ") }
        assertEquals(listOf("Hand-off started", "PlexToZidoo opened", "Silo started"), messages)
    }

    @Test
    fun stopsAtTheLimitButStillRecordsACrash() {
        val log = ZidooHandoffLog(file)
        log.startRun("Hand-off started")
        repeat(ZidooHandoffLog.MAX_LINES + 50) { log.add("line $it") }
        assertEquals(ZidooHandoffLog.MAX_LINES, log.lines().size)

        log.addCrash("main", IllegalStateException("boom", RuntimeException("cause")))

        val lines = log.lines()
        assertTrue(lines.any { it.endsWith("CRASH on thread main: java.lang.IllegalStateException: boom") }, lines.last())
        assertTrue(lines.any { "Caused by: java.lang.RuntimeException: cause" in it })
    }
}
