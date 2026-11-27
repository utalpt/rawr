package com.rawr.camera.integration

import kotlin.test.*

class AuxSensorsTest {
    // Dominant frequencies the Xiaomi 15 flicker sensor reported (7.8125 Hz bins).
    private val led50HzMains = 101.5625f
    private val noFlicker = 7.8125f
    private val daylight = listOf(15.625f, 54.6875f, 585.9375f, 23.4375f, 312.5f, 39.0625f)

    private fun FlickerClassifier.feed(vararg hz: Float): FlickerFrequency? {
        var last: FlickerFrequency? = null
        hz.forEach { last = update(it) }
        return last
    }

    // ToF events the Xiaomi 15 reported: a wall at 90 cm, and an overcast sky with no target.
    private val tofWall = floatArrayOf(901.67f, 0f, 885.67f, 918.33f, 63179.67f, -1f, 0.40f, -1f, 0f, -1f, -1f, -1f, 5218f, -1f, -1f, 0f)
    private val tofSky = floatArrayOf(2515.67f, 255f, 2427.67f, 2620.67f, 31528.33f, -1f, 4.56f, -1f, 0f, -1f, -1f, -1f, 3300f, -1f, -1f, 0f)

    @Test fun tofDistanceNeedsAValidStatus() {
        assertEquals(901.67f, AuxSensors.tofDistanceMm(tofWall))
        assertNull(AuxSensors.tofDistanceMm(tofSky))
        assertNull(AuxSensors.tofDistanceMm(floatArrayOf(-5f, 0f)))
        assertNull(AuxSensors.tofDistanceMm(floatArrayOf(4500f, 0f)))
        assertEquals(1200f, AuxSensors.tofDistanceMm(floatArrayOf(1200f)))
        assertNull(AuxSensors.tofDistanceMm(floatArrayOf()))
    }

    // The Xiaomi 17 Ultra's ST L8 (utalpt's dumpsys): four distance/status pairs, bands from the top.
    private fun l8(vararg bandsMm: Float) = FloatArray(16).also { v -> bandsMm.forEachIndexed { i, mm -> v[i * 2] = mm } }

    @Test fun theL8IsReadAsFourBands() {
        assertEquals(TofLayout.Bands, AuxSensors.tofLayoutFor("L8 TOF Sensor"))
        assertEquals(TofLayout.Single, AuxSensors.tofLayoutFor("vi5300_tof"))
        assertEquals(TofLayout.Single, AuxSensors.tofLayoutFor(null))
        // Flat wall: the nearer middle band.
        assertEquals(1018f, AuxSensors.tofDistanceMm(l8(993f, 1018f, 1040f, 1072f), TofLayout.Bands))
        // A hand over the top half changes bands 0 and 1 only.
        assertEquals(105f, AuxSensors.tofDistanceMm(l8(95f, 105f, 1080f, 1122f), TofLayout.Bands))
        // Open sky: status 0 everywhere and a made-up ~0.6 m (left to the AF check, tofDistanceForFocus).
        assertEquals(540f, AuxSensors.tofDistanceMm(l8(600f, 560f, 540f, 630f), TofLayout.Bands))
    }

    @Test fun anL8BandWithoutATargetIsLeftOut() {
        val values = l8(1735f, 1731f, 1064f, 636f)
        assertEquals(1064f, AuxSensors.tofDistanceMm(values, TofLayout.Bands))
        values[5] = 255f // band 2 (the desk) without a target
        assertEquals(1731f, AuxSensors.tofDistanceMm(values, TofLayout.Bands))
        values[3] = 255f // and band 1: nothing left in the middle of the frame
        assertNull(AuxSensors.tofDistanceMm(values, TofLayout.Bands))
    }

    @Test fun theVi5300LayoutIsUnchanged() {
        assertEquals(901.67f, AuxSensors.tofDistanceMm(tofWall, TofLayout.Single))
        assertNull(AuxSensors.tofDistanceMm(tofSky, TofLayout.Single))
    }

    @Test fun mainsFlickerIsConfirmedAfterThreeReadings() {
        val c = FlickerClassifier()
        assertNull(c.feed(led50HzMains, led50HzMains))
        assertEquals(FlickerFrequency.Hz100, c.update(led50HzMains))
        assertEquals(50, c.detected!!.mainsHz)
    }

