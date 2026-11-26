package com.rawr.camera.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sweep's instrument: the median and the spread of the readings a scene produces, as `docs/aux-sensors.md` asks. */
class TofSamplesTest {

    private fun sample(
        distanceMm: Float?,
        status: String = "0",
        lux: Float? = 1_000f,
        cctK: Float? = 5_200f,
        atNs: Long = 0L
    ) = TofSample(distanceMm, status, lux, cctK, atNs)

    @Test
    fun theSummaryReportsTheMedianAndTheMedianDeviation() {
        val samples = TofSamples()
        listOf(1_018f, 1_040f, 1_072f).forEach { samples.record(sample(it)) }
        val summary = samples.summary()!!
        assertEquals(3, summary.count)
        assertEquals(1_040f, summary.medianMm!!, 0.001f)
        // |1018-1040| = 22, |1040-1040| = 0, |1072-1040| = 32, whose median is 22.
        assertEquals(22f, summary.madMm!!, 0.001f)
        assertEquals(1_018f, summary.minMm!!, 0.001f)
        assertEquals(1_072f, summary.maxMm!!, 0.001f)
        assertEquals(0, summary.withoutTarget)
    }

    @Test
    fun aReadingWithoutATargetIsCountedAndLeftOutOfTheNumbers() {
        val samples = TofSamples()
        listOf(1_000f, null, 1_010f, null, 1_020f).forEach { samples.record(sample(it)) }
        val summary = samples.summary()!!
        assertEquals(5, summary.count)
        assertEquals(2, summary.withoutTarget)
        assertEquals(1_010f, summary.medianMm!!, 0.001f)
        assertEquals(1_000f, summary.minMm!!, 0.001f)
        assertEquals(1_020f, summary.maxMm!!, 0.001f)
    }

    @Test
    fun aWindowOfNothingHasNoSummary() {
        assertNull(TofSamples().summary())
    }

    @Test
    fun theRingKeepsTheLastFiftyReadings() {
        val samples = TofSamples(capacity = 3)
        listOf(1f, 2f, 3f, 900f, 901f).forEach { samples.record(sample(it)) }
        val summary = samples.summary()!!
        assertEquals(3, summary.count)
        assertEquals(5, summary.seen)
        assertEquals(3f, summary.minMm!!, 0.001f)
        assertEquals(901f, summary.maxMm!!, 0.001f)
        // 3, 900 and 901 are what the ring kept, so 900 is the median.
        assertEquals(900f, summary.medianMm!!, 0.001f)
        assertEquals(1f, summary.madMm!!, 0.001f)
    }

    @Test
    fun theLightOfTheLastReadingIsWhatTheSummaryCarries() {
        val samples = TofSamples()
        samples.record(sample(1_000f, lux = 4_000f, cctK = 3_300f))
        samples.record(sample(1_010f, lux = 57_000f, cctK = 8_000f))
        val summary = samples.summary()!!
        assertEquals(57_000f, summary.lux!!, 0.001f)
        assertEquals(8_000f, summary.cctK!!, 0.001f)
    }

    @Test
    fun theSampleLineCarriesTheReadingAndTheStatus() {
        val line = sample(1_040f, status = "0,255,0,-", lux = 57_000f, cctK = 8_290f, atNs = 12_345_000_000L)
            .asLogLine()
        assertEquals("TOF_SAMPLE d=1040 s=0,255,0,- lux=57000 cct=8290 t=12345", line)
    }

    @Test
    fun aReadingWithoutADistanceOrLightLogsADash() {
        val line = sample(null, status = "255", lux = null, cctK = null).asLogLine()
        assertEquals("TOF_SAMPLE d=- s=255 lux=- cct=- t=0", line)
    }

    @Test
    fun theSummaryLineCarriesTheNumbers() {
        val samples = TofSamples()
        listOf(1_018f, 1_040f, 1_072f).forEach { samples.record(sample(it, lux = 57_000f, cctK = 5_200f)) }
        assertEquals(
            "TOF_SUMMARY n=3 seen=3 median=1040 mad=22 min=1018 max=1072 noTarget=0 lux=57000 cct=5200",
            samples.summary()!!.asLogLine()
        )
    }

    @Test
    fun theStatusTextOfTheL8KeepsTheBandsApart() {
        val values = floatArrayOf(993f, 0f, 1_018f, 255f, 1_040f, 0f, 1_072f, 0f)
        assertEquals("0,255,0,0", tofStatusText(values, TofLayout.Bands))
        assertEquals("0", tofStatusText(values, TofLayout.Single))
        // A short array (the vendor sensor handing fewer pairs) still formats.
        assertEquals("0,-", tofStatusText(floatArrayOf(600f, 0f), TofLayout.Bands).take(3))
    }

    @Test
    fun anEmptyLogProducesNoLines() {
        val log = TofLog { "0" }
        val lines = (1..25).flatMap { log.record(sample(1_000f)) }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aLineEveryReadingAndASummaryEveryTen() {
        val log = TofLog { "1" }
        val lines = (1..10).flatMap { i -> log.record(sample(1_000f + i)) }
        // Ten sample lines and one summary, the summary last.
        assertEquals(11, lines.size)
        assertEquals(10, lines.count { it.startsWith("TOF_SAMPLE") })
        assertEquals(1, lines.count { it.startsWith("TOF_SUMMARY") })
        assertTrue(lines.last().startsWith("TOF_SUMMARY n=10 seen=10"))
    }

    @Test
    fun thePropertyIsReadAgainSoSetpropTakesEffect() {
        var enabled = false
        val log = TofLog { if (enabled) "1" else "0" }
        // The first ten readings are off, so nothing comes out, and the property is read again at the tenth.
        assertTrue((1..9).flatMap { log.record(sample(1_000f)) }.isEmpty())
        enabled = true
        // The tenth reading is where the flag is read again: the reading after it is the first to be logged.
        log.record(sample(1_000f))
        val after = log.record(sample(1_010f))
        assertEquals(1, after.size)
        assertTrue(after.first().startsWith("TOF_SAMPLE d=1010"))
    }
}
