package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the card-body settle window.
 *
 * 两个错法都很贵，所以两条都钉住：推半截词（用户看到的"顿号之后停一下"），
 * 以及流式输出期间被静默窗无限推迟（卡片看起来卡死）。
 */
class PreviewSettleTest {

    @Test
    fun `a fresh text waits for the settle window`() {
        // 上一次推送就在此刻：等静默窗。
        assertEquals(PreviewSettle.SETTLE_MS, PreviewSettle.delayFor(lastPublishAt = 1_000L, now = 1_000L))
    }

    @Test
    fun `the first ever push goes out at once`() {
        // 从未推送过（0）⇒ 距上次推送"很久"，不该让首帧等 250ms。
        assertEquals(0L, PreviewSettle.delayFor(lastPublishAt = 0L, now = 1_000_000L))
    }

    @Test
    fun `a continuous stream is capped by the maximum wait`() {
        // 流式输出：每 50ms 一块，静默窗永远走不完 ⇒ 靠 MAX_WAIT 兜底。
        val last = 10_000L
        assertEquals(PreviewSettle.SETTLE_MS, PreviewSettle.delayFor(last, last + 50L))
        assertEquals(200L, PreviewSettle.delayFor(last, last + 700L))
        assertEquals(0L, PreviewSettle.delayFor(last, last + PreviewSettle.MAX_WAIT_MS))
        assertEquals(0L, PreviewSettle.delayFor(last, last + 5_000L))
    }

    @Test
    fun `the delay never goes negative when the clock jumps`() {
        // 时基回拨（或换了时钟）不能让 postDelayed 收到负数。
        assertEquals(PreviewSettle.SETTLE_MS, PreviewSettle.delayFor(lastPublishAt = 5_000L, now = 1_000L))
    }
}
