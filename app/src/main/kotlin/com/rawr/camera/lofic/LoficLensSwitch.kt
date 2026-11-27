package com.rawr.camera.lofic

import android.content.Context

/**
 * Settings > Photo > Capture > LOFIC Lens: shows the 17 Ultra profile's "1x LOFIC" lens in the lens bar. Off by
 * default: the lens needs Anton Tretiakov's (@designation_zero) X17U LOFIC RAW root module.
 */
object LoficLensSwitch {
    private const val PREFS = "rawr_lofic"
    private const val KEY = "lens_enabled"

    @Volatile var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    /** A LOFIC lens of a profile ("1x LOFIC"). */
    fun isLoficLens(id: String): Boolean = id.endsWith(" LOFIC")
}
