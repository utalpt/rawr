package com.rawr.camera.integration

import kotlin.test.*

// The watch module (wear/) runs the same test against its own copy: the same bytes on both sides.
class WatchRemoteProtocolTest {
    @Test fun frameHeaderBytesAreStable() {
        val out = WatchRemoteProtocol.encodeFrame(seq = 258, rotationDegrees = 270, ready = true, jpeg = byteArrayOf(9))
        assertContentEquals(byteArrayOf(0x52, 0x57, 1, 1, 0, 0, 1, 2, 1, 14, 0, 0, 9), out)
        val header = assertNotNull(WatchRemoteProtocol.decodeHeader(out))
        assertEquals(258, header.seq)
        assertEquals(270, header.rotationDegrees)
        assertTrue(header.ready)
    }

    @Test fun rotationIsNormalised() {
        assertEquals(270, WatchRemoteProtocol.decodeHeader(WatchRemoteProtocol.encodeFrame(1, -90, false, ByteArray(0)))?.rotationDegrees)
    }

    @Test fun intsRoundTrip() {
        for (v in listOf(0, 1, 255, 256, 70000, Int.MAX_VALUE, -1)) assertEquals(v, WatchRemoteProtocol.decodeInt(WatchRemoteProtocol.encodeInt(v)))
        assertNull(WatchRemoteProtocol.decodeInt(ByteArray(3)))
    }

    @Test fun foreignBytesAreRejected() {
        assertNull(WatchRemoteProtocol.decodeHeader(ByteArray(5)))
        assertNull(WatchRemoteProtocol.decodeHeader(byteArrayOf(0x52, 0x58, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0)))
        assertNull(WatchRemoteProtocol.decodeHeader(byteArrayOf(0x52, 0x57, 2, 0, 0, 0, 0, 1, 0, 0, 0, 0)))
    }

    @Test fun statusRoundTripsWithItsLenses() {
        val status = WatchRemoteProtocol.Status(
            ready = true, reason = "", mode = WatchRemoteProtocol.MODE_VIDEO, recording = true, recordingMs = 12345L,
            lenses = listOf(
                WatchRemoteProtocol.Lens("ultra", "0.5x", "13mm", false),
                WatchRemoteProtocol.Lens("main", "1x", "50MP", true)
            )
        )
        val back = assertNotNull(WatchRemoteProtocol.decodeStatus(WatchRemoteProtocol.encodeStatus(status)))
        assertTrue(back.ready)
        assertEquals(WatchRemoteProtocol.MODE_VIDEO, back.mode)
        assertTrue(back.recording)
        assertEquals(12345L, back.recordingMs)
        assertEquals(listOf("ultra", "main"), back.lenses.map { it.id })
        assertEquals(listOf("0.5x", "1x"), back.lenses.map { it.label })
        assertEquals(listOf(false, true), back.lenses.map { it.selected })
    }

    @Test fun anOlderStatusWithoutTheNewFieldsStillDecodes() {
        val back = assertNotNull(WatchRemoteProtocol.decodeStatus("""{"ready":false,"reason":"Open Rawr"}""".toByteArray()))
        assertFalse(back.ready)
        assertEquals("Open Rawr", back.reason)
        assertEquals(WatchRemoteProtocol.MODE_PHOTO, back.mode)
        assertTrue(back.lenses.isEmpty())
        assertNull(WatchRemoteProtocol.decodeStatus("not json".toByteArray()))
    }

    @Test fun commandsRoundTrip() {
        assertEquals(
            WatchRemoteProtocol.CMD_LENS to "tele 3",
            WatchRemoteProtocol.decodeCommand(WatchRemoteProtocol.encodeCommand(WatchRemoteProtocol.CMD_LENS, "tele 3"))
        )
        assertNull(WatchRemoteProtocol.decodeCommand("{}".toByteArray()))
    }

    @Test fun theWatchTurnsThePictureTheOppositeWayFromTheDeviceRotation() {
        assertEquals(0, WatchRemoteProtocol.watchTurnDegrees(0))
        assertEquals(270, WatchRemoteProtocol.watchTurnDegrees(90))
        assertEquals(180, WatchRemoteProtocol.watchTurnDegrees(180))
        assertEquals(90, WatchRemoteProtocol.watchTurnDegrees(270))
        assertEquals(90, WatchRemoteProtocol.watchTurnDegrees(-90))
        assertEquals(270, WatchRemoteProtocol.watchTurnDegrees(450))
    }
}
