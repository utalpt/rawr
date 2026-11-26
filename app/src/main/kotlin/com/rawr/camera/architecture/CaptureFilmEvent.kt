package com.rawr.camera.architecture

import com.rawr.camera.settings.model.FilmSimDiscreteField
import com.rawr.camera.settings.model.FilmSimFlag
import com.rawr.camera.settings.model.FilmSimLook
import com.rawr.camera.settings.model.FilmSimNumericParameter

/** Typed commands for the film strip; section/navigation keys remain presentation-only. */
sealed interface CaptureFilmEvent {
    data class SelectPreset(val id: String) : CaptureFilmEvent
    data class ScrubNumeric(val parameter: FilmSimNumericParameter, val value: Float) : CaptureFilmEvent
    data class ResetNumeric(val parameter: FilmSimNumericParameter) : CaptureFilmEvent
    data class ScrubDiscrete(val field: FilmSimDiscreteField, val index: Int) : CaptureFilmEvent
    data class SetFlag(val flag: FilmSimFlag, val enabled: Boolean) : CaptureFilmEvent
    /** Rawr 17U fork: a card of the film sheet tapped - its change to the look is kept. */
    data class ApplyLook(val edit: (FilmSimLook) -> FilmSimLook) : CaptureFilmEvent
    /**
     * Rawr 17U fork: a card of the film sheet held - the viewfinder shows [edit] applied to the settings' look
     * while it is held, without changing the settings; null puts the viewfinder back on the settings' look. [mode]:
     * 0 the right half of the viewfinder, 1 the whole viewfinder, 2 grain loupe (ui.FilmCompareMode).
     */
    data class HoldLook(val edit: ((FilmSimLook) -> FilmSimLook)?, val mode: Int = 0) : CaptureFilmEvent
    /** Rawr 17U fork: a photo's film look (read from its recipe) saved as a preset and put in use. */
    data class ImportPreset(val name: String, val look: FilmSimLook) : CaptureFilmEvent
}
