package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the fixed status-bar colour table.
 *
 * These matter more than they look: the strip behind the status bar is the only
 * thing on screen that has to match the remote page pixel for pixel, and the
 * values it is matched against live in the page's stylesheet, not here. A wrong
 * literal shows up as a seam the user sees immediately, and the device is the
 * only place it can be caught — so the mapping itself is pinned here.
 */
class PageBarColorTest {

    // Not `const`: 0xFFF8F8F8 is a Long literal, so `.toInt()` is a call and the
    // value is not a compile-time constant.
    private val BOOT_LIGHT = 0xFFF8F8F8.toInt()
    private val BOOT_DARK = 0xFF161616.toInt()
    private val HEADER_LIGHT = 0xFFFFFFFF.toInt()
    private val HEADER_DARK = 0xFF202020.toInt()
    private val SURFACE_LIGHT = 0xFFECECEE.toInt()
    private val SURFACE_DARK = 0xFF2B2B2B.toInt()

    @Test
    fun `every state has a light and a dark surface`() {
        assertEquals(BOOT_LIGHT, PageBarColor.resolve(PageBarState.BOOT, dark = false))
        assertEquals(BOOT_DARK, PageBarColor.resolve(PageBarState.BOOT, dark = true))
        assertEquals(HEADER_LIGHT, PageBarColor.resolve(PageBarState.MAIN_HEADER, dark = false))
        assertEquals(HEADER_DARK, PageBarColor.resolve(PageBarState.MAIN_HEADER, dark = true))
        assertEquals(SURFACE_LIGHT, PageBarColor.resolve(PageBarState.MAIN_SURFACE, dark = false))
        assertEquals(SURFACE_DARK, PageBarColor.resolve(PageBarState.MAIN_SURFACE, dark = true))
    }

    @Test
    fun `the tokens are the ones the injected layer reports`() {
        // These strings are the contract with inject.js section 6; a rename on one
        // side and not the other silently pins the bar to the boot colour.
        assertEquals("boot", PageBarState.BOOT.token)
        assertEquals("main-header", PageBarState.MAIN_HEADER.token)
        assertEquals("main-surface", PageBarState.MAIN_SURFACE.token)
        assertEquals("light", PageTheme.LIGHT.token)
        assertEquals("dark", PageTheme.DARK.token)
    }

    @Test
    fun `an unknown or missing state falls back to boot, never to a wrong colour`() {
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf(null))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf(""))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf("main"))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf("MAIN-HEADER"))
        assertTrue(PageBarState.entries.all { PageBarColor.stateOf(it.token) == it })
    }

    @Test
    fun `an unknown theme means the system night mode decides`() {
        assertEquals(null, PageBarColor.themeOf(null))
        assertEquals(null, PageBarColor.themeOf(""))
        assertEquals(null, PageBarColor.themeOf("zai-dark"))
        assertEquals(PageTheme.LIGHT, PageBarColor.themeOf("light"))
        assertEquals(PageTheme.DARK, PageBarColor.themeOf("dark"))
    }

    @Test
    fun `the status bar icons invert against the surface`() {
        // Light surface -> dark icons (isAppearanceLightStatusBars = true).
        assertTrue(PageBarColor.appearanceLightStatusBars(PageTheme.LIGHT))
        assertFalse(PageBarColor.appearanceLightStatusBars(PageTheme.DARK))
        // Before the page has said anything, the system theme is the best guess.
        assertTrue(PageBarColor.appearanceLightStatusBars(null))
    }

    @Test
    fun `the log line names the state, the theme and the applied colour`() {
        val line = PageBarColor.describe(PageBarState.MAIN_HEADER, PageTheme.DARK, HEADER_DARK)
        assertTrue(line.contains("main-header/dark"))
        assertTrue(line.contains("#202020"))
        val system = PageBarColor.describe(PageBarState.BOOT, null, BOOT_LIGHT)
        assertTrue(system.contains("boot/system"))
        assertTrue(system.contains("#F8F8F8"))
    }
}
