package com.rawr.camera.ui

import com.rawr.camera.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.FilmSimQuickDiscrete
import com.rawr.camera.model.FilmSectionGates
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.FilmStripItem
import com.rawr.camera.model.formatFilmStripValue

/**
 * Compact film-sim overlay. Bare viewfinder text with drop shadows, no cards.
 *
 * Modes: Collapsed (`{preset} + PARAMS`) -> Profiles (scrollable preset names)
 * or Config (L1 sections at the bottom, scrubbable two-line params above).
 * Sticky edges: `<` back stays left, the alternate entry (PARAMS / preset
 * name) stays right so modes switch without going back first. Everything is
 * gated on film ON by the caller; power itself lives on the FILM pill.
 */
private enum class FilmStripMode { Collapsed, Profiles, Quick, Config }

@Composable
internal fun FilmStrip(
    quick: FilmSimQuickState,
    onSelectPreset: (String) -> Unit,
    onScrubParam: (String, Float) -> Unit,
    onResetParam: (String) -> Unit,
    onScrubDiscrete: (String, Int) -> Unit,
    onToggleFlag: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Rawr 17U fork: keep a card's change to the look (FilmStockSheet.kt). */
    onApplyLook: (LookEdit) -> Unit = {},
    /** Rawr 17U fork: a card held - its change on the viewfinder only; null on release. */
    onHoldLook: (HeldLook?) -> Unit = {},
    /**
     * Rawr 17U fork: with [onCloseSheet] set, the LOOK button next to the shutter owns the sheet (LookSheet.kt): the
     * strip shows the film sheet only while [open], and ✕ calls [onCloseSheet] instead of showing FILM and PARAMS.
     */
    open: Boolean = true,
    onCloseSheet: (() -> Unit)? = null
) {
    // Rawr 17U fork: film on opens the film sheet straight away (✕ goes back to FILM and PARAMS).
    var mode by remember { mutableStateOf(FilmStripMode.Profiles) }
    var sectionKey by remember { mutableStateOf(quick.sections.firstOrNull()?.key ?: "film") }
    var subsectionKey by remember { mutableStateOf<String?>(null) }
    // L4 drill into a discrete's option list. Cleared whenever the section
    // or subsection changes, or Config is left, so a stale drill never
    // greets the next open.
    var l4Key by remember { mutableStateOf<String?>(null) }
    // OFF hides the whole strip (caller gate); always restart collapsed so a
    // stale expanded mode never greets the next ON.
    LaunchedEffect(quick.presets.isEmpty()) {
        if (quick.presets.isEmpty()) mode = FilmStripMode.Profiles
    }
    val section = quick.sections.firstOrNull { it.key == sectionKey }
        ?: quick.sections.firstOrNull()
    if (section != null && section.key != sectionKey) sectionKey = section.key
    val subsection = section?.subsections?.firstOrNull { it.key == subsectionKey }
        ?: section?.subsections?.firstOrNull()
    if (subsection != null && subsection.key != subsectionKey) subsectionKey = subsection.key
    if (onCloseSheet != null) {
        if (!open) return
        // Opened from the LOOK button: the film sheet, never the FILM and PARAMS chips.
        if (mode == FilmStripMode.Collapsed || mode == FilmStripMode.Quick) mode = FilmStripMode.Profiles
    }
    val closeSheet: () -> Unit = { onCloseSheet?.invoke() ?: run { mode = FilmStripMode.Collapsed } }
    Column(
        modifier.testTag(CaptureTestTags.FILM_STRIP),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (mode) {
            FilmStripMode.Collapsed -> {
                // Same layout as LOOK/PARAMS: FILM above PARAMS; FILM opens the film sheet in their place.
                FilmFixedColumn(
                    quick = quick,
                    presetsOpen = false,
                    onToggleProfiles = { mode = FilmStripMode.Profiles },
                    onOpenParams = { mode = FilmStripMode.Quick }
                )
            }
            FilmStripMode.Profiles -> {
                // Rawr 17U fork: the film sheet (FilmStockSheet.kt) takes the place of FILM and PARAMS; it stays
                // open after a pick for quick A/B comparison, ✕ closes it.
                FilmSheetPanel(
                    quick = quick,
                    onClose = closeSheet,
                    onSelectPreset = onSelectPreset,
                    onApply = onApplyLook,
                    onHold = onHoldLook,
                    groupCard = { group ->
                        val groups = FilmQuickGroups.filter { it.first == group }
                        if (groups.isNotEmpty()) FilmQuickCard(
                            quick = quick,
                            onScrubParam = onScrubParam,
                            onResetParam = onResetParam,
                            onToggleFlag = onToggleFlag,
                            onAdvanced = { mode = FilmStripMode.Config },
                            groups = groups,
                            helpFor = ::filmSheetSettingHelp
                        )
                    }
                )
            }
            FilmStripMode.Quick -> {
                FilmFixedColumn(
                    quick = quick,
                    presetsOpen = false,
                    onToggleProfiles = { mode = FilmStripMode.Profiles },
                    onOpenParams = { mode = FilmStripMode.Collapsed },
                    paramsActive = true,
                    card = {
                        FilmQuickCard(
                            quick = quick,
                            onScrubParam = onScrubParam,
                            onResetParam = onResetParam,
                            onToggleFlag = onToggleFlag,
                            onAdvanced = { mode = FilmStripMode.Config }
                        )
                    }
                )
            }
            FilmStripMode.Config -> {
                FilmAdvBlock(
                    quick = quick,
                    sectionKey = section?.key ?: "film",
                    subsectionKey = subsection?.key,
                    onSection = {
                        sectionKey = it
                        subsectionKey = null
                        l4Key = null
                    },
                    onSubsection = {
                        subsectionKey = it
                        l4Key = null
                    },
                    onBackToQuick = {
                        l4Key = null
                        mode = FilmStripMode.Quick
                    },
                    onFilm = {
                        l4Key = null
                        mode = FilmStripMode.Profiles
                    },
                    onCloseParams = {
                        l4Key = null
                        closeSheet()
                    },
                    l4Key = l4Key,
                    onOpenDiscrete = { l4Key = it },
                    onCloseL4 = { l4Key = null },
                    onScrubParam = onScrubParam,
                    onResetParam = onResetParam,
                    onScrubDiscrete = onScrubDiscrete,
                    onToggleFlag = onToggleFlag
                )
            }
        }
    }
}

