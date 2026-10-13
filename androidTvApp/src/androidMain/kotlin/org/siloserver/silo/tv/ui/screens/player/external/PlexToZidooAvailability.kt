package org.siloserver.silo.tv.ui.screens.player.external

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.siloserver.silo.common.player.external.PlexToZidooLaunch

/**
 * True when PlexToZidoo is installed. The manifest's `<queries>` entry makes
 * the package visible to this lookup on Android 11 and later.
 */
internal fun isPlexToZidooInstalled(context: Context): Boolean =
    try {
        @Suppress("DEPRECATION") // The flags overload is API 33+; minSdk is 24.
        context.packageManager.getPackageInfo(PlexToZidooLaunch.PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

/**
 * Opens PlexToZidoo's settings. Pass the current Activity as [context], so the
 * settings open in Silo's task and Back returns to Silo.
 */
internal fun openPlexToZidooSettings(context: Context) {
    try {
        context.startActivity(
            Intent().setClassName(PlexToZidooLaunch.PACKAGE_NAME, PlexToZidooLaunch.SETTINGS_ACTIVITY_CLASS),
        )
    } catch (e: RuntimeException) {
        Log.w("PlexToZidoo", "Could not open PlexToZidoo's settings", e)
    }
}
