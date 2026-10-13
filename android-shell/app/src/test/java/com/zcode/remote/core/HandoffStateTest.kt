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

    @Test
    fun `pre-start arrival invalidates background work and formal start reuses its epoch`() {
        val state = HandoffState()
        val background = state.transition(true)!!
        state.claimCarrier(background)
        assertTrue(state.prepareForegroundArrival())
        assertFalse("arrival preparation blocks a claim before the foreground transition", state.claimCarrier(background))
        val arrival = state.transition(false)!!
        assertTrue(state.foregroundArrivalPending)
        assertFalse(state.claimCarrier(background))
        assertFalse(state.ownsAwayWindow)
        assertFalse(state.prepareForegroundArrival())
        assertEquals(arrival, state.epoch)
        state.confirmForegroundArrival()
        assertFalse(state.foregroundArrivalPending)
        assertNull(state.transition(false))
        assertEquals(arrival, state.epoch)
    }

    @Test
    fun `aborted arrival restores background ownership with a new token`() {
        val state = HandoffState()
        state.transition(true)
        state.prepareForegroundArrival()
        val arrival = state.transition(false)!!
        assertTrue(state.cancelForegroundArrival(arrival))
        val background = state.transition(true)!!
        assertFalse(state.isCurrent(arrival, false))
        assertTrue(state.claimCarrier(background))
    }

    @Test
    fun `an old activity cannot cancel the next activity's arrival`() {
        val state = HandoffState()
        state.transition(true)
        state.prepareForegroundArrival()
        val oldArrival = state.transition(false)!!
        state.confirmForegroundArrival()
        state.transition(true)
        state.prepareForegroundArrival()
        val newArrival = state.transition(false)!!
        assertFalse(state.cancelForegroundArrival(oldArrival))
        assertTrue(state.foregroundArrivalPending)
        assertTrue(state.isCurrent(newArrival, false))
    }

    @Test
    fun `screen-off cancels preparation even before formal foreground arrives`() {
        val state = HandoffState()
        state.transition(true)
        state.prepareForegroundArrival()
        state.transition(false)
        val background = state.transition(true)!!
        assertFalse(state.foregroundArrivalPending)
        assertTrue(state.claimCarrier(background))
    }

    @Test
    fun `cold initialization and an already visible app do not create a return`() {
        val state = HandoffState()
        assertFalse(state.prepareForegroundArrival())
        assertEquals(0L, state.epoch)
        state.transition(false)
        assertFalse(state.prepareForegroundArrival())
        assertFalse(state.foregroundArrivalPending)
    }
}
