package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.CaptureControlLayout
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.monitorProjection
import com.rawr.camera.video.VideoResolutionMode

/**
 * Single physical capture hierarchy for every device posture. The Activity remains
 * portrait-locked; orientation changes only content rotation, gesture interpretation,
 * pointer mapping, and deterministic scope slots.
 */
@Composable
internal fun PhysicalCaptureLayout(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview = CapturePreview(),
    monitor: @Composable () -> Unit = { ExposureMonitor(state.monitorProjection()) },
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    onOpenSettings: () -> Unit,
    latestImageUri: Uri?,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit = {},
    onOpenLensSettings: (String) -> Unit = {},
    modifier: Modifier,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    videoControls: VideoControlState = VideoControlState(),
    onToggleVideoRecording: () -> Unit = {}
) {
    val videoLocked = videoControls.busy || videoControls.recording
    val isVideo = state.captureMode == CaptureMode.Video
    val compactControls = isVideo || state.captureLayout == CaptureControlLayout.Compact
    // 16:9 viewfinders run past the photo frame and behind the shutter
    // cluster; Open Gate is exactly the photo box, so nothing bleeds.
    val videoBleed = isVideo && state.videoResolution != VideoResolutionMode.OPEN_GATE
    // Photo 3:2 and 16:9 frames are taller than the 3:4 box held upright: they take the full width and bleed
    // behind the shutter cluster like 16:9 video, instead of a narrow box inside the photo frame. 1:1 and 5:4
    // fit the photo box; 2.39 and XPan would run past the screen and stay inside it.
    val photoBleedRatio: Float? = if (isVideo) null else when (state.photoAspect) {
        com.rawr.camera.model.PhotoAspect.ThreeTwo -> 2f / 3f
        com.rawr.camera.model.PhotoAspect.SixteenNine -> 9f / 16f
        else -> null
    }
    val bleedRatio: Float? = if (videoBleed) 9f / 16f else photoBleedRatio
    // 17U: while recording, controls that cannot change mid-record (and the
    // exposure/look overlays) step aside so the frame is clear. A tap anywhere
    // on the viewfinder brings them back for 3 s; it still reaches tap-to-focus.
    val recordingNow = isVideo && videoControls.recording
    var revealTick by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    // Rawr 17U fork: a finger on the revealed controls keeps them up (a TELE drag while recording no longer has the
    // row vanish under it); they hide 3 s after the last touch ends.
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(revealTick, touching) {
        if (revealTick > 0) {
            revealed = true
            if (touching) return@LaunchedEffect
            delay(3_000L)
            revealed = false
        }
    }
    LaunchedEffect(recordingNow) { if (!recordingNow) revealed = false }
    // Gyro stabilisation (Settings > Video Encoder > Stabilisation): the gyro runs in video mode so its smoothed path
    // is settled when recording starts; the recorder only applies it while recording.
    StabilizerEffect(isVideo)
    // 17U: optional Do Not Disturb for the length of a recording (Settings > Video).
    val dndContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(recordingNow) {
        if (recordingNow) com.rawr.camera.video.RecordingDnd.begin(dndContext)
        else com.rawr.camera.video.RecordingDnd.end(dndContext)
        onDispose { if (recordingNow) com.rawr.camera.video.RecordingDnd.end(dndContext) }
    }
    val cleanRecording = recordingNow && !revealed
    val cleanNow = androidx.compose.runtime.rememberUpdatedState(cleanRecording)
    val cleanAlpha by animateFloatAsState(if (cleanRecording) 0f else 1f, tween(150), label = "recordingClean")
    BoxWithConstraints(modifier) {
        // Reserve the shutter/record area and both monitors before sizing the
        // 3:4 viewfinder. A short display must not push the readout below nav.
        val controlsHeight = if (state.captureLayout == CaptureControlLayout.Compact) {
            CaptureDimens.CompactVideoControlsMinimumHeight
        } else {
            CaptureDimens.VideoControlsClassicHeight
        }
        val viewfinderMaxHeight = (maxHeight - CaptureDimens.TopBarHeight -
            CaptureDimens.MonitorHeight * 2 - controlsHeight - CaptureDimens.VideoMonitorBottomInset)
            .coerceAtLeast(1.dp)
        val viewfinderWidth = minOf(maxWidth, viewfinderMaxHeight * .75f)
        // The photo 3:4 frame anchors every mode: the bottom strip sits at its
        // bottom edge and the shutter cluster directly below it, so photo,
        // Open Gate, and 16:9 video share identical control geometry.
        val photoFrame = DpSize(viewfinderWidth, viewfinderWidth * 4f / 3f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (cleanRecording) {
                Spacer(Modifier.fillMaxWidth().height(CaptureDimens.TopBarHeight).background(CaptureColors.Surface))
            } else {
                Box(Modifier.graphicsLayer { alpha = cleanAlpha }) {
                    CaptureTopBar(state, dispatch, onOpenSettings, videoLocked = videoLocked,
                        vfControlsHidden = quick.anyVfControlHidden)
                }
            }
            // The info bar can be hidden (VF CONTROLS); its row keeps its height so the frame never moves.
            if (quick.showInfoBar) monitor() else Spacer(Modifier.fillMaxWidth().height(CaptureDimens.MonitorHeight))
            Box(
                Modifier.fillMaxWidth().weight(1f).pointerInput(recordingNow) {
                    if (!recordingNow) return@pointerInput
                    // While the controls are hidden, any swipe only reveals them: the
                    // gesture is consumed past touch slop so the viewfinder's
                    // quick-panel swipe never starts. A tap stays a focus tap.
                    // With the controls shown, gestures pass through untouched.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!cleanNow.value) {
                            // Rawr 17U fork: revealed - the touch keeps the controls up until it ends.
                            if (revealed) {
                                touching = true
                                revealTick++
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                }
                                touching = false
                            }
                            return@awaitEachGesture
                        }
                        val slop = viewConfiguration.touchSlop
                        var swiping = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!swiping && (change.position - down.position).getDistance() > slop) {
                                swiping = true
                                revealTick++
                            }
                            if (swiping) event.changes.forEach { it.consume() }
                            if (!change.pressed) break
                        }
                    }
                }
            ) {
                CaptureViewfinder(
                    state = state,
                    dispatch = dispatch,
                    preview = preview,
                    renderProfiles = renderProfiles.copy(locked = isVideo && videoLocked),
                    onSelectRenderProfile = onSelectRenderProfile,
                    modifier = Modifier.viewfinderFrame(bleedRatio, viewfinderWidth)
                        .align(Alignment.TopCenter),
                    slotFrame = photoFrame,
                    forceCompact = isVideo,
                    forceTonemapStrip = isVideo,
                    filmQuick = filmQuick,
                    onFilmEvent = onFilmEvent,
                    quick = quick.copy(
                        locked = isVideo && videoLocked,
                        // Rawr 17U fork: the look sheet hides only for clean recording (no VF CONTROLS switch).
                        showLook = !cleanRecording,
                        showExposure = quick.showExposure && !cleanRecording,
                        lightMeterEnabled = quick.lightMeterEnabled && !cleanRecording
                    ),
                    onQuickEvent = onQuickEvent
                )
                if (isVideo && videoControls.recording) {
                    VideoRecordTimer(
                        status = videoControls.status,
                        dropped = videoControls.timing?.dropped,
                        orientation = state.orientation,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    )
                }
                // Shutter cluster starts at the photo frame bottom in every
                // mode. 16:9 video floats it over the preview bleed; photo
                // and Open Gate paint the surface color down to the monitor row.
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(photoFrame.height))
                    CaptureControls(
                        state = state,
                        dispatch = dispatch,
                        latestImageUri = latestImageUri,
                        onOpenLatestImage = onOpenLatestImage,
                        onOpenRenderer = onOpenRenderer,
                        onOpenFilmSimSettings = onOpenFilmSimSettings,
                        onOpenMultiframeSettings = onOpenMultiframeSettings,
                        onOpenLensSettings = onOpenLensSettings,
                        dcp = quick.dcps.firstOrNull { it.lensId == state.selectedLensId },
                        onSelectDcp = { file -> onQuickEvent(CaptureQuickEvent.SelectDcp(state.selectedLensId, file)) },
                        videoControls = videoControls,
                        onToggleVideoRecording = onToggleVideoRecording,
                        overlay = bleedRatio != null,
                        selectorsAlpha = cleanAlpha,
                        modifier = if (compactControls) {
                            Modifier.fillMaxWidth().weight(1f)
                        } else {
                            Modifier.fillMaxWidth().height(CaptureDimens.VideoControlsClassicHeight)
                        }
                    )
                }
            }
            if (isVideo) {
                VideoTimingMonitor(videoControls)
            } else {
                // Same height and color as the video timing row, so nothing
                // above it shifts between modes.
                Spacer(
                    Modifier.fillMaxWidth().height(CaptureDimens.MonitorHeight)
                        .background(CaptureColors.Surface)
                )
            }
            Spacer(Modifier.height(CaptureDimens.VideoMonitorBottomInset))
        }
    }
}

