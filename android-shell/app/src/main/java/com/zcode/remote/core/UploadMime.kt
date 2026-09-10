package com.zcode.remote.core

import android.webkit.MimeTypeMap

/**
 * Turns the WebView's `accept` list into the MIME filter the system pickers take.
 *
 * `FileChooserParams.getAcceptTypes()` is the page's `accept` attribute, and it
 * can hold any of the three forms a browser allows: `"image/*"`, `"image/png"`
 * or `".png"`. SAF only understands MIME types, so the dotted form has to be
 * translated.
 *
 * DELIBERATE DIVERGENCE from the HarmonyOS build: that side hands
 * DocumentViewPicker an explicit ~400-entry suffix list
 * (`entry/src/main/ets/common/AllowedFileExtensions.ets`), because the picker
 * API there demands one. Android's equivalent accepts wildcards, and most of
 * those suffixes have no MIME mapping at all — deriving a filter from them would
 * silently hide files the page is happy to accept. So here the page's own
 * `accept` is the contract, and an absent one means "anything", which is also
 * what the same page does in a desktop browser.
 *
 * The extension→MIME lookup is injected so this stays unit-testable on the JVM
 * (MimeTypeMap is an Android framework class).
 */
object UploadMime {

    /** Used when the page declares no `accept`, matching browser behaviour. */
    val ANY = arrayOf("*/*")

    fun normalise(
        acceptTypes: List<String?>?,
        resolveExtension: (String) -> String? = { null },
    ): Array<String> {
        val out = LinkedHashSet<String>()
        acceptTypes.orEmpty().forEach { raw ->
            val token = raw?.trim().orEmpty()
            if (token.isEmpty()) return@forEach
            when {
                // ".png" / "png" — the page named an extension, not a MIME type.
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
