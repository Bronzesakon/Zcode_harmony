package com.zcode.remote.core

/**
 * When the card body may be pushed, given that the agent's text arrives in pieces.
 *
 * 真机 2026-09-18 09:56:39 现场：`活进展 E:\Zcode_harmony：第五百三十`（**半截词**）上了卡片，
 * 46ms 后才补成 `第五百三十四次回复，时间09:56:36`。用户看到的是"顿号之后停一下才出来"——
 * 因为通知更新本身有 900ms 节流，那半截词会在卡片上停将近一秒。
 *
 * 所以推送前先等这份文本**安静下来**（[SETTLE_MS]），但**不超过**距上次推送 [MAX_WAIT_MS]：
 *  - 分块到达（几十毫秒一块）⇒ 合并成一次推送，卡片上只会出现完整的句子；
 *  - 连续流式输出（每 50ms 一个 token）⇒ 静默窗永远走不完，靠 [MAX_WAIT_MS] 兜底，
 *    卡片仍然按 ~1 秒的节奏跟手，不会"卡住不动"。
 *
 * 纯函数 + 单测：这段逻辑的错法（要么推送半截词，要么流式期间再也不更新）在真机上都很贵。
 */
object PreviewSettle {

    /** 文本安静这么久就算"这一句说完了"。 */
    const val SETTLE_MS = 250L

    /** 距上次推送超过这么久就无条件推一次（连续输出时的兜底节奏）。 */
    const val MAX_WAIT_MS = 900L

    /**
     * @param lastPublishAt 上一次**真正推送**的时刻（同一会话；从未推过传 0）
     * @param now 当前时刻（与 [lastPublishAt] 同一时基）
     * @return 这次推送该等多少毫秒（0 = 立刻推）
     */
    fun delayFor(
        lastPublishAt: Long,
        now: Long,
        settleMs: Long = SETTLE_MS,
        maxWaitMs: Long = MAX_WAIT_MS,
    ): Long {
        val sincePublish = (now - lastPublishAt).coerceAtLeast(0L)
        val remain = (maxWaitMs - sincePublish).coerceAtLeast(0L)
        return minOf(settleMs, remain)
    }
}
