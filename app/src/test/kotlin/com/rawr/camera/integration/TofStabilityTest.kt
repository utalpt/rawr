package com.rawr.camera.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The ToF distances a phone publishes, from the readings it gets: see [TofWindow] and [tofFilterFor]. */
class TofStabilityTest {

    private fun windowOf(vararg readings: Float?): Float? {
        val window = TofWindow()
        var last: Float? = null
        readings.forEach { last = window.accept(it) }
        return last
    }

    @Test
    fun aTargetSettlesIntoTheMedianOfItsReadings() {
        assertNull(windowOf(1_000f, 1_010f))
        assertEquals(1_000f, windowOf(1_000f, 1_010f, 992f)!!, 0.001f)
    }

    @Test
    fun theWindowKeepsTheLastFiveReadings() {
        // A window that never disagrees, so only its width decides: after 1..6 it holds 2..6, after 7 it holds 3..7.
        val window = TofWindow(maxSpread = { 10_000f })
        listOf(1f, 2f, 3f, 4f, 5f).forEach { window.accept(it) }
        assertEquals(4f, window.accept(6f)!!, 0.001f)
        assertEquals(5f, window.accept(7f)!!, 0.001f)
    }

    @Test
    fun readingsFarApartAreNotAveragedIntoADistance() {
        // 1200 to 400 mm is a hand in the way or a laser on the ceiling: the window empties instead.
        assertNull(windowOf(1_200f, 1_190f, 1_210f, 400f))
    }

    @Test
    fun aWindowThatDisagreesRestartsFromNothing() {
        val window = TofWindow()
        window.accept(1_200f)
        window.accept(1_190f)
        window.accept(1_210f)
        assertNull(window.accept(400f))
        // The next two readings are not enough on their own.
        assertNull(window.accept(1_200f))
        assertNull(window.accept(1_190f))
        assertEquals(1_200f, window.accept(1_210f)!!, 0.001f)
    }

    @Test
    fun aReadingWithoutATargetEndsTheWindow() {
        // 1200 -> no target -> 1200: the distance after the gap is not the one before it.
        assertNull(windowOf(1_200f, 1_190f, null))
        assertNull(windowOf(1_200f, 1_190f, 1_210f, null, 1_200f))
    }

    @Test
    fun aDeadSensorPublishesNothingAtAll() {
        assertNull(windowOf(null, null, null, null))
    }

    @Test
    fun aDistanceFarFromTheMedianNeedsMoreSpreadThanItsShare() {
        // 30 mm at 1 m is inside max(50 mm, 10%), and at 4 m 50 mm is still the floor.
        assertEquals(1_000f, windowOf(1_000f, 1_030f, 1_000f)!!, 0.001f)
        assertEquals(4_000f, windowOf(4_000f, 4_030f, 4_000f)!!, 0.001f)
    }

    @Test
    fun brightLightWithholdsTheL8sDistance() {
        val filter = TofFilter(TofWindow(), NEZHA_TOF_MAX_LUX, TofLayout.Bands)
        // Over the ceiling, or at it, nothing gets into the window.
        assertNull(filter.update(600f, 20_000f))
        assertNull(filter.update(600f, NEZHA_TOF_MAX_LUX))
        // Under it, the readings have to agree first.
        assertNull(filter.update(600f, 2_000f))
        assertNull(filter.update(600f, 2_000f))
        assertEquals(600f, filter.update(600f, 2_000f)!!, 0.001f)
    }

    @Test
    fun aCeilingOfZeroLeavesTheLightAlone() {
        val filter = TofFilter(TofWindow(), 0f, TofLayout.Bands)
        assertNull(filter.update(600f, 57_000f))
        assertNull(filter.update(600f, 57_000f))
        assertEquals(600f, filter.update(600f, 57_000f)!!, 0.001f)
        assertNull(filter.update(null, 57_000f))
    }

    @Test
    fun everyOtherLayoutKeepsItsOwnWay() {
        val readings = { key: String -> if (key == TOF_STABILITY_PROPERTY) "1" else null }
        assertNull(tofFilterFor("dada", TofLayout.Single, readings))
    }

    @Test
    fun the17UltraGetsTheWindowAndTheLuxCeiling() {
        val filter = tofFilterFor(NEZHA_DEVICE, TofLayout.Bands) { null }
        assertNotNull(filter)
        assertNull(filter!!.update(600f, 1_000f))
        assertNull(filter.update(600f, 1_000f))
        assertEquals(600f, filter.update(600f, 1_000f)!!, 0.001f)
    }

    @Test
    fun thePropertyTurnsTheWindowOnAnywhere() {
        val filter = tofFilterFor("dada", TofLayout.Bands) { key ->
            if (key == TOF_STABILITY_PROPERTY) "1" else null
        }
        val off = tofFilterFor("dada", TofLayout.Bands) { key ->
            if (key == TOF_STABILITY_PROPERTY) "0" else null
        }
        assertNotNull(filter)
        assertNull(off)
        // No lux ceiling off the 17 Ultra, so the readings alone decide.
        assertNull(filter!!.update(600f, 57_000f))
        assertNull(filter.update(600f, 57_000f))
        assertEquals(600f, filter.update(600f, 57_000f)!!, 0.001f)
    }

    @Test
    fun theLuxCeilingCanBeMovedOrTurnedOff() {
        val raised = tofFilterFor(NEZHA_DEVICE, TofLayout.Bands) { key ->
            if (key == TOF_MAX_LUX_PROPERTY) "40000" else null
        }
        val unlimited = tofFilterFor(NEZHA_DEVICE, TofLayout.Bands) { key ->
            if (key == TOF_MAX_LUX_PROPERTY) "0" else null
        }
        assertNotNull(raised)
        assertNotNull(unlimited)
        // 20,000 lux is over the default ceiling and under a raised one.
        assertNull(raised!!.update(600f, 20_000f))
        assertNull(raised.update(600f, 20_000f))
        assertEquals(600f, raised.update(600f, 20_000f)!!, 0.001f)
        // With no number the ceiling is off, and the window alone decides.
        assertNull(unlimited!!.update(600f, 57_000f))
        assertNull(unlimited.update(600f, 57_000f))
        assertEquals(600f, unlimited.update(600f, 57_000f)!!, 0.001f)
    }

    @Test
    fun aCeilingThatIsNotANumberFallsBackToTheDeviceOne() {
        val filter = tofFilterFor(NEZHA_DEVICE, TofLayout.Bands) { key ->
            if (key == TOF_MAX_LUX_PROPERTY) "bright" else null
        }
        assertNotNull(filter)
        // Over the 17 Ultra's own ceiling, so nothing comes out even at the third reading.
        assertNull(filter!!.update(600f, 20_000f))
        assertNull(filter.update(600f, 20_000f))
        assertNull(filter.update(600f, 20_000f))
    }
}