    @Test fun neighbouringBinsCountAsTheSameFlicker() {
        val c = FlickerClassifier()
        assertEquals(FlickerFrequency.Hz100, c.feed(93.75f, 101.5625f, 93.75f))
        val sixty = FlickerClassifier()
        assertEquals(FlickerFrequency.Hz120, sixty.feed(117.1875f, 125f, 117.1875f))
    }

    @Test fun steadyLightAndWanderingDaylightAreNotFlicker() {
        val dark = FlickerClassifier()
        repeat(20) { assertNull(dark.update(noFlicker)) }
        val day = FlickerClassifier()
        // One reading that happens to land near 100 Hz does not confirm anything.
        assertNull(day.feed(*daylight.toFloatArray(), led50HzMains, *daylight.toFloatArray()))
    }

    @Test fun oneNoisyReadingNeitherClearsNorSwitches() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(54.6875f, led50HzMains))
        assertEquals(FlickerFrequency.Hz100, c.feed(125f, led50HzMains))
    }

    @Test fun flickerIsReleasedAfterSixteenMissesInARow() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(*FloatArray(15) { noFlicker }))
        assertNull(c.update(noFlicker))
    }

    @Test fun aNewMainsFrequencyTakesOverOnceConfirmed() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(117.1875f, 117.1875f))
        assertEquals(FlickerFrequency.Hz120, c.update(117.1875f))
    }

    @Test fun invalidReadingsCountAsMisses() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertNull(c.feed(*FloatArray(16) { Float.NaN }))
        c.reset()
        assertNull(c.detected)
    }

    // A PWM-dimmed LED: the sensor settles on one bin (here 250 Hz falls between 246.09 and 253.91).
    private val pwm250 = floatArrayOf(250f, 253.90625f, 246.09375f, 250f, 253.90625f, 246.09375f)

    @Test fun steadyNonMainsFlickerIsConfirmedAfterSixReadings() {
        val c = FlickerClassifier()
        c.feed(*pwm250.copyOfRange(0, 5))
        assertNull(c.pwmHz)
        assertNull(c.update(pwm250[5]))  // Not mains...
        assertEquals(250f, c.pwmHz!!, 1f)  // ...but a steady 250 Hz.
        assertNull(c.detected)
    }

    @Test fun wanderingDaylightAndDarknessAreNotPwm() {
        val c = FlickerClassifier()
        repeat(4) { c.feed(*daylight.toFloatArray()) }
        assertNull(c.pwmHz)
        repeat(20) { c.update(noFlicker) }
        assertNull(c.pwmHz)
        // Low bins wander under steady light; they never count, however steady.
        repeat(10) { c.update(54.6875f) }
        assertNull(c.pwmHz)
    }

    @Test fun pwmSurvivesOddReadingsAndIsReleasedAfterSixteenMisses() {
        val c = FlickerClassifier()
        c.feed(*pwm250)
        c.feed(585.9375f, 23.4375f)
        assertNotNull(c.pwmHz)
        c.update(250f)
        repeat(15) { c.update(noFlicker) }
        assertNotNull(c.pwmHz)
        c.update(noFlicker)
        assertNull(c.pwmHz)
    }

    @Test fun pwmNearTheTopOfTheRangeIsKept() {
        // Xiaomi 15 on a desktop monitor dimmed to 28 %: ~961 Hz.
        val c = FlickerClassifier()
        c.feed(960.9375f, 968.75f, 953.125f, 960.9375f, 960.9375f, 968.75f)
        assertEquals(962f, c.pwmHz!!, 4f)
    }

    @Test fun mainsFlickerWinsOverPwm() {
        val c = FlickerClassifier()
        c.feed(*pwm250)
        assertNotNull(c.pwmHz)
        assertEquals(FlickerFrequency.Hz100, c.feed(led50HzMains, led50HzMains, led50HzMains))
        assertNull(c.pwmHz)
        c.reset()
        assertNull(c.pwmHz)
    }

    @Test fun flickerPeriodCoversMainsAndPwm() {
        assertEquals(10_000_000L, AuxSensorReading(flicker = FlickerFrequency.Hz100).flickerPeriodNs)
        assertEquals(4_000_000L, AuxSensorReading(flickerPwmHz = 250f).flickerPeriodNs)
        assertNull(AuxSensorReading(flickerSensor = true).flickerPeriodNs)
        assertFalse(AuxSensorReading(flickerPwmHz = 250f).isEmpty)
    }

    @Test fun pwmShuttersBandUnlessWholePeriods() {
        assertTrue(FlickerBanding.bands(3_000_000L, 4_000_000L))     // 1/333 s at 250 Hz
        assertFalse(FlickerBanding.bands(8_000_000L, 4_000_000L))    // 1/125 s: two periods
        assertFalse(FlickerBanding.bands(40_000_000L, 4_000_000L))   // ten periods average out
        assertEquals(12_000_000L, FlickerBanding.nearestSafeExposureNs(10_000_000L, 4_000_000L))
        assertFalse(FlickerBanding.bands(10_000_000L, 0L))
    }

    @Test fun shuttersOutOfStepWithTheFlickerBand() {
        val f = FlickerFrequency.Hz100
        assertTrue(FlickerBanding.bands(8_000_000L, f))       // 1/125 s
        assertTrue(FlickerBanding.bands(1_000_000L, f))       // 1/1000 s
        assertTrue(FlickerBanding.bands(16_666_667L, f))      // 1/60 s
        assertFalse(FlickerBanding.bands(10_000_000L, f))     // 1/100 s
        assertFalse(FlickerBanding.bands(20_000_000L, f))     // 1/50 s
        assertFalse(FlickerBanding.bands(30_303_030L, f))     // 1/33 s, within 5 % of three periods
        assertFalse(FlickerBanding.bands(250_000_000L, f))    // 1/4 s averages the flicker out
        assertFalse(FlickerBanding.bands(0L, f))
        assertFalse(FlickerBanding.bands(8_333_333L, FlickerFrequency.Hz120))  // 1/120 s at 60 Hz mains
        assertTrue(FlickerBanding.bands(10_000_000L, FlickerFrequency.Hz120))  // 1/100 s at 60 Hz mains
    }

    @Test fun nearestSafeShutterIsAWholeNumberOfPeriods() {
        val f = FlickerFrequency.Hz100
        assertEquals(10_000_000L, FlickerBanding.nearestSafeExposureNs(8_000_000L, f))
        assertEquals(10_000_000L, FlickerBanding.nearestSafeExposureNs(1_000_000L, f))
        assertEquals(20_000_000L, FlickerBanding.nearestSafeExposureNs(16_666_667L, f))
    }

    @Test fun incidentEvAtIso100() {
        assertEquals(0f, AuxSensorReading(lux = 2.5f).ev100!!, 1e-4f)
        assertEquals(4.35f, AuxSensorReading(lux = 51f).ev100!!, 0.01f)
        assertNull(AuxSensorReading(lux = 0f).ev100)
        assertNull(AuxSensorReading().ev100)
    }

    @Test fun nativeFormSeparatesUnknownFromNoFlicker() {
        val none = AuxSensorReading(lux = 51.1f, cctK = 3329f, flickerRawHz = 7.8125f, flickerSensor = true).toNative()
        assertEquals(51.1f, none[0])
        assertEquals(3329f, none[1])
        assertEquals(0f, none[2])
        assertEquals(7.8125f, none[3])
        val led = AuxSensorReading(flicker = FlickerFrequency.Hz100, flickerSensor = true).toNative()
        assertEquals(100f, led[2])
        val unknown = AuxSensorReading(lux = 10f).toNative()
        assertTrue(unknown[1].isNaN() && unknown[2].isNaN() && unknown[3].isNaN() && unknown[4].isNaN())
        assertEquals(218f, AuxSensorReading(tofDistanceMm = 218f).toNative()[4])
        assertFalse(AuxSensorReading(tofDistanceMm = 218f).isEmpty)
        assertEquals(1013.2f, AuxSensorReading(pressureHpa = 1013.2f).toNative()[5])
        assertTrue(unknown[5].isNaN())
        // The heading is not in the native array (it travels with the GPS fix) but still counts as a reading.
        assertEquals(6, AuxSensorReading(headingMagneticDeg = 90f).toNative().size)
        assertFalse(AuxSensorReading(headingMagneticDeg = 90f).isEmpty)
        assertTrue(AuxSensorReading().isEmpty)
    }
}
