package com.rawr.camera.lofic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.rawr.camera.renderer.RendererStore
import com.rawr.camera.settings.model.SettingsCatalog
import com.rawr.camera.settings.preferences.SettingsPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rawr 17U fork: the LOFIC photos' JPEGs, one after the other. The Renderer's bulk render takes one batch at a time
 * (about 10 s for a LOFIC DNG), so a photo taken while it was busy got no JPEG ("skipped (Renderer busy)"). Each DNG
 * now waits here for the Renderer to be free, from the 1x LOFIC lens and from the LOFIC Camera screen alike.
 */
internal object LoficJpegQueue {
    private const val TAG = "RawrLofic"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Pair<Uri, String>>(Channel.UNLIMITED)
    @Volatile private var started = false
    private val pending = AtomicInteger(0)

    /** Queues the DNG's JPEG; returns what the log shows ("queued", or "queued (N ahead)"). */
    fun add(context: Context, uri: Uri, name: String): String {
        start(context.applicationContext)
        val ahead = pending.getAndIncrement() + if (RendererStore.get(context).bulk.value.running) 1 else 0
        queue.trySend(uri to name)
        return if (ahead > 0) "queued ($ahead ahead)" else "queued"
    }

    @Synchronized
    private fun start(app: Context) {
        if (started) return
        started = true
        scope.launch {
            val store = RendererStore.get(app)
            val settings = SettingsPreferencesStore.editor(app, SettingsCatalog.initialState().values)
            for ((uri, name) in queue) {
                try {
                    // The user's own Renderer batch can start between the wait and the call: renderAll then does
                    // nothing, so wait again until this DNG is the one being rendered.
                    while (true) {
                        store.bulk.first { !it.running }
                        store.renderAll(listOf(uri to name)) { settings.values.first() }
                        if (store.bulk.value.current == name) break
                    }
                    Log.i(TAG, "LOFIC_JPEG started name=$name")
                } catch (e: Exception) {
                    Log.w(TAG, "LOFIC_JPEG failed name=$name", e)
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
    }
}
