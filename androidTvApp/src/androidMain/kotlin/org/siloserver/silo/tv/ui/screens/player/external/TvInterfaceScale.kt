package org.siloserver.silo.tv.ui.screens.player.external

import android.content.Context
import android.content.res.Configuration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.siloserver.silo.common.player.external.ExternalPlayerSettings

/**
 * Lays Silo's TV interface out at Android TV's standard size on a box that
 * reports a lower screen density than usual.
 *
 * Android TV apps are designed for a screen 960 dp wide: a 1080p output at
 * 320 dpi, or 4K at 640 dpi. Zidoo players report a lower density for their
 * own interface, so the same screen measures far more than 960 dp and every
 * app that trusts the density, Silo included, draws everything smaller. The
 * TV activity applies [overrideConfiguration] as it attaches, which sets the
 * density (and the screen size in dp that goes with it) back to the standard,
 * so Silo looks as it does on any other Android TV. Text size, and every
 * other setting, keeps the system's value.
 *
 * Nothing changes on a box that already reports the standard density or a
 * higher one, or when Settings → External Player → Fit Interface to TV is off.
 */
object TvInterfaceScale {
    /** Width of the screen Android TV layouts are designed for. */
    const val STANDARD_TV_WIDTH_DP = 960

    /** Below this much wider than standard, the box is left as it is. */
    private const val TOLERANCE = 1.05

    /** The override for [base], or null when none is needed or it is turned off. */
    fun overrideConfiguration(base: Context): Configuration? {
        val enabled = base.getSharedPreferences(ExternalPlayerSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ExternalPlayerSettings.KEY_FIT_TO_TV, ExternalPlayerSettings.FIT_TO_TV_DEFAULT)
        if (!enabled) return null
        val metrics = base.resources.displayMetrics
        val target = standardDensity(metrics.widthPixels, metrics.heightPixels, base.resources.configuration.densityDpi)
            ?: return null
        return Configuration().apply {
            // A fresh Configuration says 1.0; zero leaves the user's text size alone.
            fontScale = 0f
            densityDpi = target.densityDpi
            screenWidthDp = target.widthDp
            screenHeightDp = target.heightDp
            smallestScreenWidthDp = min(target.widthDp, target.heightDp)
        }
    }

    /** The density, and the screen size in dp it gives, for a [STANDARD_TV_WIDTH_DP]-wide layout. */
    data class Target(val densityDpi: Int, val widthDp: Int, val heightDp: Int)

    /** The standard density for a screen of [widthPx] by [heightPx] now at [densityDpi]; null when that is already it. */
    fun standardDensity(widthPx: Int, heightPx: Int, densityDpi: Int): Target? {
        val longPx = max(widthPx, heightPx)
        val shortPx = min(widthPx, heightPx)
        if (shortPx <= 0 || densityDpi <= 0) return null
        if (longPx * 160.0 / densityDpi <= STANDARD_TV_WIDTH_DP * TOLERANCE) return null
        val target = (longPx * 160.0 / STANDARD_TV_WIDTH_DP).roundToInt()
        return Target(
            densityDpi = target,
            widthDp = (widthPx * 160.0 / target).roundToInt(),
            heightDp = (heightPx * 160.0 / target).roundToInt(),
        )
    }
}
