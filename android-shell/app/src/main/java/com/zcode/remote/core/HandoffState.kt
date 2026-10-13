package com.zcode.remote.core

/** Main-thread ownership and cancellation for foreground/background handoffs. */
class HandoffState {
    @Volatile
    var epoch = 0L
        private set
    var away = false
        private set
    var ownsAwayWindow = false
        private set
    @Volatile
    var foregroundArrivalPending = false
        private set
    private var initialized = false

    /** A lifecycle pre-callback signals arrival before the started counter changes. */
    fun prepareForegroundArrival(): Boolean {
        if (!initialized || !away || foregroundArrivalPending) return false
        foregroundArrivalPending = true
        return true
    }

    fun confirmForegroundArrival() {
        foregroundArrivalPending = false
    }

    fun cancelForegroundArrival(token: Long): Boolean {
        if (!foregroundArrivalPending || token != epoch) return false
        foregroundArrivalPending = false
        return true
    }

    /** Duplicate lifecycle/screen signals must not restart the same handoff. */
    fun transition(nextAway: Boolean): Long? {
        if (nextAway) foregroundArrivalPending = false
        if (initialized && away == nextAway) return null
        initialized = true
        away = nextAway
        ownsAwayWindow = false
        return ++epoch
    }

    fun isCurrent(token: Long, expectedAway: Boolean): Boolean =
        token == epoch && away == expectedAway

    fun claimCarrier(token: Long): Boolean {
        if (foregroundArrivalPending || !isCurrent(token, true)) return false
        ownsAwayWindow = true
        return true
    }

    fun releaseCarrier() {
        ownsAwayWindow = false
    }

    companion object {
        /** Short switches cancel before a connection is taken away from the page. */
        const val START_DELAY_MS = 200L
        const val RETURN_CHECK_MS = 500L
    }
}
