package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the milestone-4 survival readout.
 *
 * This line has reported the wrong answer in two consecutive field rounds, so the
 * cases below are the ones that actually happened, not hypotheticals:
 *
 *  * a foreground flap at the moment of return makes the first-tick delay
 *    unknowable (-1) — the count still decides, and the readout must say the
 *    delay is unknown rather than claiming the heartbeat never ran;
 *  * a catch-up burst (every tick in the last instant of the window) must still
 *    be called out, because that is the failure the readout exists to catch.
 */
class SurvivalVerdictTest {

    @Test
    fun `ticks with an early first tick is a pass`() {
        assertEquals(
            "保活成立（后台心跳在跑）",
            SurvivalVerdict.verdict(ticks = 50, firstTickDelayMs = 15_000)
        )
    }

    @Test
    fun `an unknown first-tick delay is reported as unknown, not as never ran`() {
        // The flap case: the page reset its delay at the last foreground blip, so
        // the delay is gone but the ticks are real.
        val verdict = SurvivalVerdict.verdict(ticks = 50, firstTickDelayMs = -1)
        assertTrue(verdict, verdict.startsWith("后台心跳在跑"))
        assertTrue(verdict, verdict.contains("首次延迟未知"))

        val line = SurvivalVerdict.describe(
            duration = "12 分 44 秒",
            ticks = 50,
            firstTickDelayMs = -1,
            pumpDispatches = 50,
            frames = 604,
            acks = 53,
        )
        assertTrue(line, line.contains("注入层心跳 50 次"))
        assertTrue(line, line.contains("首次延迟未知"))
        assertTrue(line, line.contains("原生泵发令 50 次"))
    }

    @Test
    fun `a burst at the end of the window is still called out`() {
        val verdict = SurvivalVerdict.verdict(ticks = 40, firstTickDelayMs = 90_000)
        assertTrue(verdict, verdict.startsWith("心跳只在恢复瞬间补跑"))
    }

    @Test
    fun `zero ticks is the only never-ran case`() {
        assertEquals(
            "后台期间心跳未执行（定时器与原生发令都没跑）",
            SurvivalVerdict.verdict(ticks = 0, firstTickDelayMs = -1)
        )
        assertTrue(
            SurvivalVerdict.describe(
                duration = "10 分 0 秒",
                ticks = 0,
                firstTickDelayMs = -1,
                pumpDispatches = 39,
                frames = 441,
                acks = 43,
            ).contains("退后台后一次都没执行")
        )
    }

    @Test
    fun `the boundary between on time and catch-up is the documented 60s`() {
        assertTrue(
            SurvivalVerdict.verdict(
                ticks = 10,
                firstTickDelayMs = SurvivalVerdict.RESUMED_BURST_DELAY_MS,
            ).startsWith("保活成立")
        )
        assertTrue(
            SurvivalVerdict.verdict(
                ticks = 10,
                firstTickDelayMs = SurvivalVerdict.RESUMED_BURST_DELAY_MS + 1,
            ).startsWith("心跳只在恢复瞬间补跑")
        )
    }
}
