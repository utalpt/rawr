package com.rawr.camera.i18n

import java.io.File
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rawr 17U fork: UI translation lookups. */
class I18nTest {
    @AfterTest fun reset() = I18n.install(emptyMap())

    @Test fun englishWhenNothingIsLoaded() {
        I18n.install(emptyMap())
        assertEquals("Exposure", tr("Exposure"))
    }

    @Test fun exactCapitalsAndJoinedTexts() {
        I18n.install(mapOf("Exposure" to "Exposição", "Photo" to "Foto", "Video" to "Vídeo"))
        assertEquals("Exposição", tr("Exposure"))
        assertEquals("EXPOSIÇÃO", tr("EXPOSURE"))
        assertEquals("Foto · Vídeo", tr("Photo · Video"))
        assertEquals("Unknown", tr("Unknown"))
    }

    @Test fun halfTranslatedJoinsStayEnglish() {
        I18n.install(mapOf("Photo" to "Foto"))
        assertEquals("Photo · Other", tr("Photo · Other"))
        assertEquals("Foto · 12", tr("Photo · 12"))
    }

    @Test fun identityEntryStopsTheCapitalsFallback() {
        I18n.install(mapOf("Film" to "Filme", "FILM" to "FILM"))
        assertEquals("FILM", tr("FILM"))
        assertEquals("Filme", tr("Film"))
    }

    /** Every bundled language carries every text any bundled language has, so none falls behind the others. */
    @Test fun bundledLanguagesCoverTheSameTexts() {
        val dir = File("src/main/assets/i18n")
        val files = I18n.bundled.associate { lang ->
            val file = File(dir, "${lang.code}.json")
            assertTrue(file.isFile, "missing ${file.path}")
            val json = JSONObject(file.readText())
            assertEquals(lang.code, json.optString("@code"), "@code of ${file.name}")
            lang.code to I18n.parse(file.readText()).keys
        }
        val all = files.values.flatten().toSortedSet()
        files.forEach { (code, keys) ->
            val missing = all - keys
            assertTrue(missing.isEmpty(), "$code.json lacks ${missing.size} texts, e.g. ${missing.take(5)}")
        }
    }
}
