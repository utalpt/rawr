package com.rawr.camera.lofic

/**
 * The "1x LOFIC" lens's photo hand-off (native imaging/LoficGrab): the shutter asks for the next Long/Short pair of
 * one frame and waits for it. Xiaomi 17 Ultra, from Anton Tretiakov's (@designation_zero) X17U LOFIC RAW v1.0 spec.
 */
internal object LoficNative {
    init { System.loadLibrary("rawrcam_native") }

    /** A LOFIC session is running. */
    external fun active(): Boolean

    external fun request()

    /** Rows the Long fills in the viewfinder, 0 until known: 3072 with the root module, 2304 without it (16:9). */
    external fun viewfinderRows(): Int

    /** [ByteArray long, ByteArray short, IntArray{width, rows, iso, matched}, LongArray{timestampNs, exposureNs},
     *  FloatArray neutral], or null when no pair came in time. */
    external fun waitPair(timeoutMs: Int): Array<Any>?
}
