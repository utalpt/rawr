package com.rawr.camera.model

import com.rawr.camera.fixtures.CaptureFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFpsCeilingTest {
    private fun state(max: Int, fps: Int) =
        CaptureUiState(CaptureFixtures.baseline().copy(videoFpsMax = max)).copy(videoFps = fps)

    @Test
    fun noCeilingKeepsEveryRate() {
        assertEquals(listOf(24, 30, 60), state(0, 60).videoFpsChoices())
        assertEquals(60, state(0, 60).effectiveVideoFps())
    }

    @Test
    fun aThirtyFpsLensShowsThirty() {
        assertEquals(listOf(24, 30), state(30, 60).videoFpsChoices())
        assertEquals(30, state(30, 60).effectiveVideoFps())
        assertEquals(24, state(30, 24).effectiveVideoFps())
    }
}
