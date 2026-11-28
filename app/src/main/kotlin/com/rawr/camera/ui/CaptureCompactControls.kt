package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.rawr.camera.ui.icons.outlined.Iso
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.ExposureParameter
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.ViewfinderQuickState

/**
 * V2 compact control strip.
 *
 * The WB · SS · ISO · EV strip floats over the viewfinder bottom
 * ([CompactViewfinderStrip]) so the control area below the monitor holds only
 * the lens selector + shutter row and the shutter sits as high as possible.
 * All four are scrubbable; long-press locks, double-tap returns to auto
 * (EV: double-tap resets to +0.0, long-press is haptic-only).
 */
@Composable
internal fun CompactViewfinderStrip(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier = Modifier,
    forceTonemapStrip: Boolean = false,
    renderProfiles: RenderProfileQuickState = RenderProfileQuickState(),
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit = {},
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    /** Distance from the top of the control frame the rail must stay below (scopes). */
    railTopLimit: Dp = 0.dp
) {
    val density = LocalDensity.current
    val topLimitPx = with(density) { railTopLimit.roundToPx() }
    // The rail floats directly above whatever the strip stack currently is, so
    // multi-level strips push it up instead of overlapping it. It is measured
    // after the stack and only gets the height left under the scopes.
    Box(modifier) {
        Layout(
            content = {
                CompactStripStack(state, dispatch, forceTonemapStrip, renderProfiles, onSelectRenderProfile, filmQuick, onFilmEvent, quick)
                // MTR / AE / HL moved to the swipe-down quick panel (ViewfinderQuickPanel); the rail slot stays
                // empty so the stack layout is unchanged.
                Box(Modifier)
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val stack = measurables[0].measure(constraints.copy(minHeight = 0))
            val room = if (constraints.hasBoundedHeight) {
                (constraints.maxHeight - topLimitPx - stack.height).coerceAtLeast(0)
            } else {
                androidx.compose.ui.unit.Constraints.Infinity
            }
            val rail = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = room))
            val height = stack.height + rail.height
            layout(stack.width, height) {
                stack.place(0, height - stack.height)
                rail.place(stack.width - rail.width, 0)
            }
        }
    }
}

@Composable
private fun CompactStripStack(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    forceTonemapStrip: Boolean,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    filmQuick: FilmSimQuickState?,
    onFilmEvent: (CaptureFilmEvent) -> Unit,
    quick: ViewfinderQuickState
) {
    // No scrim: legibility on bright and dark scenes comes from drop shadows
    // on every glyph instead of a background wash.
    // No tap swallow on the whole stack: it spanned the full width and made
    // the bottom third of the viewfinder dead to tap-to-focus. Each button is
    // a pointer target of its own, so taps on a button still never reach the
    // viewfinder; only the empty space between them now focuses.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Exactly one render owner is surfaced: Film Sim's preset/look strip
        // when ON, otherwise the regular tonemap profile/PARAMS strip. Both
        // share the same strip language and the same bottom slot geometry.
        // Video mode always shows the tonemap strip (film controls hidden).
        // VF CONTROLS (eye panel / Settings › Display) can hide the look strip and the exposure row.
        // Rawr 17U fork: the LOOK button next to the shutter opens the sheet (LookSheet.kt); the viewfinder shows no
        // LOOK or PARAMS chips. Film on: the film sheet; otherwise the look sheet. Turning Film on or off opens or
        // closes it, as Ed's strip opened the film sheet when Film came on.
        val filmOwner = state.filmSimEnabled && filmQuick != null && !forceTonemapStrip
        val filmOn = state.filmSimEnabled && !forceTonemapStrip
        var lastFilmOn by remember { mutableStateOf(filmOn) }
        LaunchedEffect(filmOn) {
            if (filmOn != lastFilmOn) {
                lastFilmOn = filmOn
                LookSheetState.open = filmOn
            }
        }
        // Film with no preset in use ("Custom"): the stock's name. Changed: the preset's look edited, or any film
        // value off its default (EV, push, ...).
        val lookLabel = if (filmOwner) {
            filmQuick!!.selectedPresetId?.let { filmQuick.activePresetName }
                ?: com.rawr.camera.settings.model.FilmStocks.films.getOrNull(filmQuick.discretes["Film"]?.selectedIndex ?: -1)
                ?: filmQuick.activePresetName
        } else renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }?.label ?: "RAWR NTRL"
        val lookModified = if (filmOwner) filmQuick!!.modified || filmQuick.params.values.any { !it.isDefault } else !renderProfiles.log && (state.renderExposureTenths != 0 ||
            com.rawr.camera.model.TonemapCatalog.tone.any {
                !com.rawr.camera.model.TonemapCatalog.isDefault(state, requireNotNull(it.param))
            } || renderProfiles.agx?.modified == true)
        SideEffect {
            LookSheetState.label = lookLabel
            LookSheetState.modified = lookModified
            LookSheetState.film = filmOwner
        }
        if (filmOwner) {
            FilmStrip(
                quick = filmQuick,
                onSelectPreset = { onFilmEvent(CaptureFilmEvent.SelectPreset(it)) },
                onScrubParam = { key, value -> onFilmEvent(CaptureFilmEvent.ScrubNumeric(
                    com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key), value)) },
                onResetParam = { key -> onFilmEvent(CaptureFilmEvent.ResetNumeric(
                    com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key))) },
                onScrubDiscrete = { key, index -> onFilmEvent(CaptureFilmEvent.ScrubDiscrete(
                    com.rawr.camera.settings.model.FilmSimDiscreteField.valueOf(key), index)) },
                onToggleFlag = { key, enabled -> onFilmEvent(CaptureFilmEvent.SetFlag(
                    com.rawr.camera.settings.model.FilmSimFlag.valueOf(key), enabled)) },
                modifier = Modifier.fillMaxWidth(),
                onApplyLook = { edit -> onFilmEvent(CaptureFilmEvent.ApplyLook(edit)) },
                onHoldLook = { held -> onFilmEvent(CaptureFilmEvent.HoldLook(held?.edit, held?.mode ?: 0)) },
                open = LookSheetState.open && quick.showLook,
                onCloseSheet = { LookSheetState.open = false },
                onImportPhotoLook = { name, look -> onFilmEvent(CaptureFilmEvent.ImportPreset(name, look)) }
            )
        } else if ((!state.filmSimEnabled || forceTonemapStrip) && LookSheetState.open && quick.showLook) {
            LookSheetPanel(
                profiles = renderProfiles,
                state = state,
                onSelectProfile = onSelectRenderProfile,
                dispatch = dispatch,
                onClose = { LookSheetState.open = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (quick.showExposure) {
            // Rawr 17U fork: the TELE sheet (tele optical zoom), above the row like the WB strip.
            if (TeleZoomSheetState.barShown && TeleZoomSheetState.style.aboveRow &&
                state.teleZoomAvailable() && !state.whiteBalancePanelOpen && !LookSheetState.open
            ) {
                TeleZoomSheet(
                    state, dispatch,
                    Modifier.fillMaxWidth()
                        .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
                        .padding(bottom = CaptureDimens.ControlGap)
                )
            }
            // Single-tap slider bar (ISO / SS / EV / WB / focus) sits right above the row it edits.
            SliderBarView()
            CompactParamRow(
                state = state,
                dispatch = dispatch,
                modifier = Modifier.fillMaxWidth(),
                oneLine = true
            )
        }
    }
}

/** LOOK cell at the end of the exposure row: opens/closes the look/PARAMS strip above it. */
@Composable
private fun LookToggleCell(open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .viewfinderChip(active = open)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
            .testTag("capture_look_toggle"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (open) "LOOK \u25BE" else "LOOK \u25B4",
            color = if (open) CaptureColors.AccentSoft else Color.White.copy(alpha = 0.85f),
            fontFamily = CaptureMono,
            fontSize = 9.sp,
            maxLines = 1,
            style = ViewfinderTextStyle
        )
    }
}

