package com.rawr.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleOpticalZoomTest {
    @Test
    fun codesMatchTelezoom() {
        assertEquals(3.2, TeleOpticalZoom.code(75), 1e-9)
        assertEquals(3.64, TeleOpticalZoom.code(85), 1e-9)
        assertEquals(3.86, TeleOpticalZoom.code(90), 1e-9)
        assertEquals(4.3, TeleOpticalZoom.code(100), 1e-9)
        assertEquals(3.2, TeleOpticalZoom.code(50), 1e-9)
        assertEquals(4.3, TeleOpticalZoom.code(120), 1e-9)
    }

    @Test
    fun onlyLensesWithTheZoomKey() {
        val tele = LensCapability("4.3x Normal", "4.3x Normal", "f1:9.70:7.00x5.00:z4.30")
        val short = LensCapability("3.2x Normal", "3.2x Normal", "f1:9.70:7.00x5.00")
        assertEquals(4.3, TeleOpticalZoom.lensCode(tele)!!, 1e-9)
        assertTrue(TeleOpticalZoom.supports(tele))
        assertNull(TeleOpticalZoom.lensCode(short))
        assertFalse(TeleOpticalZoom.supports(short))
        assertFalse(TeleOpticalZoom.supports(null))
    }
}

class TeleOpticalZoomTwinTest {
    private val g = "f1:9.70:7.00x5.00"
    private val lenses = listOf(
        LensCapability("1x Normal", "1x Normal", "f1:6.30:9.80x7.30"),
        LensCapability("3.2x Normal", "3.2x Normal", g),
        LensCapability("3.2x DCG", "3.2x DCG", g),
        LensCapability("4.3x Normal", "4.3x Normal", "$g:z4.30"),
        LensCapability("4.3x DCG", "4.3x DCG", "$g:z4.30")
    )

    @Test
    fun fixedTeleLensesGoToTheirTwin() {
        assertEquals("4.3x DCG", TeleOpticalZoom.twinOf(lenses[2], lenses)?.id)
        assertEquals("4.3x Normal", TeleOpticalZoom.twinOf(lenses[1], lenses)?.id)
        assertNull(TeleOpticalZoom.twinOf(lenses[0], lenses))
        assertNull(TeleOpticalZoom.twinOf(lenses[3], lenses))
        assertEquals(listOf("1x Normal", "4.3x Normal", "4.3x DCG"), TeleOpticalZoom.visibleLenses(lenses).map { it.id })
    }

    @Test
    fun buttonNamesFollowTheOptics() {
        assertEquals("3.2x DCG", TeleOpticalZoom.displayName(lenses[4], 75))
        assertEquals("3.6x Normal", TeleOpticalZoom.displayName(lenses[3], 85))
        assertEquals("4.3x Normal", TeleOpticalZoom.displayName(lenses[3], 100))
        assertEquals("1x Normal", TeleOpticalZoom.displayName(lenses[0], 85))
    }
}
