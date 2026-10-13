package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffStateTest {
    @Test
    fun `a quick return cancels the pending background claim`() {
        val state = HandoffState()
        val background = state.transition(true)!!
        state.transition(false)
        assertFalse(state.claimCarrier(background))
        assertFalse(state.ownsAwayWindow)
    }

    @Test
    fun `a second background window never accepts the first window's callbacks`() {
        val state = HandoffState()
        val first = state.transition(true)!!
        val foreground = state.transition(false)!!
        val second = state.transition(true)!!
        assertFalse(state.isCurrent(first, true))
        assertFalse(state.isCurrent(foreground, false))
        assertTrue(state.claimCarrier(second))
    }

    @Test
    fun `duplicate screen and lifecycle signals do not invalidate a handoff`() {
        val state = HandoffState()
        val token = state.transition(true)!!
        assertNull(state.transition(true))
        assertEquals(token, state.epoch)
        assertTrue(state.claimCarrier(token))
        assertNull(state.transition(true))
        assertTrue(state.ownsAwayWindow)
    }

    @Test
    fun `foreground always releases ownership and cannot claim the carrier`() {
        val state = HandoffState()
        assertTrue(state.claimCarrier(state.transition(true)!!))
        val token = state.transition(false)!!
        assertFalse(state.ownsAwayWindow)
        assertFalse(state.claimCarrier(token))
    }

    @Test
    fun `manual release preserves the cancellation token`() {
        val state = HandoffState()
        val token = state.transition(true)!!
        state.claimCarrier(token)
        state.releaseCarrier()
        assertFalse(state.ownsAwayWindow)
        assertTrue(state.isCurrent(token, true))
    }
}