@Composable
internal fun CompactParamRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier = Modifier,
    oneLine: Boolean = false,
    lookOpen: Boolean = false,
    onToggleLook: (() -> Unit)? = null
) {
    val weights = CaptureDimens.CompactParamColumnWeights
    // 17U: one thin line (values only) with the LOOK toggle as the last cell.
    androidx.compose.runtime.CompositionLocalProvider(LocalCompactParamOneLine provides oneLine) {
    Row(
        modifier
            .fillMaxWidth()
            .height(if (oneLine) 36.dp else CaptureDimens.CompactParamTwoLineHeight)
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .testTag(CaptureTestTags.COMPACT_PARAM_ROW),
        horizontalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)
    ) {
        // Rawr 17U fork: the S/I priority switch's tab sits on the fixed cell (Settings > Photo > Capture > S/I Toggle).
        Box(Modifier.weight(weights[0]).fillMaxHeight()) {
            CompactExposureButton(ExposureParameter.Iso, state, dispatch, Modifier.fillMaxSize())
            PriorityToggleBadge(ExposureParameter.Iso, state, dispatch, Modifier.align(Alignment.TopStart))
        }
        Box(Modifier.weight(weights[1]).fillMaxHeight()) {
            CompactExposureButton(ExposureParameter.Shutter, state, dispatch, Modifier.fillMaxSize())
            PriorityToggleBadge(ExposureParameter.Shutter, state, dispatch, Modifier.align(Alignment.TopStart))
        }
        CompactExposureButton(ExposureParameter.Ev, state, dispatch, Modifier.weight(weights[2]).fillMaxHeight())
        CompactWbButton(state, dispatch, Modifier.weight(weights[3]).fillMaxHeight())
        CompactFocusButton(state, dispatch, Modifier.weight(weights[4]).fillMaxHeight())
        // Rawr 17U fork: the tele's optical zoom (75 to 100 mm), only on the lenses that move the optics.
        if (state.teleZoomAvailable()) CompactTeleZoomButton(state, dispatch, Modifier.weight(weights[2]).fillMaxHeight())
        if (onToggleLook != null) LookToggleCell(lookOpen, onToggleLook, Modifier.weight(0.95f).fillMaxHeight())
    }
    }
}
