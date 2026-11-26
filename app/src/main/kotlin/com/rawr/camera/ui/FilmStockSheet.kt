package com.rawr.camera.ui

import com.rawr.camera.i18n.tr
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.settings.architecture.SetFilmSimDiscreteValue
import com.rawr.camera.settings.architecture.withDiscrete
import com.rawr.camera.settings.model.FilmSimDiscreteField
import com.rawr.camera.settings.model.FilmSimLook
import com.rawr.camera.settings.model.FilmSimNumericParameter
import com.rawr.camera.settings.model.FilmSimSpecs
import com.rawr.camera.settings.model.FilmStocks
import com.rawr.camera.settings.model.FilmFactoryPresets
import kotlin.math.abs

/*
 * Rawr 17U fork: the film picker as cards, after the film browser of the fork's own Klipp Lab (utalpt/klipp,
 * FilmBrowser17u). One row of tabs (presets, the stock groups, print paper, filter, grain, halation) over one row
 * of cards, so most of the viewfinder stays clear. Tap a card to use it; hold it to see it on the live viewfinder
 * while the finger is down (the settings do not change) - the sheet fades out meanwhile so the whole frame shows.
 * The cards show names for now; previews of the current frame through each card come later.
 */

/** A change to the look: what a card does when tapped (kept) or held (preview only). */
typealias LookEdit = (FilmSimLook) -> FilmSimLook

/**
 * Rawr 17U fork: a held card - its change to the look and how the viewfinder shows it: [FilmCompareMode.SPLIT]
 * (the card on the right half), [FilmCompareMode.FULL] (whole viewfinder, halation) or [FilmCompareMode.LOUPE]
 * (grain: the middle of the frame enlarged to about the photo's pixel size).
 */
class HeldLook(val edit: LookEdit, val mode: Int)

object FilmCompareMode {
    const val SPLIT = 0
    const val FULL = 1
    const val LOUPE = 2
}

private class Card(
    val key: String,
    val top: String,
    val name: String,
    val edit: LookEdit,
    val selected: Boolean,
    /** A preset: tapping selects the preset (the settings keep its id), holding shows its look. */
    val presetId: String? = null
)

/** The sheet's tabs; [quickGroup] names the PARAMS quick-card group shown above the sheet for the tab. */
internal enum class FilmSheetTab(val label: String, val quickGroup: String?) {
    Presets("PRESETS", null), Negative("NEGATIVE", "EXPOSURE"), Cinema("CINEMA", "EXPOSURE"),
    Slide("SLIDE", "EXPOSURE"), Paper("PAPER", "EXPOSURE"), Filter("FILTER", "DIFFUSION"),
    Grain("GRAIN", "GRAIN"), Halation("HALATION", "HALATION")
}

private typealias Tab = FilmSheetTab

// Positions in FilmStocks.films (the native profile order).
private val NegativeFilms = listOf(2, 1, 3, 4, 5, 0, 6, 7, 13, 14, 15)
private val CinemaFilms = listOf(8, 9, 10, 11, 12)
private val SlideFilms = listOf(16, 17, 18, 19)
private val FujiFilms = setOf(13, 14, 15, 18, 19)

/**
 * Rawr 17U fork: what the viewfinder shows while a card is held, for [FilmCompareOverlay]: the look in use on the
 * left half and the held card on the right (the split falls on the middle of the screen, measured on the 17U), the
 * held card over the whole viewfinder (halation), or the grain loupe (the middle of the frame enlarged).
 */
internal data class FilmCompareHold(val current: String, val held: String, val mode: Int)

internal object FilmCompareOverlayState {
    var hold by mutableStateOf<FilmCompareHold?>(null)
}

/** Rawr 17U fork: the divider and the two names over the viewfinder while a film-sheet card is held. */
@Composable
internal fun FilmCompareOverlay(modifier: Modifier) {
    val hold = FilmCompareOverlayState.hold ?: return
    Box(modifier) {
        if (hold.mode == FilmCompareMode.SPLIT) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Color.White.copy(alpha = .85f))
            )
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 10.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CompareLabel(hold.current) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CompareLabel(hold.held) }
            }
        } else {
            val label = if (hold.mode == FilmCompareMode.LOUPE) "${hold.held} · grain loupe ×4" else hold.held
            Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) { CompareLabel(label) }
        }
    }
}

