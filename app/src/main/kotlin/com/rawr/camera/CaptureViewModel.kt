package com.rawr.camera

import com.rawr.camera.settings.model.withLensSelected
import com.rawr.camera.settings.model.MultiframeNumericParameter
import com.rawr.camera.settings.model.activeLensHasOwnLook
import com.rawr.camera.settings.model.editActiveMultiframeTuning
import com.rawr.camera.settings.model.multiframeTuningFor
import com.rawr.camera.settings.model.withOwnLookForActiveLens
import com.rawr.camera.settings.model.withMaster
import com.rawr.camera.settings.model.withVfControl
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.camera.architecture.VIDEO_FPS_CHOICES
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SyncToneControls
import com.rawr.camera.integration.DeviceLensDefaults
import com.rawr.camera.settings.model.effectiveLensProfiles
import com.rawr.camera.integration.NativeCaptureScreenController
import com.rawr.camera.integration.RawPreviewCoordinator
import com.rawr.camera.integration.StillCaptureCoordinator
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.ToneParameter
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.toFilmSimQuickState
import com.rawr.camera.model.toRenderProfileQuickState
import com.rawr.camera.model.toViewfinderQuickState
import com.rawr.camera.model.withToneParameter
import com.rawr.camera.settings.architecture.SetFilmSimDiscreteValue
import com.rawr.camera.settings.architecture.SetFilmSimFlag
import com.rawr.camera.settings.architecture.SetFilmSimNumericValue
import com.rawr.camera.settings.architecture.capturePreferences
import com.rawr.camera.settings.architecture.editActiveProfileTone
import com.rawr.camera.settings.architecture.withAgxDefault
import com.rawr.camera.settings.architecture.withAgxValue
import com.rawr.camera.settings.architecture.withCapturePreferenceChanges
import com.rawr.camera.settings.architecture.withFilmDiscrete
import com.rawr.camera.settings.architecture.withFilmFlag
import com.rawr.camera.settings.architecture.withFilmNumeric
import com.rawr.camera.settings.architecture.withFilmPreset
import com.rawr.camera.settings.model.FilmSimSpecs
import com.rawr.camera.settings.model.SettingsCatalog
import com.rawr.camera.settings.model.withRenderProfile
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.options
import com.rawr.camera.settings.model.persistedId
import com.rawr.camera.settings.model.storedValue
import com.rawr.camera.settings.preferences.SettingsPreferencesStore
import com.rawr.camera.storage.GalleryMediaStore
import com.rawr.camera.video.VideoResolutionMode
import com.rawr.camera.video.toVideoImageSettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application lifetime owner.
 *
 * Compose state is production-backed for camera/lens/exposure/focus controls.
 * Processing controls not yet integrated remain application/UI concerns.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val previewCoordinator = RawPreviewCoordinator(application)
    private val settingsDefaults = SettingsCatalog.initialState().values
    private val settingsEditor = SettingsPreferencesStore.editor(application, settingsDefaults)

    private val latestSettings: SettingsValues get() = settingsEditor.state.value
    val cameraLocation = com.rawr.camera.location.CameraLocation.get(application)
    private val levelSensor = com.rawr.camera.integration.CaptureLevelSensor(application)
    /** Rear light, colour temperature and flicker sensors, on phones that have them. */
    val auxSensors = com.rawr.camera.integration.AuxSensors(application)
    val foreground = com.rawr.camera.integration.CaptureForeground { active ->
        cameraLocation.setForeground(active)
        levelSensor.setActive(active)
        auxSensors.setActive(active)
    }
    fun videoImageSettings(): com.rawr.camera.video.VideoImageSettings = latestSettings.toVideoImageSettings()
    fun videoEncoderConfig(): com.rawr.camera.settings.model.VideoEncoderConfig = latestSettings.videoEncoder.sanitized()
    /** Microphone setup for the video-mode meter; follows Settings edits. */
    val videoAudioInput: StateFlow<com.rawr.camera.video.VideoAudioInput> = settingsEditor.state
        .map { com.rawr.camera.video.VideoAudioInput.of(it.videoEncoder.sanitized()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.rawr.camera.video.VideoAudioInput.of(latestSettings.videoEncoder.sanitized()))
    /** Settings > Photo > Capture > Voice Shutter: MainActivity starts and stops its listener from this. */
    val voiceShutterEnabled: kotlinx.coroutines.flow.Flow<Boolean> =
        settingsEditor.values.map { it.voiceShutter }.distinctUntilChanged()

    /** Settings > Photo > Capture > Watch Remote: MainActivity starts and stops the watch remote from this. */
    val watchRemoteEnabled: kotlinx.coroutines.flow.Flow<Boolean> =
        settingsEditor.values.map { it.watchRemote }.distinctUntilChanged()

    /** The microphone permission was refused: the switch goes back off. */
    fun turnVoiceShutterOff() {
        settingsEditor.update { it.copy(voiceShutter = false) }
    }

    /** Startup diagnostics use the same optimistic owner, including when preferences are still loading. */
    fun applyStartupVideoOverrides(resolution: VideoResolutionMode?, fps: Int?) {
        if (resolution == null && fps == null) return
        val next = settingsEditor.update { current -> current.copy(
            videoResolutionId = resolution?.persistedId() ?: current.videoResolutionId,
            videoFps = fps?.takeIf { it in VIDEO_FPS_CHOICES } ?: current.videoFps
        ) }
        controller.dispatch(next.capturePreferences(DeviceLensDefaults.lenses))
        settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoResolution)
    }

    private val mutableRenderProfiles = MutableStateFlow(settingsDefaults.toRenderProfileQuickState())
    val renderProfiles: StateFlow<RenderProfileQuickState> = mutableRenderProfiles.asStateFlow()
    private val mutableFilmSimQuick = MutableStateFlow(settingsDefaults.toFilmSimQuickState())
    val filmSimQuick: StateFlow<FilmSimQuickState> = mutableFilmSimQuick.asStateFlow()
    val viewfinderQuick: StateFlow<ViewfinderQuickState> = settingsEditor.state
        .map { it.toViewfinderQuickState(DeviceLensDefaults.lenses) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            latestSettings.toViewfinderQuickState(DeviceLensDefaults.lenses))
    private val galleryMediaStore = GalleryMediaStore(application)
    private val mutableLatestGalleryImage = MutableStateFlow<Uri?>(null)
    val latestGalleryImage: StateFlow<Uri?> = mutableLatestGalleryImage.asStateFlow()
    private val galleryRefreshGeneration = AtomicLong(0L)
    private val stillCaptureCoordinator =
        StillCaptureCoordinator(
            application = application,
            preview = previewCoordinator,
            settings = { latestSettings },
            onJpegPublished = ::onJpegPublished,
            onDngPublished = ::refreshLatestGalleryImage,
            gpsSnapshot = cameraLocation::snapshot,
            levelSnapshot = levelSensor::snapshot,
            ambientSnapshot = auxSensors::snapshot
        )
    val controller: CaptureScreenController =
        NativeCaptureScreenController(
            previewCoordinator,
            onCaptureQueued = stillCaptureCoordinator::enqueueCapture,
            requestStillCapture = stillCaptureCoordinator::capture,
            foregroundToken = foreground::token,
            pollStillCompletion = stillCaptureCoordinator::pollCompletion,
            onToneScrubbed = { parameter, value -> persistQuickTone(parameter, value) },
            onRenderExposureScrubbed = ::persistRenderExposure,
            onAgxControl = ::persistAgxControl,
            videoLocked = { videoSelectionLocked() },
            onRawCpuIngress = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        application,
                        "Camera frames use the CPU copy path on this device (slower, more battery)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onLensSelected = { lensId -> settingsEditor.update { it.withLensSelected(lensId) } },
            onCapturePreferencesChanged = { before, after ->
                settingsEditor.update { it.withCapturePreferenceChanges(before, after) }
                settingsSync.updatePreviewCrop(after.captureMode, after.videoResolution)
            }
        )

    private val settingsSync by lazy {
        com.rawr.camera.integration.CaptureSettingsSync(
            viewModelScope, settingsEditor.values, { latestSettings }, previewCoordinator, controller,
            sensedFlicker = auxSensors.flickerFrequency,
            sensedFlickerPeriodNs = auxSensors.flickerPeriodNs
        )
    }

    var recordingOptions = com.rawr.camera.video.RecordingOptions()
    private val recordingBackend = com.rawr.camera.video.CaptureRecordingBackend(
        application, controller, previewCoordinator, { latestSettings }
    )
    val recording = com.rawr.camera.video.RecordingCoordinator(
        controller = controller,
        startRecorder = { gps -> recordingBackend.start(recordingOptions, gps) },
        clockNs = android.os.SystemClock::elapsedRealtimeNanos,
        foregroundToken = foreground::token,
        validateAccess = cameraLocation::validateAccess,
        admitGps = { if (latestSettings.locationTagging) cameraLocation.snapshot() else null },
        onSaved = { recorder ->
            if (recordingOptions.inspectOutput) {
                recordingOptions = recordingOptions.copy(inspectOutput = false)
                recordingBackend.inspectSaved(recorder)
            }
        }
    )
    private var captureVisible = false
    private var previewStopJob: kotlinx.coroutines.Job? = null

    fun startPreview() {
        captureVisible = true
        viewModelScope.launch {
            previewStopJob?.join()
            if (captureVisible) previewCoordinator.start()
        }
    }

    fun setUiPresentationActive(active: Boolean) = previewCoordinator.setUiPresentationActive(active)

    fun stopPreviewAfterRecording() {
        captureVisible = false
        val stopping = recording.stop()
        previewStopJob = viewModelScope.launch {
            stopping.join()
            if (!captureVisible) previewCoordinator.stop()
        }
    }

    init {
        refreshLatestGalleryImage()
        // Rawr 17U fork: the camera sounds (CameraSounds.kt): loaded once the setting is on, and the stop tone when a
        // recording ends. The start tone plays in NativeVideoRecorder.start, before the microphone opens.
        viewModelScope.launch {
            // Rawr 17U fork: the TELE control's look, for the capture UI.
            launch {
                settingsEditor.values.map { it.teleZoomStyle }.distinctUntilChanged().collect {
                    com.rawr.camera.ui.TeleZoomSheetState.style = com.rawr.camera.model.TeleZoomStyle.fromId(it)
                }
            }
            launch {
                settingsEditor.values.map { it.teleZoomAlwaysOn }.distinctUntilChanged().collect {
                    com.rawr.camera.ui.TeleZoomSheetState.alwaysOn = it
                }
            }
            launch {
                settingsEditor.values.map { it.shutterSoundFollowSystem }.distinctUntilChanged().collect {
                    com.rawr.camera.integration.CameraSounds.followSystem = it
                }
            }
            settingsEditor.values.map { it.shutterSound }.distinctUntilChanged().collect {
                com.rawr.camera.integration.CameraSounds.prepare(it)
            }
        }
        viewModelScope.launch {
            var last = recording.state.value.phase
            recording.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                val on = latestSettings.shutterSound
                if (last == com.rawr.camera.video.RecordingPhase.Recording && phase != com.rawr.camera.video.RecordingPhase.Recording)
                    com.rawr.camera.integration.CameraSounds.videoStop(on)
                last = phase
            }
        }
        viewModelScope.launch { recordingBackend.recoverInterrupted() }
        // The light meter and capture metadata read the front light sensor while a front camera is in use.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                controller.state.map { it.selectedLensId }.distinctUntilChanged(),
                settingsEditor.state.map { it.effectiveLensProfiles(DeviceLensDefaults.lenses) }.distinctUntilChanged()
            ) { lensId, profiles -> profiles.firstOrNull { it.name == lensId }?.camera?.id }
                .distinctUntilChanged()
                .collect { cameraId ->
                    val front = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cameraId != null && com.rawr.camera.integration.isFrontCamera(getApplication(), cameraId)
                    }
                    auxSensors.setFrontCamera(front)
                }
        }
        viewModelScope.launch {
            settingsEditor.values.map { it.capturePreferences(DeviceLensDefaults.lenses) }.distinctUntilChanged().collect { preferences ->
                controller.dispatch(preferences)
                settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoResolution)
            }
        }
        viewModelScope.launch {
            // The aspect picked in the top bar: viewfinder crop and prewarm follow it
            // (never mid-recording; the picker is locked then anyway).
            controller.state.map { it.videoAspect to it.photoAspect }.distinctUntilChanged().collect {
                if (!videoSelectionLocked())
                    settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoResolution)
            }
        }
        viewModelScope.launch {
            // Rawr 17U fork: the tele's optical zoom (75 to 100 mm) to the camera. The native side keeps it across lens
            // changes and applies it only on lenses that carry the zoom key (the 4.3x lenses).
            controller.state.map { it.teleZoomMm }.distinctUntilChanged().collect { mm ->
                previewCoordinator.setOpticalZoomCode(com.rawr.camera.model.TeleOpticalZoom.code(mm))
            }
        }
        viewModelScope.launch {
            // Rawr 17U fork: one tele button. A fixed 75 mm lens (3.2x) that has a twin which moves the optics (4.3x)
            // is not in the lens bar; a saved or restored selection of it goes to the twin at 75 mm.
            controller.state.map { it.selectedLensId to it.capabilities.lenses }.distinctUntilChanged().collect { (id, lenses) ->
                val lens = lenses.firstOrNull { it.id == id } ?: return@collect
                val twin = com.rawr.camera.model.TeleOpticalZoom.twinOf(lens, lenses) ?: return@collect
                controller.dispatch(com.rawr.camera.architecture.SetTeleZoomMm(com.rawr.camera.model.TeleOpticalZoom.MIN_MM))
                controller.dispatch(com.rawr.camera.architecture.SelectLens(twin.id))
            }
        }
        viewModelScope.launch {
            settingsEditor.values.map { it.toRenderProfileQuickState() }.distinctUntilChanged().collect {
                mutableRenderProfiles.value = it
            }
        }
        viewModelScope.launch {
            settingsEditor.values.distinctUntilChanged { before, after ->
                before.selectedFilmPresetId == after.selectedFilmPresetId &&
                    before.filmSimLook == after.filmSimLook && before.filmPresets == after.filmPresets
            }.collect { mutableFilmSimQuick.value = it.toFilmSimQuickState() }
        }
        settingsSync.start()
    }

    fun refreshLatestGalleryImage() {
        val generation = galleryRefreshGeneration.incrementAndGet()
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) { galleryMediaStore.findLatestRawrImage() }
            if (galleryRefreshGeneration.get() == generation) mutableLatestGalleryImage.value = latest
        }
    }

    private fun onJpegPublished(uri: Uri) {
        galleryMediaStore.rememberPublishedJpeg(uri)
        galleryRefreshGeneration.incrementAndGet()
        mutableLatestGalleryImage.value = uri
    }

    private fun videoSelectionLocked(): Boolean = recording.state.value.let { it.busy || it.recording }

    fun selectRenderProfileFromCapture(selection: com.rawr.camera.model.RenderProfileSelection) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        val next = settingsEditor.update { it.withRenderProfile(selection) }
        mutableRenderProfiles.value = next.toRenderProfileQuickState()
        previewCoordinator.setColorRenderProfile(next.effectiveRenderProfileId(), next.regularLutProfileId())
        controller.dispatch(SyncToneControls.fromImageTone(next.activeImageTone()))
    }

    fun onFilmEvent(event: com.rawr.camera.architecture.CaptureFilmEvent) {
        if (event is com.rawr.camera.architecture.CaptureFilmEvent.HoldLook) {
            // Rawr 17U fork: preview only - the held card renders the second half of the viewfinder (split
            // compare), the settings and the first half stay as they are; null ends the compare. Halation cards
            // cover the whole viewfinder (mode 1) and grain cards show the grain loupe (mode 2, the middle of the
            // frame enlarged so the grain is rendered at about the photo's pixel size).
            val current = latestSettings.filmSimLook
            val look = event.edit?.invoke(current)
            android.util.Log.i("RawrFilmSheet", if (look == null) "HOLD_LOOK end" else
                "HOLD_LOOK film=${look.film} paper=${look.paper} process=${look.process} mode=${event.mode} current=${current.film}")
            previewCoordinator.setFilmCompareLook(look?.toFloatArray(), look?.toIntArray(), event.mode)
            return
        }
        val next = settingsEditor.update { current ->
            when (event) {
                is com.rawr.camera.architecture.CaptureFilmEvent.HoldLook -> current
                is com.rawr.camera.architecture.CaptureFilmEvent.ApplyLook -> current.copy(filmSimLook = event.edit(current.filmSimLook))
                is com.rawr.camera.architecture.CaptureFilmEvent.SelectPreset -> current.withFilmPreset(event.id)
                is com.rawr.camera.architecture.CaptureFilmEvent.ImportPreset -> {
                    // Rawr 17U fork: the photo's look as a new saved preset, put in use straight away. A look that a
                    // preset already has (the same photo imported again) is not saved twice: that preset is used.
                    val same = (com.rawr.camera.settings.model.FilmFactoryPresets.all + current.filmPresets)
                        .firstOrNull { it.look == event.look }
                    if (same != null) current.withFilmPreset(same.id) else {
                        val id = com.rawr.camera.settings.architecture.newPresetId(current.filmPresets.map { it.id })
                        val name = event.name.trim().take(48).ifEmpty { "Photo look" }
                        current.copy(filmPresets = current.filmPresets +
                            com.rawr.camera.settings.model.FilmPreset(id, name, event.look)).withFilmPreset(id)
                    }
                }
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, event.value), quantize = true, enableGrain = true
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ResetNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, FilmSimSpecs.forParameter(event.parameter).defaultValue)
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubDiscrete -> {
                    if (event.index !in event.field.options().indices) current else current.withFilmDiscrete(
                        SetFilmSimDiscreteValue(event.field, event.field.storedValue(event.index))
                    )
                }
                is com.rawr.camera.architecture.CaptureFilmEvent.SetFlag -> current.withFilmFlag(
                    SetFilmSimFlag(event.flag, event.enabled)
                )
            }
        }
        mutableFilmSimQuick.value = next.toFilmSimQuickState()
        previewCoordinator.setFilmSimLook(next.filmSimLook.toFloatArray(), next.filmSimLook.toIntArray())
    }

    @Volatile private var lastControlsBeforeReset: String? = null

    /** Rail edits go through settings; CaptureSettingsSync projects them onto the preview and stills. */
    fun onQuickEvent(event: CaptureQuickEvent) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        // Camera control memory lives in native, not in settings.
        when (event) {
            is CaptureQuickEvent.ResetCameraControls -> {
                previewCoordinator.resetCameraControls(event.allCameras) { lastControlsBeforeReset = it }
                return
            }
            CaptureQuickEvent.UndoCameraControlsReset -> {
                lastControlsBeforeReset?.let(previewCoordinator::undoCameraControlsReset)
                lastControlsBeforeReset = null
                return
            }
            else -> Unit
        }
        settingsEditor.update { c ->
            val video = c.isVideo
            when (event) {
                is CaptureQuickEvent.SelectDcp -> if (controller.state.value.selectedLensId != event.lensId) c else
                    c.copy(lensProfiles = c.effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                        if (lens.name == event.lensId) lens.image { selectDcp(event.file) } else lens
                    })
                CaptureQuickEvent.ToggleRawrAe -> c.copy(rawrAe = c.rawrAe.copy(enabled = !c.rawrAe.enabled))
                is CaptureQuickEvent.SetMetering -> c.copy(rawrAe = c.rawrAe.copy(metering = event.metering))
                is CaptureQuickEvent.SetHighlightMode -> {
                    val enabled = event.mode != HighlightQuickMode.Off
                    val method = when (event.mode) {
                        HighlightQuickMode.Off -> if (video) c.videoHighlightMethod else c.photoHighlightMethod
                        HighlightQuickMode.Propagation -> 0
                        HighlightQuickMode.Opposed -> 1
                    }
                    if (video) c.copy(videoHighlightEnabled = enabled, videoHighlightMethod = method)
                    else c.copy(photoHighlightEnabled = enabled, photoHighlightMethod = method)
                }
                is CaptureQuickEvent.SetHighlightThreshold -> event.value.coerceIn(0.5f, 2f).let {
                    if (video) c.copy(videoHighlightThreshold = it) else c.copy(photoHighlightThreshold = it)
                }
                is CaptureQuickEvent.SetHighlightCompression -> event.value.coerceIn(0f, 300f).let {
                    if (video) c.copy(videoHighlightCompression = it) else c.copy(photoHighlightCompression = it)
                }
                is CaptureQuickEvent.SetMultiframeFrames -> c.editActiveMultiframeTuning {
                    it.withValue(MultiframeNumericParameter.MaxFrames, event.frames.coerceIn(1, 30).toFloat())
                }
                CaptureQuickEvent.ToggleOwnMultiframe -> c.lastLensId?.let { lens ->
                    if (lens in c.multiframeTuningByLens) c.copy(multiframeTuningByLens = c.multiframeTuningByLens - lens)
                    else c.copy(multiframeTuningByLens = c.multiframeTuningByLens + (lens to c.multiframeTuningFor(lens)))
                } ?: c
                CaptureQuickEvent.ToggleOwnLook -> c.withOwnLookForActiveLens(!c.activeLensHasOwnLook)
                is CaptureQuickEvent.SetMaxShutter -> c.copy(rawrAe = c.rawrAe.copy(maxShutter = event.value))
                is CaptureQuickEvent.SetSharpening ->
                    c.copy(photoCaptureSharpeningEnabled = event.enabled, photoCaptureSharpeningLevel = event.level)
                is CaptureQuickEvent.ResetCameraControls, CaptureQuickEvent.UndoCameraControlsReset -> c
                is CaptureQuickEvent.SetVfControlVisible -> c.withVfControl(event.control, event.visible)
                CaptureQuickEvent.ShowAllVfControls -> c.copy(vfShowLook = true, vfShowExposure = true, vfShowInfoBar = true)
                is CaptureQuickEvent.SetLensBarGrouping -> c.copy(lensBarGrouping = event.value)
                is CaptureQuickEvent.SetLensBarSize -> c.copy(lensBarSize = event.value)
                CaptureQuickEvent.ToggleDenoise ->
                    if (video) c.copy(videoDenoiseEnabled = !c.videoDenoiseEnabled)
                    else c.copy(photoDenoise = c.photoDenoise.withMaster(
                        c.photoDenoise !is com.rawr.camera.settings.model.DenoiseConfig.Wavelet))
            }
        }
    }

    private fun persistQuickTone(parameter: ToneParameter, value: Int) {
        settingsEditor.update { current ->
            current.editActiveProfileTone { it.withToneParameter(parameter, value.coerceIn(-100, 100).toFloat()) }
        }
    }

    private fun persistAgxControl(parameter: com.rawr.camera.settings.model.AgxParameter, value: Float?) {
        settingsEditor.update { current ->
            if (value == null) current.withAgxDefault(parameter) else current.withAgxValue(parameter, value)
        }
    }

    private fun persistRenderExposure(tenths: Int) {
        val ev = com.rawr.camera.model.TonemapControlContract.exposureEvFromTenths(tenths)
        settingsEditor.update { current -> current.editActiveProfileTone { it.copy(renderExposure = ev) } }
    }

    /** Diagnostic camera-ID override; native routing remains authoritative. */

    fun selectCameraIdFromIntent(cameraId: String) {
        previewCoordinator.setPreferredCameraId(cameraId)
    }

    override fun onCleared() {
        levelSensor.setActive(false)
        auxSensors.setActive(false)
        recording.closeWhenIdle {
            stillCaptureCoordinator.closeWhenIdle {
                controller.close()
                previewCoordinator.close()
            }
        }
    }
}
