package org.siloserver.silo.common.player.external

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalPlayerSettingsTest {

    private class MemoryStorage : ExternalPlayerSettings.Storage {
        val values = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        override fun read(key: String): Boolean? = values[key]
        override fun write(key: String, value: Boolean) {
            values[key] = value
        }
        override fun readInt(key: String): Int? = ints[key]
        override fun writeInt(key: String, value: Int) {
            ints[key] = value
        }
    }

    @Test
    fun defaultsLeaveTheBuiltInPlayerInCharge() {
        val settings = ExternalPlayerSettings(MemoryStorage())

        assertEquals(false, settings.enabled.value)
        assertEquals(ZidooSkipSettings(skipIntro = true, skipCredits = false), settings.skipSettings())
        assertEquals(10, settings.upNextCountdownSeconds.value)
    }

    @Test
    fun changesPersistAndSurviveARestart() {
        val storage = MemoryStorage()
        ExternalPlayerSettings(storage).apply {
            setEnabled(true)
            setSkipIntro(false)
            setSkipCredits(true)
            setUpNextCountdownSeconds(5)
            // Not one of the offered lengths: ignored.
            setUpNextCountdownSeconds(7)
        }

        val reloaded = ExternalPlayerSettings(storage)

        assertEquals(true, reloaded.enabled.value)
        assertEquals(ZidooSkipSettings(skipIntro = false, skipCredits = true), reloaded.skipSettings())
        assertEquals(5, reloaded.upNextCountdownSeconds.value)
    }
}
