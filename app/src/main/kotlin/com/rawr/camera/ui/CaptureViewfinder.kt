package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawr.camera.architecture.ActivateSpotAe
import com.rawr.camera.architecture.CancelSelfTimer
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CloseFocusSelector
import com.rawr.camera.architecture.FocusAt
import com.rawr.camera.architecture.PickWhiteBalanceAt
import com.rawr.camera.architecture.ToggleMonitorPanel
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.model.*

@Composable
internal fun CaptureViewfinder(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier,
    /**
     * Top-centered frame for controls and modal palettes (the photo 3:4 frame).
     * Keeps their geometry identical across modes and video resolutions when
     * the viewfinder box itself is taller. Null = this box.
     */
    slotFrame: DpSize? = null,
    /** Video mode hides the film strip (tonemap strip stays); film engine itself is untouched. */
    forceTonemapStrip: Boolean = false,
    /** Forces the compact strip content (used by the video overlay regardless of layout). */
    forceCompact: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {}
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .background(Color(0xFF1E1E1E))
            .testTag(CaptureTestTags.VIEWFINDER)
            .semantics { contentDescription = "Camera viewfinder" },
        contentAlignment = Alignment.Center
    ) {
        preview.content(Modifier.matchParentSize())
        if (!preview.nativeContent) {
            MonitoringOverlay(state.armedOverlays, state.falseColorManual, Modifier.matchParentSize())
        }
        GridOverlay(state.grid, Modifier.matchParentSize(), clearCenter = quick.levelMeterEnabled)
        FilmCompareOverlay(Modifier.matchParentSize())
        if (quick.levelMeterEnabled) LevelMeterOverlay(Modifier.matchParentSize())

        var viewfinderSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // Focus selector swaps the bottom slot to focus controls instead of
        // overlaying them. Monitor/render/white-balance palettes remain modal.
        val focusOnly =
            state.focus.selectorOpen && !state.monitorPanelOpen &&
                !state.whiteBalancePanelOpen
        // Rawr 17U fork: the eye opens the top sheet on MONITOR (below), not a modal palette.
        val modalOpen = state.whiteBalancePanelOpen
        // Rawr 17U fork: the WB strip and the FILM/LOOK sheet share the space above the exposure row, so the one
        // opened last closes the other (the LOOK button sits below the dismiss scrim and never closed WB).
        val lookSheetOpen = LookSheetState.open
        androidx.compose.runtime.LaunchedEffect(lookSheetOpen) {
            if (lookSheetOpen && state.whiteBalancePanelOpen) dispatch(ToggleWhiteBalancePanel)
            if (lookSheetOpen) TeleZoomSheetState.open = false
        }
        androidx.compose.runtime.LaunchedEffect(state.whiteBalancePanelOpen) {
            if (state.whiteBalancePanelOpen) LookSheetState.open = false
            if (state.whiteBalancePanelOpen) TeleZoomSheetState.open = false
        }
        // The rail shares the right edge with the compact scopes; keep it below them.
        val density = LocalDensity.current
        val railTopLimit = with(density) {
            val bottom = rightScopeBottom(
                viewfinderSize.width.toDp(), viewfinderSize.height.toDp(),
                state.orientation == Orientation.Landscape, state.activeScopes.size,
                preview.deviceRotationDegrees
            )
            if (bottom > 0.dp) bottom + 6.dp else 0.dp
        }
        val popupOpen = focusOnly || modalOpen
        var quickPanelOpen by remember { mutableStateOf(false) }
        var sheetTab by remember { mutableStateOf(QuickSheetTab.Exposure) }
        var sheetHeightPx by remember { mutableStateOf(0) }
        var bottomStackPx by remember { mutableStateOf(0) }
        val swipePx = with(LocalDensity.current) { 48.dp.toPx() }
        // Rawr 17U fork: opening needs a longer swipe that starts in the top third, so a drag to frame or focus
        // no longer opens the sheet by accident.
        val openSwipePx = with(LocalDensity.current) { 96.dp.toPx() }
        // The eye button: open the sheet on MONITOR (or close it if MONITOR is showing), then clear the flag.
        LaunchedEffect(state.monitorPanelOpen) {
            if (state.monitorPanelOpen) {
                if (quickPanelOpen && sheetTab == QuickSheetTab.Monitor) {
                    quickPanelOpen = false
                } else {
                    sheetTab = QuickSheetTab.Monitor
                    quickPanelOpen = true
                }
                dispatch(ToggleMonitorPanel)
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .onSizeChanged { viewfinderSize = it }
                .then(
                    if (!popupOpen) {
                        Modifier.pointerInput(swipePx, openSwipePx, state.orientation) {
                            // Swipe down from the top third opens the top sheet, swipe up closes it. In landscape
                            // "down" as the phone is held runs from the screen's right edge to the left.
                            val landscape = state.orientation == Orientation.Landscape
                            var travel = 0f
                            var fromTop = false
                            val onStart = { start: Offset ->
                                travel = 0f
                                fromTop = if (landscape) start.x > size.width * 2f / 3f else start.y < size.height / 3f
                            }
                            val onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, d: Float ->
                                    travel += d
                                    if (fromTop && travel > openSwipePx && !quickPanelOpen) {
                                        quickPanelOpen = true
                                        haptics.selection()
                                    } else if (travel < -swipePx && quickPanelOpen) {
                                        quickPanelOpen = false
                                        haptics.selection()
                                    }
                                    change.consume()
                            }
                            if (landscape) {
                                detectHorizontalDragGestures(
                                    onDragStart = onStart,
                                    onHorizontalDrag = { change, dx -> onDrag(change, -dx) }
                                )
                            } else {
                                detectVerticalDragGestures(
                                    onDragStart = onStart,
                                    onVerticalDrag = { change, dy -> onDrag(change, dy) }
                                )
                            }
                        }.pointerInput(state.orientation, state.exposureControl.mode, state.whiteBalancePickArmed) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (quickPanelOpen) {
                                        quickPanelOpen = false  // a tap outside the panel only closes it
                                        return@detectTapGestures
                                    }
                                    if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                        haptics.selection()
                                        val point = offset.toNormalized(viewfinderSize)
                                        dispatch(if (state.whiteBalancePickArmed) PickWhiteBalanceAt(point) else FocusAt(point))
                                    }
                                },
                                onLongPress = { offset ->
                                    if (state.exposureControl.mode == ExposureMode.Auto &&
                                        viewfinderSize.width > 0 && viewfinderSize.height > 0
                                    ) {
                                        haptics.longPress()
                                        dispatch(ActivateSpotAe(offset.toNormalized(viewfinderSize)))
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        )

        val scopeTopInset =
            if (quickPanelOpen && !popupOpen && state.orientation != Orientation.Landscape && sheetHeightPx > 0)
                with(density) { sheetHeightPx.toDp() } + 6.dp
            else 0.dp
        // Landscape: the compact scope slides up its edge to stay above the bottom controls and an open sheet.
        val scopeBottomInset = with(density) {
            val frameHeight = slotFrame?.height ?: viewfinderSize.height.toDp()
            // bottomStackPx already includes the slot's bottom padding.
            (viewfinderSize.height.toDp() - frameHeight).coerceAtLeast(0.dp) + bottomStackPx.toDp() + 6.dp
        }
        ScopeLayer(state, dispatch, preview, scopeTopInset, scopeBottomInset)
        if (state.whiteBalancePickArmed) {
            androidx.compose.material3.Text(
                text = "Tap something truly white or grey (not a cream wall)",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Box(Modifier.matchParentSize().then(if (popupOpen) Modifier else Modifier.zIndex(2f))) {
            if (preview.nativeContent) preview.faceOverlay() else FaceBoxes(state.faceDetections)
            AfTarget(state, dispatch)
            SpotAeTarget(state, dispatch)
        }

        // Wrapper stays a direct child so the focus-only zIndex still lifts the
        // slot above the z20 dismiss scrim, and modal palettes still cover it.
        val controlFrameModifier = Modifier
            .align(Alignment.TopCenter)
            .then(if (slotFrame != null) Modifier.size(slotFrame) else Modifier.matchParentSize())
        Box(
            controlFrameModifier
                .then(if (focusOnly) Modifier.zIndex(21f) else Modifier)
        ) {
            ViewfinderBottomSlot(
                state = state,
                dispatch = dispatch,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomStackPx = it.height }
                    .padding(
                        start = CaptureDimens.ViewfinderHorizontalPadding,
                        end = CaptureDimens.ViewfinderHorizontalPadding,
                        bottom = CaptureDimens.ViewfinderBottomPadding
                    ),
                forceCompact = forceCompact,
                forceTonemapStrip = forceTonemapStrip,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit
            )
        }

        if (popupOpen) {
            // Focus bottom controls sit above the scrim (z21 vs z20) so they stay
            // interactive; taps on the image area still dismiss. Modal palettes
            // keep full coverage.
            var scrimCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(20f)
                    .onGloballyPositioned { scrimCoords = it }
                    .pointerInput(
                        state.focus.selectorOpen,
                        state.monitorPanelOpen,
                        state.whiteBalancePanelOpen
                    ) {
                        detectTapGestures(
                            onTap = { at ->
                                // Rawr 17U fork: a tap on the TELE cell under the WB scrim switches to the TELE sheet.
                                val root = scrimCoords?.takeIf { it.isAttached }?.localToRoot(at)
                                if (state.whiteBalancePanelOpen && root != null &&
                                    TeleZoomSheetState.cellBounds?.contains(root) == true
                                ) TeleZoomSheetState.open = true
                                if (state.focus.selectorOpen) dispatch(CloseFocusSelector)
                                if (state.monitorPanelOpen) dispatch(ToggleMonitorPanel)
                                if (state.whiteBalancePanelOpen) dispatch(ToggleWhiteBalancePanel)
                            }
                        )
                    }
            )
        }
        if (modalOpen) {
            // Palettes share the photo control frame, even when the video preview
            // bleeds below it. Keep the wrapper above the full-preview dismiss scrim.
            Box(controlFrameModifier.zIndex(21f)) {
                val paletteModifier = Modifier.topBarPaletteModifier(state, this)
                if (state.monitorPanelOpen) {
                    // Scrolls rather than clipping its last row: the stack is taller than a short
                    // control frame once VF CONTROLS carries the lens bar options.
                    Column(
                        paletteModifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MonitorPanel(state, dispatch, Modifier)
                        if (quick.eyeControls) {
                            VfControlsPanel(quick, state.orientation, onQuickEvent)
                        }
                    }
                }
                if (state.whiteBalancePanelOpen) {
                    if (state.orientation == Orientation.Landscape) {
                        WhiteBalancePanel(state, dispatch, paletteModifier)
                    } else {
                        // Rawr 17U fork: portrait WB as a strip just above the exposure row (WhiteBalanceSheet.kt).
                        WhiteBalanceSheet(
                            state,
                            dispatch,
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = CaptureDimens.ViewfinderHorizontalPadding,
                                    end = CaptureDimens.ViewfinderHorizontalPadding,
                                    bottom = CaptureDimens.ViewfinderBottomPadding + StripDimens.ParamRowHeight +
                                        CaptureDimens.ControlGap
                                )
                        )
                    }
                }
            }
        }
        if (state.captureFlash) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .12f)))
        // Rawr 17U fork: the TELE control on the right edge (Wheel / Dial / Rocker, Settings > Tele Zoom Control).
        if (TeleZoomSheetState.open && TeleZoomSheetState.style != com.rawr.camera.model.TeleZoomStyle.BAR &&
            state.teleZoomAvailable() && !state.whiteBalancePanelOpen
        ) {
            Box(controlFrameModifier.zIndex(4f), contentAlignment = Alignment.CenterEnd) {
                TeleEdgeControl(state, dispatch, Modifier.padding(end = 6.dp))
            }
        }
        Box(controlFrameModifier.zIndex(3f)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = quickPanelOpen && !popupOpen,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 3 },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 3 },
                // Landscape: turned a quarter, along the right edge (the top as the phone is held), from the top.
                modifier = if (state.orientation == Orientation.Landscape)
                    Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 6.dp)
                else Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 6.dp, end = 6.dp)
            ) {
                // Portrait: full width; the compact scopes move down below it (scopeTopInset).
                val frameWidth = slotFrame?.width ?: with(density) { viewfinderSize.width.toDp() }
                val sheetWidth = if (state.orientation == Orientation.Landscape) 320.dp
                else (frameWidth - 12.dp).coerceAtLeast(200.dp)
                ViewfinderQuickPanel(
                    quick = quick,
                    spotActive = state.spotAe.active,
                    onEvent = onQuickEvent,
                    state = state,
                    dispatch = dispatch,
                    tab = sheetTab,
                    onTab = { sheetTab = it },
                    onClose = { quickPanelOpen = false },
                    // Measured after the turn: in landscape its height on screen is the sheet's width.
                    modifier = Modifier.onSizeChanged { sheetHeightPx = it.height }
                        .uprightPanelInLandscape(state.orientation).width(sheetWidth)
                )
            }
            if (quick.lightMeterEnabled && !(quickPanelOpen && !popupOpen)) {
                // While a video records, the REC/DROP readout takes the top line: sit below it.
                val recording = state.captureMode == CaptureMode.Video && quick.locked
                // Top centre as the user holds the phone: in landscape (turned 90° clockwise)
                // that is the middle of the screen's right edge.
                LightMeterOverlay(
                    exposureTimeNs = state.appliedExposureTimeNs,
                    orientation = state.orientation,
                    focusAtDistance = { mm ->
                        tofFocusNormalized(mm, state.capabilities.manualFocus)?.let {
                            dispatch(com.rawr.camera.architecture.SetManualFocus(it))
                        } != null
                    },
                    modifier = if (state.orientation == Orientation.Landscape) {
                        Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    } else {
                        Modifier.align(Alignment.TopCenter).padding(top = if (recording) 36.dp else 8.dp)
                    }
                )
            }
            if (state.capabilities.lensUnavailable) {
                LensUnavailableNotice(state.orientation, Modifier.align(Alignment.Center))
            } else if (state.capabilities.profileError.isNotEmpty()) {
                ProfileErrorNotice(state.capabilities.profileError, state.orientation, Modifier.align(Alignment.Center))
            }
            // One status pill for every capture wait, in one place: moving it
            // while it fades out left an empty pill behind.
            ScrubReadoutOverlay(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            CaptureStatusNotice(
                longExposure = state.longExposure,
                holdActive = state.bracketHoldActive,
                restoringPreview = state.restoringPreview,
                stacked = state.experimentalMultiframeEnabled && state.captureMode == CaptureMode.Photo,
                orientation = state.orientation,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                fullSizeBinnedStills = state.fullSizeBinnedStills,
                fullSizeBurstsToSingle = state.fullSizeBurstsToSingle,
                multiframeHint =
                    if (state.experimentalMultiframeEnabled && state.captureMode == CaptureMode.Photo)
                        multiframeHintText(state.multiframeAlgorithm, state.multiframeFrames)
                    else null,
                multiframeHintKey = listOf(state.selectedLensId, state.experimentalMultiframeEnabled,
                    state.multiframeAlgorithm, state.multiframeFrames)
            )
            LocalControlHelp.current?.let { help ->
                ControlHelpOverlay(help, state.orientation, Modifier.align(Alignment.Center))
            }
        }
        state.selfTimerRemainingMs?.let { remaining ->
            SelfTimerCountdownOverlay(
                remainingMs = remaining,
                orientation = state.orientation,
                onCancel = {
                    haptics.selection()
                    dispatch(CancelSelfTimer)
                },
                modifier = Modifier.matchParentSize().zIndex(3f)
            )
        }
    }
}

