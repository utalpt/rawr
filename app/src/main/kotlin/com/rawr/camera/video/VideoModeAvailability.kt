package com.rawr.camera.video

import com.rawr.camera.architecture.VIDEO_FPS_CHOICES
import com.rawr.camera.model.CaptureUiState

/** Capabilities of the current RAW stream, not of the encoder in isolation. */
data class VideoModeAvailability(val geometry: VideoGeometry, val fixedRates: Set<Int>) {
    fun supports(resolution: VideoResolutionMode, fps: Int, aspect: VideoAspect): Boolean =
        fps in fixedRates && runCatching { resolution.resolve(geometry, aspect, fps > 30) }.isSuccess

    fun rates(resolution: VideoResolutionMode, aspect: VideoAspect): List<Int> =
        VIDEO_FPS_CHOICES.filter { supports(resolution, it, aspect) }

    fun resolutions(fps: Int, aspect: VideoAspect): List<VideoResolutionMode> =
        VideoResolutionMode.entries.filter { supports(it, fps, aspect) }

    fun reconcile(state: CaptureUiState): CaptureUiState {
        if (supports(state.videoResolution, state.videoFps, state.videoAspect)) return state
        val preferredRates = listOf(state.videoFps, 30, 24, 60).distinct()
        val sameResolution = listOf(30, 24, 60).firstOrNull {
            supports(state.videoResolution, it, state.videoAspect)
        }
        if (sameResolution != null) return state.copy(videoFps = sameResolution)
        for (mode in VideoResolutionMode.entries) {
            val fps = preferredRates.firstOrNull { supports(mode, it, state.videoAspect) } ?: continue
            return state.copy(videoResolution = mode, videoFps = fps)
        }
        return state
    }
}