@Composable
private fun FilmFixedColumn(
    quick: FilmSimQuickState,
    presetsOpen: Boolean,
    onToggleProfiles: () -> Unit,
    onOpenParams: () -> Unit,
    paramsActive: Boolean = false,
    /** Card shown above FILM (the PARAMS quick card), given the grid width. */
    card: (@Composable () -> Unit)? = null
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .padding(bottom = CaptureDimens.ControlGap)
    ) {
        val cells = paramsCellWidths(maxWidth)
        val gap = CaptureDimens.ControlGap
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            card?.let { content ->
                Box(Modifier.width(cells[1] + cells[2] + cells[3] + cells[4] + gap * 3)) { content() }
            }
            val presetName = quick.activePresetName
            Box(
                Modifier
                    .width(cells[4])
                    .height(StripDimens.ParamRowHeight)
                    .testTag(CaptureTestTags.FILM_STRIP_PROFILE)
                    .captureCombinedClickable(
                        shape = CaptureTextRippleShape,
                        onLongClickLabel = "Explain",
                        onLongClick = {
                            showHelp(
                                "Film: the film stock preset used for the JPEG and preview. A dot means its " +
                                    "parameters were changed. Tap to pick another; PARAMS edits the current one."
                            )
                        }
                    ) {
                        haptics.selection()
                        onToggleProfiles()
                    }.semantics {
                        contentDescription = "Film preset $presetName. " +
                            if (presetsOpen) "Tap to close." else "Tap to choose preset."
                    }
                    .viewfinderChip(active = presetsOpen),
                contentAlignment = Alignment.Center
            ) {
                StripTwoLineItem(
                    valueText = if (quick.modified) "$presetName •" else presetName,
                    valueColor = if (quick.modified) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f),
                    labelText = "FILM"
                )
            }
            StripParamsEntry(onOpenParams, Modifier.width(cells[4]).height(StripDimens.ParamRowHeight), active = paramsActive)
        }
    }
}

/** One quick-card entry: a film numeric slider or flag by catalog key, with the card's own label. */
internal sealed interface FilmQuickEntry {
    val label: String
    data class Num(val key: String, override val label: String, val needsFlag: String? = null) : FilmQuickEntry
    data class Flag(val key: String, override val label: String) : FilmQuickEntry
    data object Advanced : FilmQuickEntry { override val label = "ALL PARAMS" }
}

