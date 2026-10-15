package com.zcode.remote.core

/** A disconnected socket still owns its session while a retry is pending. */
internal class ProbeLifetime {
    @Volatile
    private var generation = 0L
    @Volatile
    var active = false
        private set

    /** Called under the probe's lifecycle lock. */
    fun begin(): Long? {
        if (active) return null
        val token = ++generation
        active = true
        return token
    }

    /** Invalidates callbacks even when the previous socket has already closed. */
    fun end() {
        active = false
        generation++
    }

    fun isCurrent(token: Long): Boolean = active && token == generation
}
