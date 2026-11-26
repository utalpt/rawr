package com.rawr.camera.settings.ui

import com.rawr.camera.settings.model.FilmSimLook
import kotlin.test.*

class PhotoPresetNameTest {
    @Test fun aRawrPhotoIsNamedByItsTimeAndStock() {
        assertEquals("21:32 Velvia 100", photoPresetName("RAWR_20261126_213228_173", FilmSimLook(film = 18)))
        assertEquals("20:45 Portra 400", photoPresetName("RAWR_20261126_204542_710_MF17", FilmSimLook(film = 2)))
    }

    @Test fun anyOtherFileKeepsItsName() {
        assertEquals("IMG_0042", photoPresetName("IMG_0042", FilmSimLook()))
    }
}