private val FilmQuickGroups: List<Pair<String, List<FilmQuickEntry>>> = listOf(
    "EXPOSURE" to listOf(
        FilmQuickEntry.Num("FilmExposureEv", "EV"),
        FilmQuickEntry.Num("FilmPushPullStops", "PUSH"),
        FilmQuickEntry.Num("PrintExposureEv", "PRINT EV")
    ),
    "GRAIN" to listOf(
        FilmQuickEntry.Flag("GrainEnabled", "GRAIN"),
        FilmQuickEntry.Num("GrainAmount", "AMT", needsFlag = "GrainEnabled"),
        FilmQuickEntry.Num("GrainParticleAreaUm", "SIZE", needsFlag = "GrainEnabled")
    ),
    "HALATION" to listOf(
        FilmQuickEntry.Flag("HalationEnabled", "HAL"),
        FilmQuickEntry.Num("HalationAmount", "AMT", needsFlag = "HalationEnabled"),
        FilmQuickEntry.Num("HalationScale", "SCALE", needsFlag = "HalationEnabled")
    ),
    "DIFFUSION" to listOf(
        FilmQuickEntry.Flag("CameraDiffusionEnabled", "CAMERA"),
        FilmQuickEntry.Num("CameraDiffusionStrength", "STR", needsFlag = "CameraDiffusionEnabled"),
        FilmQuickEntry.Flag("PrintDiffusionEnabled", "PRINT")
    ),
    "FILTERS" to listOf(
        FilmQuickEntry.Flag("CameraUvEnabled", "UV"),
        FilmQuickEntry.Flag("CameraIrEnabled", "IR"),
        FilmQuickEntry.Advanced
    )
)

