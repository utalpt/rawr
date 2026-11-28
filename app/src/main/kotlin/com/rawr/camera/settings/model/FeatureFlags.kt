package com.rawr.camera.settings.model

/**
 * Features that can be switched off in one place. HDR+ Bracketed (Rawr 17U fork, Steve's v26 patch; removed from Ed's main)
 * follows the build: -Prawr.hdrb=false leaves its native code out and hides it here.
 */
object FeatureFlags {
    val hdrPlusBracketed: Boolean = com.rawr.camera.BuildConfig.HDR_PLUS_BRACKETED
}
