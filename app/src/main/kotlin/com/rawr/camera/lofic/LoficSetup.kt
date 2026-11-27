package com.rawr.camera.lofic

/**
 * Rawr 17U fork: the LOFIC capture setups of the Xiaomi 17 Ultra main camera (Camera2 id "2").
 *
 * Built from Anton Tretiakov's (@designation_zero) X17U LOFIC RAW v1.0 integration specification (INTEGRATION.md):
 * each setup is a fixed recipe. The session type, the exact AE target fps range and the stream list decide which
 * sensor mode the camera HAL picks; the RAW buffers are always 4096x3072 and the sensor mode decides how many rows are
 * filled. The 4:3 setups, and a usable Short stream in every setup, need his root module.
 */
internal enum class LoficSetup(
    val label: String,
    val sessionType: Int,
    val fps: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val jpegWidth: Int,
    val jpegHeight: Int,
    val sensorMode: Int,
    val rows: Int,
    val minFrameNs: Long,
    val isoMin: Int
) {
    FOUR_THREE_30("4:3 · 30 fps", 0xF010, 30, 1440, 1080, 2880, 2160, 4096, 3072, 5, 3072, 33_289_000L, 50),
    // In mode 38 the HAL raises ISO 100 to 200 (Long analog gain 4, measured on the 17U).
    FOUR_THREE_60("4:3 · 59.5 fps", 0xF010, 60, 1440, 1080, 2880, 2160, 4096, 3072, 38, 3072, 16_813_000L, 200),
    SIXTEEN_TEN_60("16:10 · 60 fps", 0x8004, 60, 1920, 1080, 3840, 2160, 3840, 2160, 27, 2560, 16_625_000L, 50);

    companion object {
        const val RAW_WIDTH = 4096
        const val RAW_HEIGHT = 3072

        /** Long: RAW14 packed, 4 pixels in 7 bytes; Short: RAW10 packed, 4 pixels in 5 bytes. */
        const val LONG_ROW_BYTES = RAW_WIDTH / 4 * 7
        const val SHORT_ROW_BYTES = RAW_WIDTH / 4 * 5
        const val LONG_BLACK = 1024
        const val LONG_WHITE = 16383
        const val SHORT_BLACK = 64
        const val SHORT_WHITE = 1023

        /** Highest analog ISO (Long analog gain 15.94); above it the HAL adds digital gain after the RAW. */
        const val ISO_MAX_ANALOG = 797

        /** Long/Short ratio with the root module: k = 212 x Long analog gain (gain = ISO / 50). */
        fun modelK(iso: Int): Double = 212.0 * minOf(iso / 50.0, 15.94)
    }
}
