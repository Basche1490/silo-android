package org.siloserver.silo.common.player.external

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalPlayerSettingsTest {

    private class MemoryStorage : ExternalPlayerSettings.Storage {
        val values = mutableMapOf<String, Boolean>()
        override fun read(key: String): Boolean? = values[key]
        override fun write(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun defaultsLeaveTheBuiltInPlayerInCharge() {
        val settings = ExternalPlayerSettings(MemoryStorage())

        assertEquals(false, settings.enabled.value)
        assertEquals(ZidooSkipSettings(skipIntro = true, skipCredits = false), settings.skipSettings())
    }

    @Test
    fun changesPersistAndSurviveARestart() {
        val storage = MemoryStorage()
        ExternalPlayerSettings(storage).apply {
            setEnabled(true)
            setSkipIntro(false)
            setSkipCredits(true)
        }

        val reloaded = ExternalPlayerSettings(storage)

        assertEquals(true, reloaded.enabled.value)
        assertEquals(ZidooSkipSettings(skipIntro = false, skipCredits = true), reloaded.skipSettings())
    }
}
