package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import com.rawr.camera.settings.model.LensBarGrouping
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.SelectLens
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.LensCapability
import kotlinx.coroutines.delay

/** "3.2x 50MP": the zoom on top, the rest below. */
internal fun splitLensName(displayName: String): Pair<String, String> {
    val name = displayName.trim()
    val split = name.indexOf(' ')
    return if (split > 0) name.substring(0, split) to name.substring(split + 1).trim() else name to ""
}

/** Lens button content: two lines, Leica red fill when selected; [outlined] = red outline instead of fill. */
@Composable
internal fun LensButtonFace(
    displayName: String,
    selected: Boolean,
    enabled: Boolean,
    state: CaptureUiState,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    moreVariants: Boolean = false
) {
    val shape = CapturePalette.shape()
    val filled = selected && !outlined
    val boxed = CapturePalette.valueBackgrounds || filled || selected
    val (top, bottom) = splitLensName(displayName)
    val dim = if (enabled) 1f else .5f
    // Scales the capsule itself, so the tap target grows with the label rather than just around it.
    val scale = state.lensBarSize.scale
    Box(
        modifier
            // One width for every lens (labels fit in 60 dp), so Rounded/Pill buttons are all the same capsule.
            .widthIn(min = (60.dp * scale))
            .height(44.dp * scale)
            .then(
                if (!boxed) Modifier else Modifier
                    .background(if (filled) CaptureColors.Accent.copy(alpha = dim) else Color(0xFF141414), shape)
                    .border(
                        1.dp,
                        when {
                            filled -> Color.Transparent
                            selected -> CaptureColors.Accent
                            else -> Color(0xFF262626)
                        },
                        shape
                    )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp).uprightInLandscape(state.orientation)
        ) {
            Text(
                top,
                color = when {
                    filled -> CaptureColors.OnAccent
                    selected -> CaptureColors.Accent
                    else -> Color.White.copy(alpha = .9f)
                }.copy(alpha = dim),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                maxLines = 1
            )
            if (bottom.isNotEmpty()) {
                Text(
                    bottom,
                    color = when {
                        filled -> CaptureColors.OnAccentSoft
                        // Rawr 17U fork: the theme's soft accent (was a fixed red, also in the Mono theme).
                        selected -> CaptureColors.AccentSoft
                        else -> Color(0xFF8C8C8C)
                    }.copy(alpha = dim),
                    fontFamily = CaptureMono,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    letterSpacing = .5.sp,
                    maxLines = 1
                )
            }
        }
        if (moreVariants) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    // Inside the corner curve of every button shape (Rounded/Pill corners are larger).
                    .padding(
                        top = when (CapturePalette.buttonShape) {
                            ButtonShapeStyle.Square -> 4.dp
                            ButtonShapeStyle.Rounded -> 5.dp
                            ButtonShapeStyle.Pill -> 6.dp
                        },
                        end = when (CapturePalette.buttonShape) {
                            ButtonShapeStyle.Square -> 4.dp
                            ButtonShapeStyle.Rounded -> 6.dp
                            ButtonShapeStyle.Pill -> 10.dp
                        }
                    )
                    .size(4.dp)
                    .background(if (filled) CaptureColors.OnAccent else Color(0xFF8C8C8C), CircleShape)
            )
        }
    }
}

/** Places the popup centred above its anchor, kept inside the window. */
private class AbovePositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * One button per physical lens (optical group). A group shows its active variant (the selected
 * lens, or the one last used there). Tapping another group switches to that variant; tapping the
 * active group opens its variants above it. Long press opens the shown variant's settings.
 */