@Composable
private fun CompareLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontFamily = CaptureMono,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = ViewfinderTextStyle,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .6f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

private fun stockEdit(field: FilmSimDiscreteField, index: Int): LookEdit =
    { it.withDiscrete(SetFilmSimDiscreteValue(field, index)) }

internal fun filmSheetTabOf(film: Int): FilmSheetTab = when (film) {
    in CinemaFilms -> Tab.Cinema
    in SlideFilms -> Tab.Slide
    else -> Tab.Negative
}

// Filter strengths as on the glass (1/8 to 2), grain and halation in three steps.
private val FilterStrengths = listOf(0.125f to "1/8", 0.25f to "1/4", 0.5f to "1/2", 1f to "1", 2f to "2")
private val GrainSteps = listOf(0.5f to "Light", 1f to "Medium", 2f to "Strong")
// Halation shows only around bright lights; 1 is the stock default, so the steps go up from there (max 4).
private val HalationSteps = listOf(1f to "Low", 2f to "Medium", 4f to "High")

/** Long-press help for the sheet's tabs. */
private fun tabHelp(tab: FilmSheetTab): String = when (tab) {
    FilmSheetTab.Presets -> "Presets: saved film looks (stock, paper and settings). Tap a card to use one; hold it to see it on the viewfinder."
    FilmSheetTab.Negative -> "Negative film: colour negative stocks, printed on the paper chosen in PAPER. Tap to use, hold to see it on the viewfinder."
    FilmSheetTab.Cinema -> "Cinema film: Kodak motion picture negatives - Vision3 daylight (D) and tungsten (T), and Verita - printed on the paper chosen in PAPER."
    FilmSheetTab.Slide -> "Slide film: reversal stocks, scanned directly with no print, so PAPER goes away while one is in use."
    FilmSheetTab.Paper -> "Print paper: the paper the negative is printed on; it sets the print's contrast and colour. 2383 and 2393 are cinema print films."
    FilmSheetTab.Filter -> "Filter: a diffusion filter in front of the lens, a glow around the highlights. Pick the filter, then its strength from 1/8 to 2, as on the glass."
    FilmSheetTab.Grain -> "Grain: the film's grain over the picture. Light, medium or strong; amount and size in the settings above."
    FilmSheetTab.Halation -> "Halation: the red glow of light bouncing back through the film base around bright lights. Best seen on lamps, the sun or reflections; a flat scene shows little."
}

/** Long-press help for the settings shown above the sheet (the PARAMS quick-card keys). */
internal fun filmSheetSettingHelp(key: String): String? = when (key) {
    "GrainEnabled" -> "Grain: film grain on or off."
    "HalationEnabled" -> "Halation: the red glow around bright lights, on or off."
    "CameraDiffusionEnabled" -> "Camera filter: the diffusion filter in front of the lens, on or off (its type is in the FILTER cards)."
    "PrintDiffusionEnabled" -> "Print diffusion: a soft glow added at the print stage, on or off."
    "CameraUvEnabled" -> "UV filter: cuts ultraviolet before the film."
    "CameraIrEnabled" -> "IR filter: cuts infrared before the film."
    else -> runCatching { FilmSimNumericParameter.valueOf(key) }.getOrNull()?.let { parameter ->
        val spec = FilmSimSpecs.forParameter(parameter)
        "${spec.label}: ${spec.supportingText}. Drag up/down to adjust; double-tap resets."
    }
}

private fun near(a: Float, b: Float) = abs(a - b) < 0.02f

