package com.rawr.camera.model

import kotlin.test.*

class FilmSectionGatesTest {
    private fun state(vararg on: Pair<String, Boolean>) = FilmSimQuickState.empty().copy(
        flags = on.associate { (k, v) -> k to FilmSimQuickFlag(k, k, v) }
    )

    @Test fun grainOffMarksTheSectionAndDimsItsValuesButNotItsSwitch() {
        val s = state("GrainEnabled" to false, "GrainSublayersEnabled" to true)
        assertTrue(FilmSectionGates.isOff(s, "grain"))
        assertFalse(FilmSectionGates.isLive(s, "grain", "emulsion", "GrainAmount"))
        assertFalse(FilmSectionGates.isLive(s, "grain", "setup", "GrainSublayersEnabled"))
        assertTrue(FilmSectionGates.isLive(s, "grain", "setup", "GrainEnabled"))
        assertFalse(FilmSectionGates.isOff(state("GrainEnabled" to true), "grain"))
    }

    @Test fun diffusionIsOffOnlyWhenBothSwitchesAre() {
        val s = state("CameraDiffusionEnabled" to true, "PrintDiffusionEnabled" to false)
        assertFalse(FilmSectionGates.isOff(s, "diffusion"))
        assertFalse(FilmSectionGates.isOff(s, "diffusion", "camera"))
        assertTrue(FilmSectionGates.isOff(s, "diffusion", "print"))
        assertTrue(FilmSectionGates.isLive(s, "diffusion", "camera", "CameraDiffusionStrength"))
        assertFalse(FilmSectionGates.isLive(s, "diffusion", "print", "PrintDiffusionStrength"))
    }

    @Test fun filterCutsFollowTheirOwnSwitch() {
        val s = state("CameraUvEnabled" to true, "CameraIrEnabled" to false)
        assertFalse(FilmSectionGates.isOff(s, "filters"))
        assertTrue(FilmSectionGates.isLive(s, "filters", "filtration", "CameraUvCutNm"))
        assertFalse(FilmSectionGates.isLive(s, "filters", "filtration", "CameraIrCutNm"))
    }

    @Test fun ungatedSectionsAndMissingSwitchesNeverDim() {
        val s = state()
        assertFalse(FilmSectionGates.isOff(s, "grain"))
        assertTrue(FilmSectionGates.isLive(s, "grain", "emulsion", "GrainAmount"))
        assertTrue(FilmSectionGates.isLive(state("GrainEnabled" to false), "print", "exposure", "PrintGamma"))
    }
}
