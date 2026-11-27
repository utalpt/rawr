package com.rawr.camera.lofic

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Photo > Capture > LOFIC Lens: shows the 17 Ultra profile's "1x LOFIC" lens in the lens bar. Off by
 * default: the lens needs Anton Tretiakov's (@designation_zero) X17U LOFIC RAW root module.
 */
object LoficLensSwitch {
    private const val PREFS = "rawr_lofic"
    private const val KEY = "lens_enabled"

    // Snapshot state: the lens bar and the settings row redraw when it changes.
    private val state = mutableStateOf(false)
    val enabled: Boolean get() = state.value

    fun load(context: Context) {
        state.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        state.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    /** A LOFIC lens of a profile ("1x LOFIC"). */
    fun isLoficLens(id: String): Boolean = id.endsWith(" LOFIC")
}
