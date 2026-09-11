package com.zcode.remote.core

/**
 * The milestone-4 readout: while the app was away, did the injected layer keep
 * ticking, or did the heartbeat only catch up at the instant it came back?
 *
 * Pure, and unit-tested, because this one line has now fooled two field rounds.
 * It reported "后台期间心跳未执行" while the pump was demonstrably driving a tick
 * every 15s, and the reason was not the keepalive at all: waking a screen-off
 * phone produces a foreground → background → foreground flap within a couple of
 * milliseconds, the injected layer zeroed its counter on that flap, and the
 * verdict was read 16ms later. Two rules keep it honest now:
 *
 *  * the tick count is a DELTA against a base the native side records when the
 *    window opens, so nothing the page resets can fabricate a zero;
 *  * an unknown first-tick delay is reported as unknown instead of being
 *    rounded down to "never ran" — a flap makes the delay unknowable, but the
 *    count still answers the question that matters.
 */
object SurvivalVerdict {

    /** Ticks closer to the foreground return than this look like a catch-up burst. */
    const val RESUMED_BURST_DELAY_MS = 60_000L

    /** The decisive sentence. */
    fun verdict(ticks: Int, firstTickDelayMs: Long): String = when {
        ticks <= 0 -> "后台期间心跳未执行（定时器与原生发令都没跑）"
        firstTickDelayMs < 0 -> "后台心跳在跑（首次延迟未知：窗口末尾有前后台抖动）"
        firstTickDelayMs > RESUMED_BURST_DELAY_MS -> "心跳只在恢复瞬间补跑，后台期间很可能没执行"
        else -> "保活成立（后台心跳在跑）"
    }

    /** The whole line as it is written to the log and shown in 设置 → 诊断. */
    fun describe(
        duration: String,
        ticks: Int,
        firstTickDelayMs: Long,
        pumpDispatches: Int,
        frames: Int,
        acks: Int,
    ): String {
        val firstTick = when {
            firstTickDelayMs >= 0 -> "首次在退后台后 ${firstTickDelayMs / 1000}s"
            ticks > 0 -> "首次延迟未知"
            else -> "退后台后一次都没执行"
        }
        return "后台存活检查：时长 $duration，后台期间注入层心跳 $ticks 次" +
            "（$firstTick，原生泵发令 $pumpDispatches 次）、" +
            "收到 $frames 帧、配对确认 $acks 次 → ${verdict(ticks, firstTickDelayMs)}"
    }
}
