package org.siloserver.silo.tv.ui.screens.player.external

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.player.external.HttpZidooPlayerApi
import org.siloserver.silo.common.player.external.ZidooPlayerApi
import org.siloserver.silo.common.profiles.ProfileAwayTracker

/**
 * Dependency wiring for Settings → External Player (Zidoo via PlexToZidoo).
 * Kept in its own module, included from `androidTvModule`, so the fork touches
 * as little of Silo's own wiring as possible.
 */
val externalPlayerTvModule = module {
    // Device-local, never synced.
    single {
        val prefs = androidContext().getSharedPreferences(ExternalPlayerSettings.PREFS_NAME, Context.MODE_PRIVATE)
        ExternalPlayerSettings(
            object : ExternalPlayerSettings.Storage {
                override fun read(key: String): Boolean? =
                    if (prefs.contains(key)) prefs.getBoolean(key, false) else null

                override fun write(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
            },
        )
    }
    single<ZidooPlayerApi> { HttpZidooPlayerApi() }
    single { ZidooStartParams(detector = get(), playerSettings = get()) }
    single {
        val startParams = get<ZidooStartParams>()
        ZidooHandoffCoordinator(
            appContext = androidContext(),
            starter = get(named("tvVideoPlaybackStarter")),
            sessionLifecycle = get(),
            sessionManager = get(),
            settings = get(),
            zidoo = get(),
            // Zidoo playback is playback on another screen: tell the profile
            // tracker when it pauses, resumes or ends, as the phone does for Cast.
            onPlaybackActivityChanged = { get<ProfileAwayTracker>().onRemotePlaybackChanged() },
            // Plan for the Zidoo player, so the server sends the file untouched
            // instead of converting it for Silo's own player.
            zidooStartParams = startParams::forRequest,
            catalogRepository = get(),
        )
    }
}
