package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * relayProof 与页面实现逐字对齐的回归锚（docs/05 快照 @4696180）：
 * `base64url(HMAC-SHA256(key=passHash, msg=nonce|role|deviceSid))`，无填充。
 * 向量由 node crypto 以同一算法离线生成（2026-09-13）。
 */
class Tier2ProofTest {

    @Test
    fun proofMatchesThePageAlgorithm() {
        assertEquals(
            "zjwQIGBnBdXIw4FjcrL2CWJ1_wgs_uObTtQcpvWWQOY",
            relayProof("test-pass-hash", "test-nonce-1", "terminal", "device-001"),
        )
        assertEquals(
            "nBirF2y2KBlcYo75gGzHqv6xurhVxksEUU3y7KrBTK0",
            relayProof("abc", "2026-09-13T01:00:00Z", "terminal", "sid-xyz"),
        )
    }

    @Test
    fun differentRolesChangeTheProof() {
        val withTerminal = relayProof("hash", "nonce", "terminal", "sid")
        val withOther = relayProof("hash", "nonce", "controller", "sid")
        assert(withTerminal != withOther)
    }
}
