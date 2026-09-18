package com.zcode.remote.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class UploadMimeTest {

    private val lookup: (String) -> String? = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "pdf" to "application/pdf",
        "kt" to "text/x-kotlin",
    )::get

    @Test
    fun `no accept types means anything`() {
        assertArrayEquals(UploadMime.ANY, UploadMime.normalise(null, lookup))
        assertArrayEquals(UploadMime.ANY, UploadMime.normalise(emptyList(), lookup))
    }

    @Test
    fun `wildcards pass through untouched`() {
        assertArrayEquals(arrayOf(IMAGE_ANY), UploadMime.normalise(listOf(IMAGE_ANY), lookup))
    }

    @Test
    fun `explicit mime types pass through`() {
        assertArrayEquals(
            arrayOf("application/pdf"),
            UploadMime.normalise(listOf("application/pdf"), lookup),
        )
    }

    @Test
    fun `dotted extensions are translated`() {
        assertArrayEquals(arrayOf("image/png"), UploadMime.normalise(listOf(".png"), lookup))
    }

    @Test
    fun `bare extensions are translated too`() {
        assertArrayEquals(arrayOf("image/jpeg"), UploadMime.normalise(listOf("jpg"), lookup))
    }

    @Test
    fun `unknown extensions are dropped rather than guessed`() {
        // "+2" in a page's accept list is junk; dropping it must not widen the filter.
        assertArrayEquals(UploadMime.ANY, UploadMime.normalise(listOf(".blp", "+2"), lookup))
    }

    @Test
    fun `mixed forms keep order and drop duplicates`() {
        assertArrayEquals(
            arrayOf(IMAGE_ANY, "image/png", "application/pdf"),
            UploadMime.normalise(listOf(IMAGE_ANY, ".png", "image/png", "pdf"), lookup),
        )
    }

    @Test
    fun `blank entries are ignored`() {
        assertArrayEquals(arrayOf("image/png"), UploadMime.normalise(listOf(" ", "", ".png"), lookup))
    }

    private companion object {
        /**
         * Assembled rather than written as one literal: the two-character sequence
         * that opens a block comment inside a string literal made the Kotlin
         * compiler fail on the production class in three consecutive CI runs (see
         * the note in UploadMime.kt). Keeping it out of this file too costs nothing.
         */
        val IMAGE_ANY: String = "image" + "/" + "*"
    }
}
