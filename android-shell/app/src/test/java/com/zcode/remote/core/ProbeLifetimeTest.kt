package com.zcode.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLifetimeTest {
    @Test
    fun `a failed socket awaiting retry keeps ownership and refuses duplicate start`() {
        val lifetime = ProbeLifetime()
        val retryToken = lifetime.begin()!!
        // Socket failure changes transport phase, not the session lifetime.
        assertTrue(lifetime.active)
        assertNull(lifetime.begin())
        assertTrue(lifetime.isCurrent(retryToken))
        lifetime.end()
        assertFalse(lifetime.active)
        assertFalse(lifetime.isCurrent(retryToken))
    }

    @Test
    fun `a retry finishing slow cleanup cannot reconnect after foreground stop`() {
        val lifetime = ProbeLifetime()
        val retryToken = lifetime.begin()!!
        var reconnected = false
        val finishCleanup = { if (lifetime.isCurrent(retryToken)) reconnected = true }
        lifetime.end()
        finishCleanup()
        assertFalse(reconnected)
    }

    @Test
    fun `old bootstrap and cleanup cannot send through a subsequent background session`() {
        val lifetime = ProbeLifetime()
        val old = lifetime.begin()!!
        lifetime.end()
        val current = lifetime.begin()!!
        assertNotEquals(old, current)
        var sent = false
        if (lifetime.isCurrent(old)) sent = true
        assertFalse(sent)
        assertTrue(lifetime.isCurrent(current))
    }

    @Test
    fun `an already firing old duration timer cannot stop the new session`() {
        val lifetime = ProbeLifetime()
        val old = lifetime.begin()!!
        val expiredTimer = { if (lifetime.isCurrent(old)) lifetime.end() }
        lifetime.end()
        val current = lifetime.begin()!!
        expiredTimer()
        assertTrue(lifetime.active)
        assertTrue(lifetime.isCurrent(current))
    }
}
