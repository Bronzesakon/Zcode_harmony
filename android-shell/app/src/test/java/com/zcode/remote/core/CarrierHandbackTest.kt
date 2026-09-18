package com.zcode.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the carrier hand-back rule.
 *
 * The case that matters is the regression from the 2026-09-17 field run: after an **early**
 * takeover the page's own `KICKED` frame refreshes the inbound counter, so a bare
 * "a frame arrived" test hands the connection back to an already-terminal page — and the
 * shell then re-took it in the same pass (bridge torn down and rebuilt for nothing).
 */
class CarrierHandbackTest {

    private val silence = 30_000L

    @Test
    fun `the dead-link carrier yields as soon as the page link is alive`() {
        // 判死路径：页面静默 35s 后任何一帧都是它自己回来了 ⇒ 照旧交还（不变式不变）。
        assertTrue(
            CarrierHandback.shouldHandBack(
                pageLinkAlive = true,
                startedEarly = false,
                pageIsServing = false,
            )
        )
    }

    @Test
    fun `no live link means no hand-back, whatever the page reports`() {
        assertFalse(
            CarrierHandback.shouldHandBack(
                pageLinkAlive = false,
                startedEarly = false,
                pageIsServing = true,
            )
        )
        assertFalse(
            CarrierHandback.shouldHandBack(
                pageLinkAlive = false,
                startedEarly = true,
                pageIsServing = true,
            )
        )
    }

    @Test
    fun `the early carrier holds the connection through the page's KICKED frame`() {
        // 真机 22:49:01：socket=-1、paired=false，页面已 dispose —— 偏偏那一帧把 age 拉回 16s。
        assertFalse(
            CarrierHandback.shouldHandBack(
                pageLinkAlive = true,
                startedEarly = true,
                pageIsServing = false,
            )
        )
    }

    @Test
    fun `the early carrier yields once the page serves a conversation again`() {
        assertTrue(
            CarrierHandback.shouldHandBack(
                pageLinkAlive = true,
                startedEarly = true,
                pageIsServing = true,
            )
        )
    }

    @Test
    fun `serving means an open paired socket with fresh conversation frames`() {
        assertTrue(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, true, 0, silence))
        assertTrue(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, true, silence - 1, silence))
    }

    @Test
    fun `the overview page is alive but not serving`() {
        // 概览页：socket 活着、配对着，可一个会话都不跟——正是提前接管的触发场景，
        // 所以它绝不构成"页面又在服务了"。−1 = 本客户端生命周期内一帧会话帧都没见过。
        assertFalse(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, true, -1, silence))
        assertFalse(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, true, silence, silence))
        assertFalse(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, true, silence + 1, silence))
    }

    @Test
    fun `a terminal page is not serving`() {
        // KICKED 终态：socket 没了（−1）、或已关闭（3）、或配对丢了。
        assertFalse(CarrierHandback.pageIsServing(-1, false, 1_000, silence))
        assertFalse(CarrierHandback.pageIsServing(3, true, 1_000, silence))
        assertFalse(CarrierHandback.pageIsServing(CarrierHandback.SOCKET_OPEN, false, 1_000, silence))
    }
}
