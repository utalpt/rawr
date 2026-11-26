package com.rawr.camera.integration

import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SetControlSurfaceStyle
import com.rawr.camera.architecture.SyncToneControls
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.settings.model.*
import com.rawr.camera.video.VideoEncoderSetup
import com.rawr.camera.video.VideoFrameSize
import com.rawr.camera.video.VideoResolutionMode
import com.rawr.camera.video.encoderSetup
import com.rawr.camera.video.toVideoImageSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/** Projects settings onto native preview and reports initial submission to its start gate. */
internal class CaptureSettingsSync(
    private val scope: CoroutineScope,
    private val values: Flow<SettingsValues>,
    private val latestSettings: () -> SettingsValues,
    private val previewCoordinator: RawPreviewCoordinator,
    private val controller: CaptureScreenController,
    /** Mains flicker confirmed by the phone's flicker sensor; must emit at once (null = none). */
    private val sensedFlicker: Flow<FlickerFrequency?> = flowOf(null),
    /** Period of any flicker the sensor confirmed, mains or PWM, ns; must emit at once (0 = none). */
    private val sensedFlickerPeriodNs: Flow<Long> = flowOf(0L)
) {
    private val bindings = mutableListOf<suspend () -> Unit>()
    private var firstApplies = 0
    private var started = false
    private var encoderPrewarmRetry: Job? = null

    fun start() {
        check(!started)
        started = true
        // Diagnostics always record (bounded in native), so a bundle exported
        // after a problem already holds the evidence: no switch to remember.
        previewCoordinator.setPersistentDiagnosticsEnabled(true)
        previewCoordinator.setInternalTraceCaptureEnabled(true)
        syncSetting({ it.controlSurfaceStyle }) { controller.dispatch(SetControlSurfaceStyle(it)) }
        syncSetting({ it.lensBarGrouping }) { controller.dispatch(com.rawr.camera.architecture.SetLensBarGrouping(it)) }
        syncSetting({ it.lensBarSize }) { controller.dispatch(com.rawr.camera.architecture.SetLensBarSize(it)) }
        // OIS follows its setting, except while the gyro stabilisation runs (video/NativeStabilizer.kt: StabilizerState).
        syncStream(
            combine(values.map { it.oisEnabledPreference }, com.rawr.camera.video.StabilizerState.active) { ois, stab ->
                ois && !stab
            }.distinctUntilChanged()
        ) { previewCoordinator.setOisEnabled(it) }
        syncSetting({ it.lensProfiles }) { previewCoordinator.setLensProfiles(it) }
        // Start on the last used lens. Startup only: later values come from
        // the user's own switches, and replaying one could undo a newer switch.
        // Native falls back to the first lens if this one left the profile.
        syncStream(values.map { it.lastLensId }.take(1)) { id -> id?.let(previewCoordinator::selectLens) }
        // Auto follows the flicker sensor when the phone has one (see antibandingModeFor).
        syncStream(
            combine(values.map { it.antiFlicker }.distinctUntilChanged(), sensedFlicker) { setting, flicker ->
                com.rawr.camera.settings.model.antibandingModeFor(setting, flicker?.hz)
            }.distinctUntilChanged()
        ) { previewCoordinator.setAntibandingMode(it) }
        // rawr AE keeps its shutter in step with it under Auto, PWM LEDs included (native FlickerPeriod.h).
        syncStream(sensedFlickerPeriodNs) { previewCoordinator.setSensedFlickerPeriodNs(it) }
        syncSetting({ it.highlightProtection }) {
            previewCoordinator.setPostGainKneeWidthEv(com.rawr.camera.settings.model.postGainKneeWidthEvFor(it))
        }
        syncSetting({ if (it.isVideo) it.videoAutoKeyExposure else it.autoKeyExposure }) {
            previewCoordinator.setAutoKeyMode(it.nativeId)
        }
        // Photo local tone mapping; video always takes the adaptive curve.
        syncSetting({
            (if (!it.isVideo && it.photoToneMapping == PhotoToneMapping.Local) it.localToneShadowsEv else 0f) to it.localToneTuning
        }) { (shadows, tuning) -> previewCoordinator.setLocalTone(shadows, tuning) }
        // The next photo's merged frame count: the viewfinder's noise limit
        // on the local tone lift matches the still's.
        syncSetting({
            if (!it.isVideo && it.experimentalMultiframeEnabled)
                it.activeMultiframeTuning.maxFrames.coerceIn(1, 30)
            else 1
        }) { previewCoordinator.setExpectedMergeFrames(it) }
        syncSetting({ it.maxPostGainId }) {
            previewCoordinator.setMaxAePostGain(com.rawr.camera.settings.model.maxPostGainForId(it))
        }
        syncSetting({ it.aePriorityDisabled }) { previewCoordinator.setAePriorityDisabled(it) }
        syncSetting({ it.rawrAe }) { previewCoordinator.setRawrAutoExposure(it) }
        syncSetting({ it.autoMinFpsId }) {
            previewCoordinator.setAutoMinFps(com.rawr.camera.settings.model.autoMinFpsForId(it))
        }
        syncSetting({ it.persistZslRingEnabled }) { previewCoordinator.setPersistZslRingEnabled(it) }
        syncSetting({ it.photoLensShadingEnabled }) { previewCoordinator.setLensShadingCorrectionEnabled(it) }
        // The viewfinder frames what the current mode saves.
        syncSetting({ if (it.isVideo) it.videoDistortionCorrectionEnabled else it.distortionCorrectionEnabled }) {
            previewCoordinator.setDistortionCorrectionEnabled(it)
        }
        // Idle video uses RawPreview until recording supplies the monitor
        // image. Match its recovery switch to the recorder, including when
        // entering/leaving Video; photo recovery can alter LOG highlights.
        syncSetting({ if (it.isVideo) it.videoHighlightEnabled else it.photoHighlightEnabled }) {
            previewCoordinator.setHighlightReconstructionEnabled(it)
        }
        syncSetting({ it.toVideoImageSettings() }) {
            previewCoordinator.setVideoImageSettings(it)
            // FCC and defringe are part of the prewarmed pipeline.
            prewarmVideo()
        }
        syncSetting({ it.effectiveVideoBitDepth() }) { prewarmVideo() }
        syncSetting({ it.experimentalZeroCopyEnabled }) { previewCoordinator.setExperimentalZeroCopy(it) }
        syncSetting({ it.experimentalMultiframeEnabled }) { previewCoordinator.setExperimentalMultiframeEnabled(it) }
        syncSetting({ it.persistentEngineEnabled }) { previewCoordinator.setPersistentEngineEnabled(it) }
        // Keyed on profile identity + LUT structure (tone normalized out):
        // native profile switches rebuild the tonemap engine, so tone-only
        // edits must not retrigger them (tone flows via setTonemapParameters).
        syncSetting({ it.renderProfileSyncKey() }) { (profile, id, _) ->
            previewCoordinator.setColorRenderProfile(profile, id)
        }
        // Film simulation is a photo look; the Video viewfinder must not apply it.
        syncSetting({ it.filmSimEnabled && !it.isVideo }) { previewCoordinator.setFilmSim(it) }
        syncSetting({ it.viewfinderDivisor }) { previewCoordinator.setViewfinderDivisor(it) }
        syncSetting({ it.filmSimLook }) { look ->
            previewCoordinator.setFilmSimLook(look.toFloatArray(), look.toIntArray())
        }
        syncSetting({ it.peakingSensitivityId }) { id ->
            val sensitivity =
                when (id) {
                    "peaking.low" -> 0.25f
                    "peaking.high" -> 0.75f
                    else -> 0.50f
                }
            previewCoordinator.setFocusPeakingSensitivity(sensitivity)
        }
        syncStream(LiveTonemapState.values.filterNotNull()) { applyTonemapState(it) }
        syncStream(LiveTonemapState.agx.filterNotNull()) { previewCoordinator.setAgxParameters(it) }
        firstApplies = bindings.size
        bindings.forEach { collect -> scope.launch { collect() } }
    }

    private fun <T> syncSetting(select: (SettingsValues) -> T, apply: suspend (T) -> Unit) {
        syncStream(values.map(select).distinctUntilChanged(), apply)
    }

    private fun <T> syncStream(source: Flow<T>, apply: suspend (T) -> Unit) {
        bindings += {
            var first = true
            source.collect { value ->
                apply(value)
                if (first) {
                    first = false
                    firstApplies--
                    if (firstApplies == 0) previewCoordinator.setInitialConfigReady()
                }
            }
        }
    }

    /**
     * Arms/clears the native idle-preview crop to the exact record window.
     * 16:9 outputs crop the open-gate preview to the centered record rect;
     * Open Gate and Photo show the full frame (clear). Output sizes are
     * sensor-landscape, matching the recorder's frame sizes.
     */
    fun updatePreviewCrop(mode: CaptureMode, resolution: VideoResolutionMode) {
        // The recorder's frame (VideoCropRequest); native resolves Open Gate and applies the
        // aspect and halving with the same rule, for the viewfinder and the prewarm.
        val state = controller.state.value
        val photo = state.photoAspect
        val photoCrop = mode == CaptureMode.Photo && photo != com.rawr.camera.model.PhotoAspect.Native
        // Photo: the JPEG crop goes native at once; the viewfinder shows the same frame
        // (the image area at that aspect, as the video Open Gate crop does).
        previewCoordinator.setPhotoAspect(if (photoCrop) photo.num else 0, if (photoCrop) photo.den else 0)
        val request = when {
            photoCrop -> com.rawr.camera.video.VideoCropRequest(0, 0, photo.num, photo.den, false)
            mode != CaptureMode.Video -> null
            else -> com.rawr.camera.video.videoCropRequest(resolution, state.videoAspect)
        }
        if (request == null) previewCoordinator.clearVideoPreviewCrop()
        else previewCoordinator.setVideoPreviewCropOut(request.width, request.height, request.num, request.den,
            request.halve)
        prewarmVideo(mode, resolution)
    }

    /** Builds (Video) or frees (Photo) the recording pipeline so recording starts at once. */
    fun prewarmVideo(
        mode: CaptureMode = controller.state.value.captureMode,
        resolution: VideoResolutionMode = controller.state.value.videoResolution
    ) {
        if (mode != CaptureMode.Video) {
            // Leaving Video releases the pool: a pending wait for the snapshot must not refill it afterwards.
            encoderPrewarmRetry?.cancel()
            encoderPrewarmRetry = null
            previewCoordinator.releaseVideoProcessing()
            return
        }
        val (width, height) = when (resolution) {
            VideoResolutionMode.HD1080 -> 1920 to 1080
            VideoResolutionMode.UHD4K -> 3840 to 2160
            VideoResolutionMode.OPEN_GATE -> 0 to 0
        }
        previewCoordinator.prewarmVideo(width, height, latestSettings().effectiveVideoBitDepth())
        prewarmVideoEncoder(resolution)
    }

    /**
     * Prepares the next recording's HEVC encoder with the same spec the recording will
     * build. The record window comes from the camera geometry and changes with lens and
     * aspect, so it is resolved exactly as the recording does; when the snapshot is not
     * available yet the prewarm falls back to the nominal size and lets the recorder
     * report the real error.
     *
     * Open Gate records the image area, so without the RAW geometry there is nothing exact
     * to prepare. A session that boots straight into Video has not published it yet, and
     * the first take would pay the full setup: the prewarm waits for the snapshot instead,
     * polling it for a few seconds, and gives up once Video is left.
     */
    private fun prewarmVideoEncoder(resolution: VideoResolutionMode) {
        encoderPrewarmRetry?.cancel()
        encoderPrewarmRetry = null
        val setup = encoderSetup(resolution)
        if (setup != null) {
            previewCoordinator.prewarmVideoEncoder(setup)
            return
        }
        encoderPrewarmRetry = scope.launch {
            repeat(ENCODER_PREWARM_RETRY_ATTEMPTS) {
                delay(ENCODER_PREWARM_RETRY_DELAY_MS)
                val state = controller.state.value
                if (state.captureMode != CaptureMode.Video) return@launch
                val setup = encoderSetup(state.videoResolution) ?: return@repeat
                previewCoordinator.prewarmVideoEncoder(setup)
                return@launch
            }
        }
    }

    /** The encoder spec the next take will build, or null when the snapshot lacks the window. */
    private fun encoderSetup(resolution: VideoResolutionMode): VideoEncoderSetup? {
        val state = controller.state.value
        val settings = latestSettings()
        val frameSize = runCatching {
            resolution.resolve(org.json.JSONObject(previewCoordinator.cameraControlSnapshot()), state.videoAspect)
        }.getOrNull() ?: when (resolution) {
            VideoResolutionMode.HD1080 -> VideoFrameSize(1920, 1080)
            VideoResolutionMode.UHD4K -> VideoFrameSize(3840, 2160)
            // Open Gate records the image area: without the RAW geometry there is nothing to prepare.
            VideoResolutionMode.OPEN_GATE -> return null
        }
        return settings.videoEncoder.sanitized().encoderSetup(
            resolution, frameSize, state.videoFps, settings.effectiveVideoBitDepth())
    }

    private fun applyTonemapState(tone: com.rawr.camera.model.ImageToneState) {
        runCatching {
            previewCoordinator.setTonemapParameters(tone)
        }.onFailure {
            // A native hiccup must never cancel the LiveTonemapState collector,
            // or settings -> capture tone sync would die for the rest of the session.
        }
        controller.dispatch(SyncToneControls.fromImageTone(tone))
    }

    private companion object {
        /** Poll cadence while the RAW geometry is still missing from the snapshot. */
        const val ENCODER_PREWARM_RETRY_DELAY_MS = 250L
        /** About 6 s: a cold session publishes the snapshot well within it. */
        const val ENCODER_PREWARM_RETRY_ATTEMPTS = 24
    }
}
