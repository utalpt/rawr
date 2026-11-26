package com.rawr.camera.settings.preferences

import java.io.ByteArrayInputStream
import kotlin.test.*

class PhotoRecipeTest {
    private fun photo(prefix: ByteArray, recipe: String): ByteArray {
        // As JpegExif.cpp / DngProvenance.cpp write it: the recipe as a JSON string value under its key.
        val escaped = recipe.replace("\\", "\\\\").replace("\"", "\\\"")
        val note = "RawrRecipe\u0000{\"com.rawrcam.processing.recipe.v1\":\"$escaped\"}"
        return prefix + ByteArray(100_000) { 7 } + note.toByteArray(Charsets.UTF_8) + ByteArray(1000)
    }

    @Test fun findsTheRecipeInAJpegAcrossChunks() {
        val recipe = """{"version":1,"filmSimEnabled":true,"film":{"film":2,"name":"Pörtra \"400\" \/ ok"}}"""
        val bytes = photo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()), recipe)
        assertTrue(PhotoRecipe.looksLikePhoto(bytes.copyOf(4)))
        assertEquals(recipe, PhotoRecipe.find(ByteArrayInputStream(bytes)))
    }

    @Test fun findsTheRecipeInADng() {
        val recipe = """{"version":1,"filmSimEnabled":false}"""
        val bytes = photo("II".toByteArray() + byteArrayOf(42, 0), recipe)
        assertTrue(PhotoRecipe.looksLikePhoto(bytes.copyOf(4)))
        assertEquals(recipe, PhotoRecipe.find(ByteArrayInputStream(bytes)))
    }

    @Test fun aPhotoWithoutARecipeGivesNull() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(5000) { 1 }
        assertNull(PhotoRecipe.find(ByteArrayInputStream(bytes)))
        assertFalse(PhotoRecipe.looksLikePhoto("{\"fo".toByteArray()))
    }
}
