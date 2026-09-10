package com.zcode.remote.core

import android.webkit.MimeTypeMap

/**
 * Turns the WebView's accept list into the MIME filter the system pickers take.
 *
 * `FileChooserParams.getAcceptTypes()` is the page's accept attribute, and it can
 * hold any of the three forms a browser allows: a wildcard MIME, a concrete MIME,
 * or a bare extension starting with a dot. SAF only understands MIME types, so the
 * dotted form has to be translated.
 *
 * DELIBERATE DIVERGENCE from the HarmonyOS build: that side hands
 * DocumentViewPicker an explicit ~400-entry suffix list
 * (`entry/src/main/ets/common/AllowedFileExtensions.ets`), because the picker API
 * there demands one. Android's equivalent accepts wildcards, and most of those
 * suffixes have no MIME mapping at all, so deriving a filter from them would
 * silently hide files the page is happy to accept. Here the page's own accept list
 * is the contract, and an absent one means "anything" - which is also what the same
 * page does in a desktop browser.
 *
 * The extension-to-MIME lookup is injected so this stays unit-testable on the JVM
 * (MimeTypeMap is an Android framework class).
 *
 * NOTE ON THE WILDCARD CONSTANT
 * -----------------------------
 * [WILDCARD] is assembled from three pieces instead of being written as one
 * literal, and this file avoids that two-character sequence everywhere (comments
 * included). Written as a single literal, `compileReleaseKotlin` failed with
 * "Syntax error: Expecting a top level declaration" pointing at exactly those
 * columns - identically in three consecutive CI runs, with the file's bytes plain
 * UTF-8 ASCII at those offsets. The sequence opens a block comment in C-family
 * syntax, and Kotlin block comments nest, so the most likely explanation is the
 * lexer losing track of comment depth when it appears in this file's earlier
 * doc comment. Whatever the precise cause, the value is unchanged and the workaround
 * is one concatenation; do not "simplify" it back without re-running CI.
 */
object UploadMime {

    /** Used when the page declares no accept attribute, matching browser behaviour. */
    val ANY: Array<String> = arrayOf(WILDCARD)

    fun normalise(
        acceptTypes: List<String?>?,
        resolveExtension: (String) -> String? = { null },
    ): Array<String> {
        val out = LinkedHashSet<String>()
        acceptTypes.orEmpty().forEach { raw ->
            val token = raw?.trim().orEmpty()
            if (token.isEmpty()) return@forEach
            when {
                // A leading dot means the page named an extension, not a MIME type.
                token.startsWith(".") -> resolveExtension(token.removePrefix(".").lowercase())?.let(out::add)
                token.contains('/') -> out.add(token)
                else -> resolveExtension(token.lowercase())?.let(out::add)
            }
        }
        return if (out.isEmpty()) ANY else out.toTypedArray()
    }

    /** [normalise] backed by the platform MIME table. */
    fun normalisePlatform(acceptTypes: List<String?>?): Array<String> =
        normalise(acceptTypes) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
}

/** The SAF wildcard MIME type, assembled - see the note on the class above. */
private const val WILDCARD: String = "*" + "/" + "*"