@Composable
internal fun LensGroupSelector(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier,
    enabled: Boolean = true,
    onOpenLensSettings: (String) -> Unit = {}
) {
    val haptics = LocalCaptureHaptics.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rawr_lens_groups", android.content.Context.MODE_PRIVATE) }
    // Rawr 17U fork: one tele button - the 3.2x lenses are the 4.3x ones at 75 mm (TeleOpticalZoom).
    val lenses = com.rawr.camera.model.TeleOpticalZoom.visibleLenses(state.capabilities.lenses)
    // Auto keeps every lens on one row while the row still fits, and groups by optics when it would not.
    // The width is the capsule (60 dp scaled) plus the 6 dp gap, against the usable width less the padding
    // the capture layout puts around this row.
    val maxWidthDp = LocalConfiguration.current.screenWidthDp - 24
    val flatFits = lenses.size * ((60 * state.lensBarSize.scale) + 6) <= maxWidthDp
    val flat = when (state.lensBarGrouping) {
        LensBarGrouping.Flat -> true
        LensBarGrouping.Grouped -> false
        LensBarGrouping.Auto -> flatFits
    }
    val groups: List<Pair<String, List<LensCapability>>> = remember(lenses, flat) {
        if (flat) lenses.map { ("lens:" + it.id) to listOf(it) }
        else lenses.groupBy { it.group.ifBlank { "lens:" + it.id } }.toList()
    }
    // Remember the variant each group was last left on.
    val selectedGroup = groups.firstOrNull { (_, members) -> members.any { it.id == state.selectedLensId } }?.first
    LaunchedEffect(state.selectedLensId, selectedGroup) {
        if (selectedGroup != null) prefs.edit().putString(selectedGroup, state.selectedLensId).apply()
    }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    // Auto-close 3 s after the last interaction (scrolling the variants counts).
    var popupTouch by remember { mutableStateOf(0) }
    LaunchedEffect(expandedGroup, popupTouch) {
        if (expandedGroup != null) {
            delay(3000)
            expandedGroup = null
        }
    }
    if (!enabled && expandedGroup != null) expandedGroup = null
    // Rawr 17U fork: the modes popup opens where the TELE bar sits, so it closes that bar.
    LaunchedEffect(expandedGroup) {
        if (expandedGroup != null) {
            SliderBar.close()
            TeleZoomSheetState.open = false
        }
    }

    Row(
        // Scrolls sideways only when the groups don't fit (rare once variants are grouped).
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        groups.forEach { (key, members) ->
            val isActive = key == selectedGroup
            val shown = members.firstOrNull { it.id == state.selectedLensId }
                ?: members.firstOrNull { it.id == prefs.getString(key, null) }
                ?: members.first()
            Box {
                LensButtonFace(
                    displayName = com.rawr.camera.model.TeleOpticalZoom.displayName(shown, state.teleZoomMm),
                    selected = isActive,
                    enabled = enabled,
                    state = state,
                    moreVariants = members.size > 1,
                    modifier = Modifier
                        .testTag(CaptureTestTags.lens(shown.id))
                        .then(
                            if (enabled) {
                                Modifier.captureCombinedClickable(
                                    shape = CapturePalette.shape(),
                                    onLongClickLabel = "${shown.displayName} lens settings",
                                    onLongClick = {
                                        haptics.selection()
                                        onOpenLensSettings(shown.id)
                                    }
                                ) {
                                    haptics.selection()
                                    if (isActive) {
                                        expandedGroup = if (members.size > 1 && expandedGroup != key) key else null
                                    } else {
                                        expandedGroup = null
                                        dispatch(SelectLens(shown.id))
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                        .semantics {
                            contentDescription = "Lens ${shown.displayName}" +
                                (if (members.size > 1) ", ${members.size} variants" else "") +
                                if (enabled) ". Long press for settings." else " (unavailable while recording)"
                            role = Role.RadioButton
                            selected = isActive
                            if (!enabled) disabled()
                        }
                )
                if (expandedGroup == key) {
                    val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }
                    Popup(
                        popupPositionProvider = remember(gapPx) { AbovePositionProvider(gapPx) },
                        onDismissRequest = { expandedGroup = null },
                        properties = PopupProperties(focusable = true)
                    ) {
                        val panel = RoundedCornerShape(14.dp)
                        // Many variants: the panel is capped at the screen width and scrolls sideways,
                        // starting on the current variant.
                        val scroll = rememberScrollState()
                        val maxPanel = LocalConfiguration.current.screenWidthDp.dp - 16.dp
                        var currentX by remember { mutableStateOf(-1) }
                        var viewport by remember { mutableStateOf(0) }
                        LaunchedEffect(currentX, viewport) {
                            if (currentX >= 0 && viewport > 0) scroll.scrollTo((currentX - viewport / 3).coerceAtLeast(0))
                        }
                        LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) popupTouch++ }
                        Row(
                            Modifier
                                .widthIn(max = maxPanel)
                                .background(Color.Black.copy(alpha = .78f), panel)
                                .border(1.dp, CaptureColors.Outline, panel)
                                .clip(panel)
                                .onSizeChanged { viewport = it.width }
                                .horizontalScroll(scroll)
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            members.forEach { variant ->
                                val current = variant.id == state.selectedLensId
                                LensButtonFace(
                                    displayName = com.rawr.camera.model.TeleOpticalZoom.displayName(variant, state.teleZoomMm),
                                    selected = current,
                                    enabled = true,
                                    state = state,
                                    outlined = true,
                                    modifier = Modifier
                                        .then(
                                            if (current) {
                                                Modifier.onGloballyPositioned { currentX = it.positionInParent().x.toInt() }
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .testTag(CaptureTestTags.lens(variant.id) + "_variant")
                                        .captureCombinedClickable(
                                            shape = CapturePalette.shape(),
                                            onLongClickLabel = "${variant.displayName} lens settings",
                                            onLongClick = {
                                                haptics.selection()
                                                expandedGroup = null
                                                onOpenLensSettings(variant.id)
                                            }
                                        ) {
                                            haptics.selection()
                                            expandedGroup = null
                                            if (!current) dispatch(SelectLens(variant.id))
                                        }
                                        .semantics {
                                            contentDescription = "Lens ${variant.displayName}"
                                            role = Role.RadioButton
                                            selected = current
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