/**
 * Viewfinder box for the current mode. The Activity is portrait-locked, so a
 * landscape 16:9 recording presents as a 9:16 box; Open Gate matches the ~4:3
 * sensor and uses exactly the photo box.
 *
 * 16:9 boxes are full-bleed width and may run taller than the remaining space
 * (unbounded height, never scaled down); the overflow draws behind the shutter
 * cluster. Native presentation is aspect-fit and the idle preview is cropped
 * to the exact record rect, so the box fills with no bars and framing never
 * jumps at record start. No image work happens here — only box sizing.
 */
private fun Modifier.viewfinderFrame(bleedRatio: Float?, photoWidth: Dp): Modifier = if (bleedRatio != null) {
    this.fillMaxWidth()
        .wrapContentHeight(Alignment.Top, unbounded = true)
        .aspectRatio(bleedRatio)
} else {
    this.width(photoWidth).aspectRatio(3f / 4f)
}

@Composable
private fun StabilizerEffect(isVideo: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(com.rawr.camera.video.StabilizerSensor.mode(context))
    }
    var levelHorizon by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.rawr.camera.video.StabilizerSensor.levelHorizon(context))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        mode = com.rawr.camera.video.StabilizerSensor.mode(context)
        levelHorizon = com.rawr.camera.video.StabilizerSensor.levelHorizon(context)
        onPauseOrDispose { }
    }
    val sensor = androidx.compose.runtime.remember { com.rawr.camera.video.StabilizerSensor(context) }
    androidx.compose.runtime.DisposableEffect(isVideo, mode, levelHorizon) {
        sensor.configure(isVideo && mode > 0, com.rawr.camera.video.StabilizerSensor.smoothingSeconds(mode), levelHorizon)
        onDispose { sensor.close() }
    }
}
