package com.rawr.camera.video

import com.rawr.camera.settings.model.VideoEncoderConfig

/**
 * The exact HEVC input a recording configures. The recorder and the encoder prewarm
 * derive it through [encoderSetup], or a prepared codec could never be reused.
 */
data class VideoEncoderSetup(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val intraFrames: Int,
    val bitrateMode: Int,
    val maxBFrames: Int,
    val bitDepth: Int
)

/**
 * Settings -> Video Encoder resolved for one record window.
 *
 * [overrides] are the diagnostic recording extras: a scripted run changes the encoder
 * input, so it keeps paying the normal startup cost instead of reusing a prepared codec
 * (the prewarm never guesses them).
 */
internal fun VideoEncoderConfig.encoderSetup(
    resolution: VideoResolutionMode,
    frameSize: VideoFrameSize,
    fps: Int,
    bitDepth: Int,
    overrides: Map<String, Int> = emptyMap()
): VideoEncoderSetup {
    fun overridden(name: String, value: Int) = overrides[name] ?: value
    return VideoEncoderSetup(
        width = frameSize.width,
        height = frameSize.height,
        fps = fps,
        // Never ask for more than the encoder advertises.
        bitrate = overridden("raw_video_bitrate",
            minOf(bitrateMbps(resolution), VideoEncoderCapabilities.maxBitrateMbps) * 1_000_000),
        intraFrames = overridden("raw_video_intra_frames", keyframeInterval.frames(fps)),
        bitrateMode = overridden("raw_video_bitrate_mode", bitrateMode.wireValue),
        maxBFrames = overridden("raw_video_max_b_frames", maxBFrames),
        bitDepth = bitDepth
    )
}