private fun cardsFor(tab: Tab, quick: FilmSimQuickState): List<Card> {
    val film = quick.discretes["Film"]?.selectedIndex ?: -1
    val paper = quick.discretes["Paper"]?.selectedIndex ?: -1
    fun films(list: List<Int>) = list.map { i ->
        Card("film$i", if (i in FujiFilms) "FUJIFILM" else "KODAK", FilmStocks.films.getOrElse(i) { "?" },
            stockEdit(FilmSimDiscreteField.Film, i), i == film)
    }
    return when (tab) {
        Tab.Presets -> quick.presets.mapNotNull { p ->
            val look = quick.presetLooks[p.id] ?: return@mapNotNull null
            Card("preset:${p.id}", "PRESET", p.label, { look }, p.id == quick.selectedPresetId, presetId = p.id)
        }
        Tab.Negative -> films(NegativeFilms)
        Tab.Cinema -> films(CinemaFilms)
        Tab.Slide -> films(SlideFilms)
        Tab.Paper -> FilmStocks.papers.mapIndexed { i, name ->
            Card("paper$i", if (i == 5) "FUJIFILM" else "KODAK", name, stockEdit(FilmSimDiscreteField.Paper, i), i == paper)
        }
        Tab.Filter -> {
            val on = quick.flags["CameraDiffusionEnabled"]?.enabled == true
            val family = quick.discretes["CameraDiffusionFamily"]?.selectedIndex ?: -1
            val strength = quick.params["CameraDiffusionStrength"]?.value ?: 0.5f
            listOf(Card("filter:none", "FILTER", "None", { it.copy(cameraDiffusionEnabled = false) }, !on)) +
                FilmStocks.diffusionFamilies.mapIndexed { i, name ->
                    Card("filter:$i", "FILTER", name,
                        { it.copy(cameraDiffusionEnabled = true, cameraDiffusionFamily = i) }, on && family == i)
                } +
                FilterStrengths.map { (value, label) ->
                    Card("strength:$label", "STRENGTH", label,
                        { it.copy(cameraDiffusionEnabled = true, cameraDiffusionStrength = value) },
                        on && near(strength, value))
                }
        }
        Tab.Grain -> {
            val on = quick.flags["GrainEnabled"]?.enabled == true
            val amount = quick.params["GrainAmount"]?.value ?: 1f
            listOf(Card("grain:off", "GRAIN", "Off", { it.copy(grainEnabled = false) }, !on)) +
                GrainSteps.map { (value, label) ->
                    Card("grain:$label", "GRAIN", label,
                        { it.copy(grainEnabled = true, grainAmount = value) }, on && near(amount, value))
                }
        }
        Tab.Halation -> {
            val on = quick.flags["HalationEnabled"]?.enabled == true
            val amount = quick.params["HalationAmount"]?.value ?: 1f
            listOf(Card("hal:off", "HALATION", "Off", { it.copy(halationEnabled = false) }, !on)) +
                HalationSteps.map { (value, label) ->
                    Card("hal:$label", "HALATION", label,
                        { it.copy(halationEnabled = true, halationAmount = value) }, on && near(amount, value))
                }
        }
    }
}

/**
 * The film sheet in place of FILM and PARAMS: the PARAMS quick-card group of the open tab above the tabs and
 * cards. The whole panel fades out while a card is held, so the whole viewfinder shows it.
 */
@Composable
internal fun FilmSheetPanel(
    quick: FilmSimQuickState,
    onClose: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onApply: (LookEdit) -> Unit,
    onHold: (HeldLook?) -> Unit,
    /** The PARAMS quick card limited to one group (by its label), or nothing for a tab without one. */
    groupCard: @Composable (String) -> Unit,
    /** Rawr 17U fork: opens ALL PARAMS (every film parameter); null hides the chip. */
    onAllParams: (() -> Unit)? = null,
    /** Rawr 17U fork: picks a Rawr photo whose film look becomes a preset; null hides "+ FROM PHOTO". */
    onImportPhoto: (() -> Unit)? = null
) {
    val film = quick.discretes["Film"]?.selectedIndex ?: -1
    var tab by remember { mutableStateOf(filmSheetTabOf(film)) }
    // A slide film has no paper: leave the paper tab if it goes away.
    if (tab == FilmSheetTab.Paper && film in FilmStocks.positiveFilmIndices) tab = filmSheetTabOf(film)
    var holding by remember { mutableStateOf(false) }
    val panelAlpha by animateFloatAsState(if (holding) 0f else 1f, tween(120), label = "filmPanelAlpha")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .padding(bottom = CaptureDimens.ControlGap)
            .alpha(panelAlpha),
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)
    ) {
        tab.quickGroup?.let { groupCard(it) }
        FilmStockSheet(
            quick = quick,
            tab = tab,
            onTab = { tab = it },
            onClose = onClose,
            onAllParams = onAllParams,
            onImportPhoto = onImportPhoto,
            onSelectPreset = onSelectPreset,
            onApply = onApply,
            onHold = { edit ->
                holding = edit != null
                onHold(edit)
            }
        )
    }
}

