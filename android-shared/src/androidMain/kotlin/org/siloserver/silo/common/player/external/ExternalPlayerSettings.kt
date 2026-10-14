package org.siloserver.silo.common.player.external

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → External Player. Device-local and never synced: whether this box
 * hands video to the Zidoo player through PlexToZidoo, and which markers that
 * playback skips on its own.
 *
 * Playback is off until the user turns it on, so a normal install behaves
 * exactly as before. Intro skipping defaults on. Credits skipping defaults off
 * because on a film it jumps to the end and past any scene after the credits.
 * Fitting the interface to the TV defaults on: it only acts on a box that
 * reports a lower screen density than Android TV's standard (Zidoo does).
 */
class ExternalPlayerSettings(private val storage: Storage) {

    /** Where the values live; the TV app backs it with SharedPreferences. */
    interface Storage {
        fun read(key: String): Boolean?
        fun write(key: String, value: Boolean)
    }

    private val _enabled = MutableStateFlow(storage.read(KEY_ENABLED) ?: false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _skipIntro = MutableStateFlow(storage.read(KEY_SKIP_INTRO) ?: true)
    val skipIntro: StateFlow<Boolean> = _skipIntro.asStateFlow()

    private val _skipCredits = MutableStateFlow(storage.read(KEY_SKIP_CREDITS) ?: false)
    val skipCredits: StateFlow<Boolean> = _skipCredits.asStateFlow()

    private val _fitToTv = MutableStateFlow(storage.read(KEY_FIT_TO_TV) ?: FIT_TO_TV_DEFAULT)
    val fitToTv: StateFlow<Boolean> = _fitToTv.asStateFlow()

    fun setEnabled(value: Boolean) = set(KEY_ENABLED, _enabled, value)

    fun setSkipIntro(value: Boolean) = set(KEY_SKIP_INTRO, _skipIntro, value)

    fun setSkipCredits(value: Boolean) = set(KEY_SKIP_CREDITS, _skipCredits, value)

    fun setFitToTv(value: Boolean) = set(KEY_FIT_TO_TV, _fitToTv, value)

    /** The skip rules for a playback starting now. */
    fun skipSettings(): ZidooSkipSettings =
        ZidooSkipSettings(skipIntro = _skipIntro.value, skipCredits = _skipCredits.value)

    private fun set(key: String, flow: MutableStateFlow<Boolean>, value: Boolean) {
        storage.write(key, value)
        flow.value = value
    }

    companion object {
        /** Preferences file for these settings. */
        const val PREFS_NAME = "silo_external_player"
        private const val KEY_ENABLED = "zidoo_enabled"
        private const val KEY_SKIP_INTRO = "zidoo_skip_intro"
        private const val KEY_SKIP_CREDITS = "zidoo_skip_credits"

        /** Read straight from [PREFS_NAME] by the TV activity before anything is injected. */
        const val KEY_FIT_TO_TV = "fit_interface_to_tv"
        const val FIT_TO_TV_DEFAULT = true
    }
}
