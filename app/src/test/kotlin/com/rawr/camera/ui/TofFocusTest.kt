package com.rawr.camera.ui

import com.rawr.camera.integration.AuxSensorReading
import com.rawr.camera.integration.tofDistanceForFocus
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.FocusUiState
import com.rawr.camera.model.ManualFocusCapability
import kotlin.test.*

class TofFocusTest {
    // A lens that focuses down to 10 cm (10 D), with a calibrated distance readout.
    private val lens = ManualFocusCapability(supported = true, distanceReadoutTrustworthy = true, minimumFocusDistance = 10f)

    @Test fun distanceBecomesTheMatchingManualFocusPosition() {
        assertEquals(0.9f, tofFocusNormalized(1000f, lens)!!, 1e-4f)   // 1 m = 1 D
        assertEquals(0.8f, tofFocusNormalized(500f, lens)!!, 1e-4f)    // 0.5 m = 2 D
        assertEquals(0.975f, tofFocusNormalized(4000f, lens)!!, 1e-4f) // 4 m = 0.25 D
    }

    @Test fun closerThanTheLensFocusesLandsOnItsClosestPosition() {
        assertEquals(0f, tofFocusNormalized(50f, lens)!!)
        val limited = lens.copy(minNormalized = 0.2f)
        assertEquals(0.2f, tofFocusNormalized(50f, limited)!!)
    }

    @Test fun unusableWithoutADistanceOrACalibratedLens() {
        assertNull(tofFocusNormalized(null, lens))
        assertNull(tofFocusNormalized(Float.NaN, lens))
        assertNull(tofFocusNormalized(0f, lens))
        assertNull(tofFocusNormalized(1000f, lens.copy(distanceReadoutTrustworthy = false)))
        assertNull(tofFocusNormalized(1000f, lens.copy(supported = false)))
        assertNull(tofFocusNormalized(1000f, lens.copy(minimumFocusDistance = 0f)))
    }

    private fun afAt(metres: Float, mode: FocusMode = FocusMode.Af) =
        FocusUiState(mode = mode, appliedFocusDiopters = 1f / metres, afFocused = true)
    private fun tof(mm: Float) = AuxSensorReading(tofDistanceMm = mm)

    // utalpt's 17U tests: the L8 reads a made-up 0.4-0.65 m in bright light.
    @Test fun aReadingFarNearerThanAConfirmedAfFocusIsDropped() {
        assertNull(tofDistanceForFocus(tof(540f), afAt(12.5f), lens))                     // open sky
        assertNull(tofDistanceForFocus(tof(540f), afAt(12.5f, FocusMode.AfLock), lens))
        assertNull(tofDistanceForFocus(tof(540f), FocusUiState(mode = FocusMode.Af, appliedFocusDiopters = 0f, afFocused = true), lens))
        assertNull(tofDistanceForFocus(tof(570f), afAt(3.3f), lens))                      // sky beside a wall
        assertNull(tofDistanceForFocus(tof(640f), afAt(1.2f), lens))                      // sunlit paving
        assertNull(tofDistanceForFocus(tof(480f), afAt(1.9f), lens))                      // a car in the sun
    }

    @Test fun aReadingThatAgreesWithAfStays() {
        assertEquals(1100f, tofDistanceForFocus(tof(1100f), afAt(1.4f), lens))           // backpack, landscape
        assertEquals(1200f, tofDistanceForFocus(tof(1200f), afAt(1.1f), lens))           // backpack, portrait
        assertEquals(4000f, tofDistanceForFocus(tof(4000f), afAt(12.5f), lens))          // farther than AF: kept
        // Close up both read in many diopters: 10 cm against AF at 11 cm is agreement.
        assertEquals(100f, tofDistanceForFocus(tof(100f), afAt(0.11f), lens))
        assertNull(tofDistanceForFocus(tof(60f), afAt(0.11f), lens))
    }

    @Test fun withoutAConfirmedAfFocusTheReadingStays() {
        val sky = afAt(12.5f)
        assertEquals(540f, tofDistanceForFocus(tof(540f), sky.copy(afFocused = false), lens))      // scanning or failed
        assertEquals(540f, tofDistanceForFocus(tof(540f), sky.copy(mode = FocusMode.Mf), lens))    // the user set focus
        assertEquals(540f, tofDistanceForFocus(tof(540f), sky.copy(appliedFocusDiopters = null), lens))
        assertEquals(540f, tofDistanceForFocus(tof(540f), sky, lens.copy(distanceReadoutTrustworthy = false)))
        assertNull(tofDistanceForFocus(AuxSensorReading(), sky, lens))
        assertNull(tofDistanceForFocus(null, sky, lens))
    }

    @Test fun aNearObjectAfDidNotPickIsNotOffered() {
        // The price of trusting a confirmed AF: a hand at 10 cm in front of a wall AF focused at 1.1 m.
        assertNull(tofDistanceForFocus(tof(105f), afAt(1.1f), lens))
    }

    @Test fun labelIsShort() {
        assertEquals("TOF 45cm", tofFocusLabel(452f))
        assertEquals("TOF 1.2m", tofFocusLabel(1234f))
        assertEquals("TOF 4.0m", tofFocusLabel(4000f))
    }
}