@Composable
internal fun FilmStockSheet(
    quick: FilmSimQuickState,
    tab: FilmSheetTab,
    onTab: (FilmSheetTab) -> Unit,
    onClose: () -> Unit,
    onSelectPreset: (String) -> Unit,
    /** Keep a change to the look. */
    onApply: (LookEdit) -> Unit,
    /** Show a change on the viewfinder only while a card is held; null on release. */
    onHold: (HeldLook?) -> Unit,
    modifier: Modifier = Modifier,
    /** Rawr 17U fork: opens ALL PARAMS; null hides the chip. */
    onAllParams: (() -> Unit)? = null,
    /** Rawr 17U fork: the PRESETS tab ends with "+ FROM PHOTO", which calls this; null hides it. */
    onImportPhoto: (() -> Unit)? = null
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    val film = quick.discretes["Film"]?.selectedIndex ?: -1
    val tabs = Tab.entries.filter { it != Tab.Paper || film !in FilmStocks.positiveFilmIndices }
    // Reset: back to the preset in use (its saved look), or to Rawr's default look when no preset is in use. The look
    // it replaced is kept for a few seconds, while the chip reads UNDO.
    val resetTarget = quick.selectedPresetId?.let { quick.presetLooks[it] } ?: FilmFactoryPresets.baseLook
    val resetName = quick.selectedPresetId?.let { id -> quick.presets.firstOrNull { it.id == id }?.label }
    var undoLook by remember { mutableStateOf<FilmSimLook?>(null) }
    LaunchedEffect(undoLook) { if (undoLook != null) { kotlinx.coroutines.delay(5000); undoLook = null } }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(14.dp))
            .padding(vertical = 6.dp)
            .testTag("film_stock_sheet"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Closes the sheet (FILM and PARAMS come back).
            item(key = "close") {
                Box(
                    Modifier
                        .height(26.dp)
                        .viewfinderChip(active = false)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = { showHelp("Close: back to the FILM and PARAMS buttons.") }
                        ) {
                            haptics.selection()
                            onClose()
                        }
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = "Close the film sheet" }
                        .testTag("film_sheet_close"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, style = ViewfinderTextStyle)
                }
            }
            item(key = "reset") {
                val undo = undoLook
                Box(
                    Modifier
                        .height(26.dp)
                        .viewfinderChip(active = undo != null)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = {
                                showHelp(
                                    if (resetName != null) "Reset: back to the preset $resetName as saved, undoing the changes made since. UNDO brings them back for 5 seconds."
                                    else "Reset: back to Rawr's default film look (no preset in use). UNDO brings the previous look back for 5 seconds."
                                )
                            }
                        ) {
                            haptics.selection()
                            if (undo != null) {
                                onApply { undo }
                                undoLook = null
                            } else {
                                onApply { current ->
                                    if (current != resetTarget) undoLook = current
                                    resetTarget
                                }
                            }
                        }
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = if (undo != null) "Undo the reset" else "Reset the film look" }
                        .testTag("film_sheet_reset"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr(if (undo != null) "UNDO" else "RESET"),
                        color = if (undo != null) CaptureColors.Accent else Color.White.copy(alpha = .8f),
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
            }
            items(tabs.size, key = { tabs[it].name }) { i ->
                val t = tabs[i]
                val on = t == tab
                Box(
                    Modifier
                        .height(26.dp)
                        .viewfinderChip(active = on)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = { showHelp(tabHelp(t)) }
                        ) {
                            haptics.selection()
                            onTab(t)
                        }
                        .padding(horizontal = 8.dp)
                        .testTag("film_sheet_tab:${t.name}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr(t.label),
                        color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .8f),
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
            }
            // Rawr 17U fork: the way into ALL PARAMS. Since the LOOK button opens this sheet in place of FILM and
            // PARAMS, the quick card's "ADV ›" (in its FILTERS group) had no tab to show it.
            if (onAllParams != null) item(key = "all_params") {
                Box(
                    Modifier
                        .height(26.dp)
                        .viewfinderChip(active = false)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = { showHelp("All params: every film parameter, by section. Sections whose switch is off show OFF.") }
                        ) {
                            haptics.selection()
                            onAllParams()
                        }
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "All film parameters" }
                        .testTag("film_sheet_all_params"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tr("ALL PARAMS") + " ›",
                        color = Color.White.copy(alpha = .8f),
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
            }
        }
        // a hold cut short (sheet closed mid-hold) leaves no labels behind and ends the compare
        val endHold by rememberUpdatedState(onHold)
        DisposableEffect(Unit) {
            onDispose {
                if (FilmCompareOverlayState.hold != null) {
                    FilmCompareOverlayState.hold = null
                    endHold(null)
                }
            }
        }
        val cards = cardsFor(tab, quick)
        val start = cards.indexOfFirst { it.selected }.coerceAtLeast(0)
        // A list state per tab, so each tab opens at its selected card.
        val list = remember(tab) { LazyListState((start - 1).coerceAtLeast(0)) }
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(cards.size, key = { cards[it].key }) { i ->
                val card = cards[i]
                CardView(
                    card,
                    onTap = {
                        val preset = card.presetId
                        if (preset != null) onSelectPreset(preset) else onApply(card.edit)
                    },
                    onHold = { down ->
                        val mode = when (tab) {
                            FilmSheetTab.Grain -> FilmCompareMode.LOUPE
                            FilmSheetTab.Halation -> FilmCompareMode.FULL
                            else -> FilmCompareMode.SPLIT
                        }
                        FilmCompareOverlayState.hold = if (down) FilmCompareHold(
                            // the tab's card in use, else the stock in use (e.g. a cinema card held over a Portra)
                            current = cards.firstOrNull { it.selected }?.name ?: FilmStocks.films.getOrNull(film) ?: "In use",
                            held = card.name,
                            mode = mode
                        ) else null
                        onHold(if (down) HeldLook(card.edit, mode) else null)
                    }
                )
            }
            // Rawr 17U fork: a JPEG or DNG taken with Rawr gives its film look as a new preset, put in use.
            if (tab == FilmSheetTab.Presets && onImportPhoto != null) item(key = "import_photo") {
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier
                        .width(84.dp)
                        .height(46.dp)
                        .clip(shape)
                        .background(Color(0xFF1C1C1C))
                        .border(1.dp, CaptureColors.Outline, shape)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = { showHelp("From photo: pick a JPEG or DNG taken with Rawr. Its film look is saved as a new preset and put in use.") }
                        ) {
                            haptics.selection()
                            onImportPhoto()
                        }
                        .semantics { contentDescription = "Import a film look from a photo" }
                        .testTag("film_sheet_import_photo")
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("+", color = CaptureColors.AccentSoft, fontFamily = CaptureMono, fontSize = 10.sp, maxLines = 1,
                        style = ViewfinderTextStyle)
                    Text(tr("FROM PHOTO"), color = Color.White.copy(alpha = .85f), fontFamily = CaptureMono, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = ViewfinderTextStyle)
                }
            }
        }
    }
}

