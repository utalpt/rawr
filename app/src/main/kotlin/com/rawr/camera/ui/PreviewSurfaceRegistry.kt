package com.rawr.camera.ui

import android.view.SurfaceView
import java.lang.ref.WeakReference

/** Rawr 17U fork: the viewfinder's SurfaceView, for the watch remote's PixelCopy (integration/WatchRemote.kt). */
internal object PreviewSurfaceRegistry {
    private var ref: WeakReference<SurfaceView>? = null

    @Synchronized fun set(view: SurfaceView) {
        ref = WeakReference(view)
    }

    @Synchronized fun clear(view: SurfaceView) {
        if (ref?.get() === view) ref = null
    }

    @Synchronized fun current(): SurfaceView? = ref?.get()
}