/** PARAMS quick card: the film settings people change per scene, grouped, each led by its switch. */
@Composable
internal fun FilmQuickCard(
    quick: FilmSimQuickState,
    onScrubParam: (String, Float) -> Unit,
    onResetParam: (String) -> Unit,
    onToggleFlag: (String, Boolean) -> Unit,
    onAdvanced: () -> Unit,
    groups: List<Pair<String, List<FilmQuickEntry>>> = FilmQuickGroups,
    tagPrefix: String = "film",
    advDescription: String = "All film parameters",
    /** Long-press text per key; null keeps the generic hint. */
    helpFor: (String) -> String? = { null }
) {
    val haptics = LocalCaptureHaptics.current
    val gap = CaptureDimens.ControlGap
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .62f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(gap)
    ) {
        val cell = (maxWidth - gap * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { (title, entries) ->
                FilmCardTitle(title)
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    entries.forEach { entry ->
                        when (entry) {
                            is FilmQuickEntry.Flag -> {
                                val flag = quick.flags[entry.key]
                                if (flag == null) Box(Modifier.width(cell)) else FilmFlagItem(
                                    key = "quick_" + entry.key,
                                    shortLabel = entry.label,
                                    enabled = flag.enabled,
                                    onToggle = { onToggleFlag(entry.key, it) },
                                    modifier = Modifier.width(cell),
                                    help = helpFor(entry.key)
                                )
                            }
                            is FilmQuickEntry.Num -> {
                                val param = quick.params[entry.key]
                                val live = entry.needsFlag?.let { quick.flags[it]?.enabled } ?: true
                                if (param == null) Box(Modifier.width(cell)) else StripNumericItem(
                                    key = "quick_" + entry.key,
                                    modifier = Modifier.width(cell).alpha(if (live) 1f else .35f),
                                    shortLabel = entry.label,
                                    value = param.value,
                                    display = param.displayValue,
                                    isDefault = param.isDefault,
                                    defaultValue = param.defaultValue,
                                    min = param.minimum,
                                    max = param.maximum,
                                    step = param.step,
                                    decimals = param.decimals,
                                    onScrub = { if (live) onScrubParam(entry.key, it) },
                                    onReset = { if (live) onResetParam(entry.key) },
                                    format = {
                                        formatFilmStripValue(it * param.displayScale, param.minimum * param.displayScale,
                                            param.displayDecimals) + param.unit
                                    },
                                    help = (helpFor(entry.key) ?: "${param.shortLabel}: drag up/down to adjust; double-tap resets.") +
                                        (if (entry.needsFlag != null) " Active when its switch is ON." else "")
                                )
                            }
                            FilmQuickEntry.Advanced -> Box(
                                Modifier
                                    .width(cell)
                                    .height(StripDimens.ParamRowHeight)
                                    .viewfinderChip()
                                    .testTag("${tagPrefix}_quick_advanced")
                                    .captureClickable(shape = CaptureTextRippleShape) {
                                        haptics.selection()
                                        onAdvanced()
                                    }.semantics { contentDescription = advDescription },
                                contentAlignment = Alignment.Center
                            ) {
                                StripTwoLineItem(valueText = "ADV ›", valueColor = Color.White.copy(alpha = .95f),
                                    labelText = "ALL PARAMS")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FilmCardTitle(title: String) {
    Text(
        tr(title),
        color = Color.White.copy(alpha = .55f),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        style = ViewfinderTextStyle,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
    )
}

/** Grid tile used by the ADV card (sections and subsections). */
@Composable
internal fun FilmGridTile(
    label: String,
    selected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    tag: String,
    /** Rawr 17U fork: the section's effect is switched off; shows "OFF" under the label. */
    off: Boolean = false,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .width(width)
            .height(CaptureDimens.CompactParamRowHeight)
            .viewfinderChip(active = selected)
            .testTag(tag)
            .captureClickable(shape = CaptureTextRippleShape) {
                haptics.selection()
                onClick()
            }.semantics {
                contentDescription = label + (if (off) ", off" else "") + if (selected) ", active" else ""
                role = Role.Tab
                this.selected = selected
            }.padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = if (selected) CaptureColors.Accent else Color.White.copy(alpha = if (off) .5f else .85f),
                fontFamily = CaptureMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ViewfinderTextStyle
            )
            if (off) Text(
                "OFF",
                color = Color.White.copy(alpha = .45f),
                fontFamily = CaptureMono,
                fontWeight = FontWeight.Bold,
                fontSize = 7.sp,
                maxLines = 1,
                style = ViewfinderTextStyle
            )
        }
    }
}

/**
 * ADV: every film parameter. A card with the sections and the active section's subsections as grids, the
 * subsection's parameters on the row beside PARAMS (first four columns), FILM above PARAMS as always.
 */
@Composable
private fun FilmAdvBlock(
    quick: FilmSimQuickState,
    sectionKey: String,
    subsectionKey: String?,
    onSection: (String) -> Unit,
    onSubsection: (String) -> Unit,
    onBackToQuick: () -> Unit,
    onFilm: () -> Unit,
    onCloseParams: () -> Unit,
    l4Key: String?,
    onOpenDiscrete: (String) -> Unit,
    onCloseL4: () -> Unit,
    onScrubParam: (String, Float) -> Unit,
    onResetParam: (String) -> Unit,
    onScrubDiscrete: (String, Int) -> Unit,
    onToggleFlag: (String, Boolean) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val section = quick.sections.firstOrNull { it.key == sectionKey } ?: quick.sections.firstOrNull()
    val subsection = section?.subsections?.firstOrNull { it.key == subsectionKey } ?: section?.subsections?.firstOrNull()
    val drill = l4Key?.let { key -> quick.discretes[key]?.let { key to it } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (drill != null) {
            FilmDiscreteOptionsRow(
                dropdownKey = drill.first,
                entry = drill.second,
                onBack = onCloseL4,
                onSelect = { onScrubDiscrete(drill.first, it) }
            )
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
                .padding(bottom = CaptureDimens.ControlGap)
        ) {
            val cells = paramsCellWidths(maxWidth)
            val gap = CaptureDimens.ControlGap
            val cardWidth = cells[1] + cells[2] + cells[3] + cells[4] + gap * 3
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(gap)) {
                FilmAdvCard(quick, section?.key, subsection?.key, cardWidth, onSection, onSubsection, onBackToQuick,
                    sectionTag = CaptureTestTags::filmSection, subsectionTag = CaptureTestTags::filmSubsection,
                    backTag = "film_adv_back")
                // FILM above PARAMS, as in every film state.
                Box(
                    Modifier
                        .width(cells[4])
                        .height(StripDimens.ParamRowHeight)
                        .testTag(CaptureTestTags.FILM_STRIP_PROFILE)
                        .captureClickable(shape = CaptureTextRippleShape) {
                            haptics.selection()
                            onFilm()
                        }
                        .viewfinderChip(),
                    contentAlignment = Alignment.Center
                ) {
                    StripTwoLineItem(
                        valueText = if (quick.modified) "${quick.activePresetName} •" else quick.activePresetName,
                        valueColor = if (quick.modified) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f),
                        labelText = "FILM"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    val itemWidths = cells.subList(0, 4)
                    val hasDirToggle = section?.key == "dir" && subsection?.key == "couplers"
                    LazyRow(
                        Modifier
                            .width(cells[0] + cells[1] + cells[2] + cells[3] + gap * 3)
                            .height(StripDimens.ParamRowHeight)
                            .testTag(CaptureTestTags.FILM_STRIP_PARAMS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        if (hasDirToggle) {
                            val dirAmount = quick.params["DirCouplersAmount"]?.value ?: 0f
                            item(key = "dir-enable") {
                                FilmFlagItem(
                                    key = "DirEnabled",
                                    modifier = Modifier.width(itemWidths[0]),
                                    shortLabel = "DIR",
                                    enabled = dirAmount > 0f,
                                    onToggle = { onScrubParam("DirCouplersAmount", if (it) 1f else 0f) }
                                )
                            }
                        }
                        subsection?.items?.forEachIndexed { index, item ->
                            val cellWidth = itemWidths[(index + if (hasDirToggle) 1 else 0) % itemWidths.size]
                            // Rawr 17U fork: values that do nothing while their section is switched off are dimmed,
                            // as on the quick card (idea: apos-fandango's Rawr X14U). They stay editable.
                            val itemKey = when (item) {
                                is FilmStripItem.Numeric -> item.key
                                is FilmStripItem.Discrete -> item.key
                                is FilmStripItem.Flag -> item.key
                            }
                            val dim = if (FilmSectionGates.isLive(quick, section?.key, subsection?.key, itemKey)) 1f else .35f
                            when (item) {
                                is FilmStripItem.Numeric -> {
                                    val param = quick.params[item.key] ?: return@forEachIndexed
                                    item(key = item.key) {
                                        StripNumericItem(
                                            key = item.key,
                                            modifier = Modifier.width(cellWidth).alpha(dim),
                                            shortLabel = param.shortLabel,
                                            value = param.value,
                                            display = param.displayValue,
                                            isDefault = param.isDefault,
                                            defaultValue = param.defaultValue,
                                            min = param.minimum,
                                            max = param.maximum,
                                            step = param.step,
                                            decimals = param.decimals,
                                            onScrub = { onScrubParam(item.key, it) },
                                            onReset = { onResetParam(item.key) },
                                            format = {
                                                formatFilmStripValue(it * param.displayScale,
                                                    param.minimum * param.displayScale, param.displayDecimals) + param.unit
                                            }
                                        )
                                    }
                                }
                                is FilmStripItem.Discrete -> {
                                    val entry = quick.discretes[item.key] ?: return@forEachIndexed
                                    item(key = item.key) {
                                        FilmDiscreteItem(
                                            key = item.key,
                                            modifier = Modifier.width(cellWidth).alpha(dim),
                                            shortLabel = entry.shortLabel,
                                            display = entry.displayValue,
                                            drilled = drill?.first == item.key,
                                            onOpen = { onOpenDiscrete(item.key) }
                                        )
                                    }
                                }
                                is FilmStripItem.Flag -> {
                                    val entry = quick.flags[item.key] ?: return@forEachIndexed
                                    item(key = item.key) {
                                        FilmFlagItem(
                                            key = item.key,
                                            modifier = Modifier.width(cellWidth).alpha(dim),
                                            shortLabel = entry.shortLabel,
                                            enabled = entry.enabled,
                                            onToggle = { onToggleFlag(item.key, it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    StripParamsEntry(onCloseParams, Modifier.width(cells[4]).height(StripDimens.ParamRowHeight), active = true)
                }
            }
        }
    }
}

@Composable
internal fun FilmConfigBlock(
    quick: FilmSimQuickState,
    sectionKey: String,
    subsectionKey: String?,
    onSection: (String) -> Unit,
    onSubsection: (String) -> Unit,
    onBack: () -> Unit,
    onOpenProfiles: () -> Unit,
    l4Key: String?,
    onOpenDiscrete: (String) -> Unit,
    onCloseL4: () -> Unit,
    onScrubParam: (String, Float) -> Unit,
    onResetParam: (String) -> Unit,
    onScrubDiscrete: (String, Int) -> Unit,
    onToggleFlag: (String, Boolean) -> Unit,
    /** Spoken name of the block (the AgX strip reuses it). */
    kind: String = "Film"
) {
    val haptics = LocalCaptureHaptics.current
    val section = quick.sections.firstOrNull { it.key == sectionKey }
        ?: quick.sections.firstOrNull()
    val subsection = section?.subsections?.firstOrNull { it.key == subsectionKey }
        ?: section?.subsections?.firstOrNull()
    val drill = l4Key?.let { key -> quick.discretes[key]?.let { key to it } }
    Column(Modifier.fillMaxWidth()) {
        // L4 option list sits as its own row above L3, keeping the
        // subsection items visible below. Back collapses just the L4 row.
        if (drill != null) {
            FilmDiscreteOptionsRow(
                dropdownKey = drill.first,
                entry = drill.second,
                onBack = onCloseL4,
                onSelect = { onScrubDiscrete(drill.first, it) }
            )
        }
        // L3: editors of the active subsection. Items scrub VERTICALLY only
        // (up = increase), so scrubbing never fights row scrolling and never
        // runs out of room at the screen edges. Discretes drill to L4 on tap
        // instead of cycling: one-by-one stepping through 20 stocks is not
        // viable. Five parameter cells fit in the available row width.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
                .height(StripDimens.ParamRowHeight)
        ) {
            val cellWidths = paramsCellWidths(maxWidth)
            val hasDirToggle = section?.key == "dir" && subsection?.key == "couplers"
            LazyRow(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap, Alignment.End),
                contentPadding = PaddingValues(
                    start = parkedParamsPadding(maxWidth, cellWidths[0], CaptureDimens.ControlGap)
                )
            ) {
            // DIR has no dedicated flag: amount > 0 is the enable. Offer an
            // explicit ON/OFF cell so disabling never means scrubbing to
            // exactly 0 (mirrors the Settings switch, which writes 1/0).
            if (hasDirToggle) {
                val dirAmount = quick.params["DirCouplersAmount"]?.value ?: 0f
                item(key = "dir-enable") {
                    FilmFlagItem(
                        key = "DirEnabled",
                        modifier = Modifier.width(cellWidths[0]),
                        shortLabel = "DIR",
                        enabled = dirAmount > 0f,
                        onToggle = { onScrubParam("DirCouplersAmount", if (it) 1f else 0f) }
                    )
                }
            }
            subsection?.items?.forEachIndexed { index, item ->
                val cellWidth = cellWidths[(index + if (hasDirToggle) 1 else 0) % cellWidths.size]
                when (item) {
                    is FilmStripItem.Numeric -> {
                        val param = quick.params[item.key] ?: return@forEachIndexed
                        item(key = item.key) {
                            StripNumericItem(
                                key = item.key,
                                modifier = Modifier.width(cellWidth),
                                shortLabel = param.shortLabel,
                                value = param.value,
                                display = param.displayValue,
                                isDefault = param.isDefault,
                                defaultValue = param.defaultValue,
                                min = param.minimum,
                                max = param.maximum,
                                step = param.step,
                                decimals = param.decimals,
                                onScrub = { onScrubParam(item.key, it) },
                                onReset = { onResetParam(item.key) },
                                format = {
                                    formatFilmStripValue(it * param.displayScale, param.minimum * param.displayScale,
                                        param.displayDecimals) + param.unit
                                }
                            )
                        }
                    }
                    is FilmStripItem.Discrete -> {
                        val entry = quick.discretes[item.key] ?: return@forEachIndexed
                        item(key = item.key) {
                            FilmDiscreteItem(
                                key = item.key,
                                modifier = Modifier.width(cellWidth),
                                shortLabel = entry.shortLabel,
                                display = entry.displayValue,
                                drilled = drill?.first == item.key,
                                onOpen = { onOpenDiscrete(item.key) }
                            )
                        }
                    }
                    is FilmStripItem.Flag -> {
                        val entry = quick.flags[item.key] ?: return@forEachIndexed
                        item(key = item.key) {
                            FilmFlagItem(
                                key = item.key,
                                modifier = Modifier.width(cellWidth),
                                shortLabel = entry.shortLabel,
                                enabled = entry.enabled,
                                onToggle = { onToggleFlag(item.key, it) }
                            )
                        }
                    }
                }
            }
            }
        }
        // L2: subsections of the active section, parked at the thumb like L3.
        // Same edge inset as L3 and the L1 nav row so all three rows share a right edge.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
                .height(CaptureDimens.CompactParamRowHeight)
        ) {
            // Parked like the L3 params above, so both rows start at the same x.
            val parkedStart = parkedParamsPadding(
                maxWidth, paramsCellWidths(maxWidth)[0], CaptureDimens.ControlGap
            )
            LazyRow(
                Modifier
                    .fillMaxSize()
                    .testTag(CaptureTestTags.filmSubsectionRow(section?.key ?: "")),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StripDimens.ParamSpacing, Alignment.End),
                contentPadding = PaddingValues(start = parkedStart)
            ) {
            section?.subsections?.forEach { sub ->
                val active = sub.key == subsection?.key
                item(key = section.key + "/" + sub.key) {
                    Box(
                        Modifier
                            .height(CaptureDimens.CompactParamRowHeight)
                            .viewfinderChip(active = active)
                            .testTag(CaptureTestTags.filmSubsection(section.key, sub.key))
                            .captureClickable(shape = CaptureTextRippleShape) {
                                haptics.selection()
                                onSubsection(sub.key)
                            }.semantics {
                                contentDescription = "$kind subsection ${sub.label}" +
                                    if (active) ", active" else ""
                                role = Role.Tab
                                selected = active
                                onClick {
                                    haptics.selection()
                                    onSubsection(sub.key)
                                    true
                                }
                            }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            sub.label,
                            color = if (active) CaptureColors.Accent else Color.White.copy(alpha = .62f),
                            fontFamily = CaptureMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            letterSpacing = .4.sp,
                            maxLines = 1,
                            style = ViewfinderTextStyle
                        )
                    }
                }
            }
            }
        }
        // Lower (L1): collapse in the first column, scrollable sections, and preset picker.
        StripNavigationRow(onBack, Modifier.padding(bottom = 2.dp)) {
            // L1 sections parked at the thumb like L2/L3.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(CaptureDimens.CompactParamRowHeight)
            ) {
                LazyRow(
                    Modifier
                        .fillMaxSize()
                        .testTag(CaptureTestTags.FILM_STRIP_SECTION_ROW),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StripDimens.ParamSpacing, Alignment.End),
                    contentPadding = PaddingValues(start = parkedParamsPadding(maxWidth))
                ) {
                    items(quick.sections, key = { it.key }) { item ->
                    val active = item.key == section?.key
                    Box(
                        Modifier
                            .height(CaptureDimens.CompactParamRowHeight)
                            .viewfinderChip(active = active)
                            .testTag(CaptureTestTags.filmSection(item.key))
                            .captureClickable(shape = CaptureTextRippleShape) {
                                haptics.selection()
                                onSection(item.key)
                            }.semantics {
                                contentDescription = "$kind section ${item.label}" +
                                    if (active) ", active" else ""
                                role = Role.Tab
                                selected = active
                                onClick {
                                    haptics.selection()
                                    onSection(item.key)
                                    true
                                }
                            }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.label,
                            color = if (active) CaptureColors.Accent else Color.White.copy(alpha = .62f),
                            fontFamily = CaptureMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            letterSpacing = .4.sp,
                            maxLines = 1,
                            style = ViewfinderTextStyle
                        )
                    }
                    }
                }
            }
            Box(
                Modifier
                    .widthIn(max = 120.dp)
                    .height(CaptureDimens.CompactParamRowHeight)
                    .viewfinderChip()
                    .captureClickable(shape = CaptureTextRippleShape) {
                        haptics.selection()
                        onOpenProfiles()
                    }.semantics {
                        contentDescription = "$kind preset ${quick.activePresetName}. Tap to choose preset."
                        onClick {
                            haptics.selection()
                            onOpenProfiles()
                            true
                        }
                    }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    if (quick.modified) "${quick.activePresetName} •" else quick.activePresetName,
                    color = if (quick.modified) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f),
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ViewfinderTextStyle,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** Two-line viewfinder item geometry: value above, short label below. */
internal object StripDimens {
    val ParamRowHeight = 36.dp
    val ParamMinWidth = 68.dp
    /** Gap between scrollable strip cells (params, profiles, sections). */
    val ParamSpacing = 18.dp
    // Right-sticky entries (PARAMS, preset) sit one inset off the physical
    // edge: flush-right targets are hard to hit and trigger edge touches.
    val TrailingInset = 12.dp
    /**
     * Parked params show this many leading cells at the thumb edge, e.g. 1.5
     * reads as one full cell plus a peek of the next so the row affords scroll.
     */
    const val ParkedVisibleCells = 1.5f
}

/** Flag on/off as a two-line viewfinder text. Tap toggles. */
@Composable
private fun FilmFlagItem(
    key: String,
    shortLabel: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press text (Rawr 17U fork); null: no long press, as before. */
    help: String? = null
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    Box(
        modifier
            .widthIn(min = StripDimens.ParamMinWidth)
            .height(StripDimens.ParamRowHeight)
            .viewfinderChip()
            .testTag(CaptureTestTags.filmParam(key))
            .systemGestureExclusion()
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                onLongClickLabel = if (help != null) "Explain" else null,
                onLongClick = help?.let { text -> { showHelp(text) } }
            ) {
                haptics.selection()
                onToggle(!enabled)
            }.semantics {
                contentDescription = "$shortLabel ${if (enabled) "on" else "off"}. Tap to toggle."
                role = Role.Switch
                onClick {
                    haptics.selection()
                    onToggle(!enabled)
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        StripTwoLineItem(
            valueText = if (enabled) "ON" else "OFF",
            valueColor = if (enabled) CaptureColors.Accent else Color.White.copy(alpha = .62f),
            labelText = shortLabel
        )
    }
}

/**
 * Two-line discrete cell (current option above, short label below). Tap
 * drills into the L3 option list; option-by-option cycling is intentionally
 * not offered (stepping through 20 stocks one at a time is not viable).
 */
@Composable
private fun FilmDiscreteItem(
    key: String,
    shortLabel: String,
    display: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    drilled: Boolean = false
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .widthIn(min = StripDimens.ParamMinWidth)
            .height(StripDimens.ParamRowHeight)
            .viewfinderChip()
            .testTag(CaptureTestTags.filmParam(key))
            .captureClickable(shape = CaptureTextRippleShape) {
                haptics.selection()
                onOpen()
            }.semantics {
                contentDescription = "$shortLabel $display. Tap to choose option."
                onClick {
                    haptics.selection()
                    onOpen()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        StripTwoLineItem(
            valueText = display,
            valueColor = if (drilled) CaptureColors.Accent else Color.White.copy(alpha = .95f),
            labelText = shortLabel
        )
    }
}

/**
 * L3 option list for a drilled-in discrete: its own row above L2 with
 * sticky back left and scrollable option texts. Tap applies immediately and
 * stays for rapid comparison; back collapses just this row.
 */
@Composable
private fun FilmDiscreteOptionsRow(
    dropdownKey: String,
    entry: FilmSimQuickDiscrete,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    StripNavigationRow(onBack, Modifier.height(StripDimens.ParamRowHeight)) {
        LazyRow(
            Modifier
                .weight(1f)
                .height(StripDimens.ParamRowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StripDimens.ParamSpacing, Alignment.End)
        ) {
            items(entry.options.size) { index ->
                val option = entry.options[index]
                val isSelected = index == entry.selectedIndex
                Box(
                    Modifier
                        .height(StripDimens.ParamRowHeight)
                        .viewfinderChip(active = isSelected)
                        .testTag(CaptureTestTags.filmDiscreteOption(dropdownKey, index))
                        .captureClickable(shape = CaptureTextRippleShape) {
                            haptics.selection()
                            onSelect(index)
                        }.semantics {
                            contentDescription = "$option" + if (isSelected) ", active" else ""
                            role = Role.RadioButton
                            selected = isSelected
                            onClick {
                                haptics.selection()
                                onSelect(index)
                                true
                            }
                        }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option,
                        color = if (isSelected) CaptureColors.Accent else Color.White.copy(alpha = .62f),
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
            }
        }
    }
}

/** ADV card: ‹ QUICK, then the sections and the active section's subsections as grids. */
@Composable
internal fun FilmAdvCard(
    quick: FilmSimQuickState,
    sectionKey: String?,
    subsectionKey: String?,
    cardWidth: androidx.compose.ui.unit.Dp,
    onSection: (String) -> Unit,
    onSubsection: (String) -> Unit,
    onBackToQuick: () -> Unit,
    sectionTag: (String) -> String,
    subsectionTag: (String, String) -> String,
    backTag: String
) {
    val gap = CaptureDimens.ControlGap
    val tile = (cardWidth - gap * 4) / 3
    val section = quick.sections.firstOrNull { it.key == sectionKey } ?: quick.sections.firstOrNull()
    val subsection = section?.subsections?.firstOrNull { it.key == subsectionKey } ?: section?.subsections?.firstOrNull()
    Column(
        Modifier
            .width(cardWidth)
            .background(Color.Black.copy(alpha = .62f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(gap),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilmGridTile("‹ QUICK", false, tile, backTag, onClick = onBackToQuick)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { FilmCardTitle("ALL PARAMS") }
        }
        FilmCardTitle("SECTION")
        quick.sections.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { sec ->
                    FilmGridTile(sec.label, sec.key == section?.key, tile, sectionTag(sec.key),
                        off = FilmSectionGates.isOff(quick, sec.key)) { onSection(sec.key) }
                }
            }
        }
        if (section != null && section.subsections.size > 1) {
            FilmCardTitle("${section.label} ›")
            section.subsections.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { sub ->
                        FilmGridTile(sub.label, sub.key == subsection?.key, tile, subsectionTag(section.key, sub.key),
                            off = section.key == "diffusion" && FilmSectionGates.isOff(quick, section.key, sub.key)) {
                            onSubsection(sub.key)
                        }
                    }
                }
            }
        }
    }
}

/** Numeric parameters of one subsection in the columns beside PARAMS (scrolls past four). */
@Composable
internal fun FilmNumericRow(
    quick: FilmSimQuickState,
    items: List<FilmStripItem>,
    widths: List<androidx.compose.ui.unit.Dp>,
    onScrubParam: (String, Float) -> Unit,
    onResetParam: (String) -> Unit,
    modifier: Modifier = Modifier,
    helpFor: (String) -> String? = { null }
) {
    val gap = CaptureDimens.ControlGap
    LazyRow(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        items.filterIsInstance<FilmStripItem.Numeric>().forEachIndexed { index, item ->
            val param = quick.params[item.key] ?: return@forEachIndexed
            item(key = item.key) {
                StripNumericItem(
                    key = item.key,
                    modifier = Modifier.width(widths[index % widths.size]),
                    shortLabel = param.shortLabel,
                    value = param.value,
                    display = param.displayValue,
                    isDefault = param.isDefault,
                    defaultValue = param.defaultValue,
                    min = param.minimum,
                    max = param.maximum,
                    step = param.step,
                    decimals = param.decimals,
                    onScrub = { onScrubParam(item.key, it) },
                    onReset = { onResetParam(item.key) },
                    format = {
                        formatFilmStripValue(it * param.displayScale, param.minimum * param.displayScale,
                            param.displayDecimals) + param.unit
                    },
                    help = helpFor(item.key)
                )
            }
        }
    }
}