@Composable
private fun CardView(card: Card, onTap: () -> Unit, onHold: (Boolean) -> Unit) {
    val haptics = LocalCaptureHaptics.current
    // The latest callbacks: the gesture detector lives on with the card.
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val shape = RoundedCornerShape(8.dp)
    val on = card.selected
    Column(
        Modifier
            .width(84.dp)
            .height(46.dp)
            .clip(shape)
            .background(Color(0xFF1C1C1C))
            .border(if (on) 2.dp else 1.dp, if (on) CaptureColors.Accent else CaptureColors.Outline, shape)
            .pointerInput(card.key) {
                var held = false
                detectTapGestures(
                    onPress = {
                        held = false
                        tryAwaitRelease()
                        if (held) {
                            held = false
                            hold(false)
                        }
                    },
                    onLongPress = {
                        held = true
                        haptics.longPress()
                        hold(true)
                    },
                    onTap = {
                        haptics.selection()
                        tap()
                    }
                )
            }
            .semantics { contentDescription = "${card.name}${if (on) ", in use" else ""}. Tap to use, hold to preview." }
            .testTag("film_card:${card.key}")
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            card.top,
            color = if (on) CaptureColors.AccentSoft else Color.White.copy(alpha = .5f),
            fontFamily = CaptureMono,
            fontSize = 8.sp,
            maxLines = 1,
            style = ViewfinderTextStyle
        )
        Text(
            card.name,
            color = if (on) Color.White else Color.White.copy(alpha = .85f),
            fontFamily = CaptureMono,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewfinderTextStyle
        )
    }
}
