package com.zcode.remote.core

import org.json.JSONObject

/**
 * 档 3（回前台"对话没回来"的恢复）的**限流判据**：什么时候允许壳再自动重载一次页面。
 *
 * 为什么把它抽出来：这套闸门的两条规则（连续上限 + 最小间隔）已经**两次**因为语义写错
 * 而在真机上失效——而真机复现"连续失败"要构造特定时序，很不可靠。本仓的既定做法是把
 * 这类判据抽成纯函数、用单测把语义钉死（同 [SurvivalVerdict]、[CarrierHandback]）。
 *
 * ## 闸门要防什么
 *
 * 恢复动作 = 钉 `history.state` + **重载整页**。用户能看见这次重载（页面闪一下），
 * 所以它必须是**低频、有终止**的：
 *   * **防连刷**：一次恢复后若页面仍没回到对话，短时间内不许再重载——
 *     否则"每次回前台都闪一次"，比它要治的毛病更烦人。
 *   * **防死循环**：连续若干次都没成功就**彻底停手**，把决定权交回用户（他自己点进任务即可）。
 *
 * ## 什么算"成功"（决定 `count` 何时清零）
 *
 * 判据一律是**页面自述的事实**——"我现在在对话视图里"（`chat=true`），两个采样点任一即可：
 *   * 退后台那一刻的视图快照（`leave`）；
 *   * 回前台 12s 判据窗里"已回到对话视图"那一支。
 *
 * ⚠️ **不是"链路配对成功"**：真机 2026-09-20 16:38 实测，按配对成功清零时，恢复重载后页面
 * 立刻配对成功 → 计数清零 → 20 秒后又一次恢复，**限流形同虚设**。配对成功只说明"链路活了"，
 * 不说明"用户回到了对话"。
 *
 * Android-free and pure so it can be unit tested on the JVM.
 */
object ConversationRecoveryPolicy {

    /** 连续自动恢复的次数上限。与注入层 KICKED 自愈同口径（那边是 `KICKED_HEAL_CAP = 2`）。 */
    const val CAP = 2

    /**
     * 两次恢复之间的最小间隔：5 分钟。
     *
     * 与注入层"回前台死链兜底"的限流同口径（`RESUME_HEAL_MIN_GAP_MS = 300000`），
     * 这样两条都会整页重载的兜底不会叠加成"一次回前台闪两下"。
     */
    const val MIN_GAP_MS = 300_000L

    /** 一条持久化记账：连续次数 + 上次恢复时刻（ms，0 = 从未）。 */
    data class Record(val count: Int, val at: Long) {
        companion object {
            val EMPTY = Record(0, 0L)
        }
    }

    /** 判据结果。[allowed] 为 false 时 [reason] 是给日志的一句话。 */
    data class Decision(val allowed: Boolean, val reason: String)

    /**
     * 该不该放行这一次恢复。
     *
     * @param record 当前记账（见 [decode]）
     * @param now 当前时刻（ms）
     */
    fun decide(record: Record, now: Long): Decision {
        if (record.count >= CAP) {
            return Decision(
                false,
                "已连续恢复 ${record.count} 次仍未回到对话（上限 $CAP）——停止自动恢复，" +
                    "把决定权交回用户",
            )
        }
        if (record.at > 0L) {
            val gap = now - record.at
            if (gap in 0..MIN_GAP_MS) {
                return Decision(
                    false,
                    "距上次恢复 ${gap / 1000}s < ${MIN_GAP_MS / 60000} 分钟",
                )
            }
        }
        return Decision(true, "")
    }

    /** 记一次恢复（次数 +1、时刻刷新）。 */
    fun afterRecovery(record: Record, now: Long): Record =
        Record(record.count + 1, now)

    /**
     * 从持久化字符串解出记账。
     *
     * **解析失败按 [Record.EMPTY] 处理**（= 放行一次），这是刻意的取舍：
     * 读不到记账说明存储出了问题，此时"卡死不动"比"多恢复一次"更糟——
     * 而次数上限本身还有兜底。
     */
    fun decode(raw: String?): Record {
        if (raw.isNullOrEmpty()) return Record.EMPTY
        return try {
            val root = JSONObject(raw)
            Record(root.optInt("count", 0).coerceAtLeast(0), root.optLong("at", 0L))
        } catch (e: Exception) {
            Record.EMPTY
        }
    }

    /** 记账的持久化写法。 */
    fun encode(record: Record): String = JSONObject()
        .put("count", record.count)
        .put("at", record.at)
        .toString()
}