/**
 * Viewfinder bottom slot: focus controls, or the compact exposure/tonemap
 * strip, or the classic quick controls. Anchored to the bottom of the photo
 * 3:4 frame inside the viewfinder box, so its position is identical across
 * capture modes and video resolutions and switching never shifts
 * exposure/tonemap geometry.
 */
@Composable
internal fun ViewfinderBottomSlot(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier = Modifier,
    forceCompact: Boolean = false,
    forceTonemapStrip: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    railTopLimit: Dp = 0.dp
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)
    ) {
        if (state.focus.selectorOpen) {
            // Long-press on the focus area swaps the bottom slot: exposure
            // rails + AMSI/SHD-HL are hidden and replaced by focus mode
            // buttons + MF rail. Same slot, same heights, no overlay.
            FocusBottomControls(state, dispatch, Modifier.fillMaxWidth())
        } else if (forceCompact || state.captureLayout == CaptureControlLayout.Compact) {
            // V2: bare WB/SS/ISO/EV strip floats over the preview bottom
            // (scrimmed for legibility). Focus selector still swaps in
            // FocusBottomControls; scopes/overlays/targets are untouched.
            CompactViewfinderStrip(
                state,
                dispatch,
                Modifier.fillMaxWidth(),
                forceTonemapStrip = forceTonemapStrip,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit
            )
        } else {
            QuickControlsRow(state, dispatch)
            ExposureArea(state, dispatch)
        }
    }
}

private fun Modifier.topBarPaletteModifier(state: CaptureUiState, scope: BoxScope): Modifier = with(scope) { when {
    // Without scopes the palette hangs from the top of the frame (it used to float mid-frame).
    state.activeScopes.isEmpty() -> {
        this@topBarPaletteModifier.align(Alignment.TopCenter).padding(top = 12.dp)
    }

    state.orientation == Orientation.Portrait -> {
        this@topBarPaletteModifier
            .align(Alignment.BottomStart)
            .padding(bottom = CaptureDimens.TopBarPalettePortraitBottomPadding)
    }

    else -> {
        this@topBarPaletteModifier
            .align(Alignment.CenterStart)
            .offset(
                x = CaptureDimens.TopBarPaletteLandscapeStartOffset,
                y = CaptureDimens.TopBarPaletteLandscapeCrossOffset
            )
    }
}

}

private fun Offset.toNormalized(size: androidx.compose.ui.unit.IntSize) = NormalizedPoint(
    x = x / size.width,
    y = y / size.height
)
