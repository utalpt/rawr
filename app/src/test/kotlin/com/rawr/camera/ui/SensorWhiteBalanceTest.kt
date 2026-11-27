package com.rawr.camera.ui

import kotlin.test.*

class SensorWhiteBalanceTest {
    @Test fun sensorTemperatureLandsOnTheRailSteps() {
        assertEquals(4850, sensorWhiteBalanceK(4862f))
        assertEquals(3325, sensorWhiteBalanceK(3329f))
        assertEquals(5200, sensorWhiteBalanceK(5200f))
    }

    @Test fun sensorTemperatureStaysInTheRailRange() {
        assertEquals(2000, sensorWhiteBalanceK(1500f))
        assertEquals(10000, sensorWhiteBalanceK(14000f))
    }

    @Test fun noReadingNoChoice() {
        assertNull(sensorWhiteBalanceK(null))
        assertNull(sensorWhiteBalanceK(Float.NaN))
        assertNull(sensorWhiteBalanceK(0f))
    }

    @Test fun anOffsetOfZeroLeavesTheReadingAlone() {
        assertEquals(4850, sensorWhiteBalanceK(4862f, 0f))
    }

    // The 17 Ultra's rear colour sensor reports 5900 K for a 5600 K reference: +9.1 mired, measured once.
    @Test fun theSensorBiasIsCorrectedInMired() {
        assertEquals(5600, sensorWhiteBalanceK(5900f, 9f))
    }

    // Mired, not kelvin: the same +9 mired is a different kelvin shift at each point of the rail, which
    // is the whole reason for the conversion. A -300 K offset would have given 3025 and 4900 here.
    @Test fun theSameMiredShiftIsADifferentKelvinShiftAlongTheRail() {
        assertEquals(3225, sensorWhiteBalanceK(3329f, 9f))
        assertEquals(4975, sensorWhiteBalanceK(5200f, 9f))
    }

    @Test fun aCorrectedReadingStaysOnTheRail() {
        assertEquals(2000, sensorWhiteBalanceK(1500f, 9f))
        assertEquals(10000, sensorWhiteBalanceK(14000f, 9f))
        // A negative offset that would send the mired below zero has no temperature to fall back to.
        assertNull(sensorWhiteBalanceK(1000f, -2000f))
    }

    @Test fun theCorrectionBelongsToThe17UltraOnly() {
        assertEquals(9f, SensorWhiteBalanceCorrection.offsetMired("nezha") { null })
        assertEquals(0f, SensorWhiteBalanceCorrection.offsetMired("dada") { null })
        assertEquals(0f, SensorWhiteBalanceCorrection.offsetMired("") { null })
    }

    @Test fun thePropertyOverridesOnAnyDevice() {
        // 0 turns the correction off on the 17 Ultra, so the same scene can be compared both ways.
        assertEquals(0f, SensorWhiteBalanceCorrection.offsetMired("nezha") { "0" })
        // Any other device can be measured without a build of its own.
        assertEquals(12f, SensorWhiteBalanceCorrection.offsetMired("dada") { "12" })
        assertEquals(-9f, SensorWhiteBalanceCorrection.offsetMired("dada") { "-9" })
        // Unreadable or absent: the device default, never a crash.
        assertEquals(9f, SensorWhiteBalanceCorrection.offsetMired("nezha") { "yes" })
        assertEquals(0f, SensorWhiteBalanceCorrection.offsetMired("dada") { null })
        // Beyond a plausible sensor bias is clamped, so a typo cannot park the seed at the rail end.
        assertEquals(60f, SensorWhiteBalanceCorrection.offsetMired("dada") { "99" })
        assertEquals(-60f, SensorWhiteBalanceCorrection.offsetMired("dada") { "-99" })
    }
}
