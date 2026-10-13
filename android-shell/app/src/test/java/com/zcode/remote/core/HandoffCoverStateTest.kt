package com.zcode.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffCoverStateTest {
    private fun returning(): HandoffCoverState = HandoffCoverState().apply {
        carrierMatched(1L, true, "old")
        beginReturn()
        pageStarted()
    }

    @Test
    fun `only a background pairing with a known page can cover`() {
        val state = HandoffCoverState()
        assertFalse(state.carrierMatched(1L, false, "old"))
        assertFalse(state.carrierMatched(1L, true, ""))
        assertFalse(state.visible)
        assertTrue(state.carrierMatched(1L, true, "old"))
        assertFalse(state.carrierMatched(1L, true, "old"))
    }

    @Test
    fun `old document and injection ready alone cannot dismiss the cover`() {
        val state = returning()
        state.pageReady("old")
        assertNull(state.contentCandidate("old"))
        assertNull(state.contentCandidate("new"))
        state.pageReady("new")
        assertTrue(state.visible)
        val ticket = state.contentCandidate("new")!!
        assertTrue(state.visible)
        assertTrue(state.pagePresented("new", ticket))
        assertFalse(state.visible)
    }

    @Test
    fun `a later navigation invalidates the prior visual commit and document`() {
        val state = returning()
        state.pageReady("first")
        val oldTicket = state.contentCandidate("first")!!
        state.pageStarted()
        state.pageReady("first")
        assertNull(state.contentCandidate("first"))
        state.pageReady("second")
        val ticket = state.contentCandidate("second")!!
        assertFalse(state.pagePresented("first", oldTicket))
        assertFalse(state.pageFailed("first", true))
        assertTrue(state.visible)
        assertTrue(state.pagePresented("second", ticket))
    }

    @Test
    fun `late self kick stays covered while a new foreground kick is revealed`() {
        val state = returning()
        assertFalse(state.pageFailed("old", false))
        assertTrue(state.visible)
        state.pageReady("new")
        val ticket = state.contentCandidate("new")!!
        assertTrue(state.pageFailed("new", true))
        assertFalse(state.pagePresented("new", ticket))
        assertFalse(state.visible)
    }

    @Test
    fun `foreground failure on the source document is also revealed`() {
        val state = returning()
        assertTrue(state.pageFailed("old", true))
        assertFalse(state.visible)
    }

    @Test
    fun `unattributed prior kick prevents masking that background window`() {
        val state = HandoffCoverState()
        assertFalse(state.carrierKicked(1L))
        assertFalse(state.carrierMatched(1L, true, "old"))
        assertTrue(state.carrierMatched(2L, true, "new"))
    }

    @Test
    fun `native kick reveals the conflict and prevents covering it again`() {
        val state = HandoffCoverState()
        state.carrierMatched(1L, true, "old")
        assertTrue(state.carrierKicked(1L))
        assertFalse(state.carrierMatched(1L, true, "old"))
        assertFalse(state.visible)
    }

    @Test
    fun `old carrier callbacks and timeout cannot cancel a new window`() {
        val state = returning()
        state.clear()
        state.carrierMatched(2L, true, "new")
        state.beginReturn()
        assertFalse(state.carrierKicked(1L))
        assertFalse(state.expire(1L))
        assertTrue(state.visible)
    }

    @Test
    fun `timeout starts only after return and dismisses instead of reloading`() {
        val state = HandoffCoverState()
        state.carrierMatched(1L, true, "old")
        assertFalse(state.expire(1L))
        state.beginReturn()
        assertNull(state.beginReturn())
        assertTrue(state.expire(1L))
        assertFalse(state.visible)
        assertFalse(state.expire(1L))
    }
}
