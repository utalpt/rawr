package com.rawr.camera.lofic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.delay

/**
 * Rawr 17U fork: Anton Tretiakov's (@designation_zero) X17U LOFIC RAW root module and his LOFIC RAW app, seen from
 * Rawr. Without the module the HAL still opens the "1x LOFIC" session, but with its 16:9 LOFIC readout (2304 rows,
 * no real Short gain): the viewfinder looks normal and only the shutter used to say so. Now the lens says it once,
 * on opening.
 */
object LoficModule {
    /** Anton's LOFIC RAW demo app (LOFIC_RAW_1.0.apk), shipped next to the module. */
    const val APP_PACKAGE = "app.loficraw"
    /** Where the module and the app are posted (@XiaomiUltraCam). */
    const val DOWNLOAD_URL = "https://t.me/XiaomiUltraCam/132174"

    /** Opens Anton's app, or the module's post when it is not installed. */
    fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Toast.makeText(context, "LOFIC RAW app not installed: it comes with the X17U LOFIC RAW module", Toast.LENGTH_LONG)
            .show()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun appInstalled(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE) != null

    /**
     * While the camera screen is resumed: once per "1x LOFIC" session, warns when the viewfinder's Long fills fewer
     * rows than the full 4:3 readout (the module is not active).
     */
    suspend fun watch(context: Context) {
        var warned = false
        while (true) {
            delay(1000)
            if (!LoficNative.active()) {
                warned = false
                continue
            }
            val rows = LoficNative.viewfinderRows()
            if (!warned && rows in 16 until LoficSetup.RAW_HEIGHT) {
                warned = true
                Toast.makeText(
                    context,
                    "1x LOFIC: the X17U LOFIC RAW module is not active ($rows rows, 16:9 readout). LOFIC photos need " +
                        "the root module (Settings > Photo > Capture > LOFIC RAW app)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
