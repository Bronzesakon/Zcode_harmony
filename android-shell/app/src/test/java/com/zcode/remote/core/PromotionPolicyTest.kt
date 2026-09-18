package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the fluid-cloud (Live Updates) slot allocation.
 *
 * 2026-09-18 起**没有"总共几张"的上限**：在跑的任务有几个就提升几个。这里曾经是 2，
 * 依据"ColorOS 只并发提升 2 张流体云卡"——被用户现场推翻（三个任务同时跑时手机三张卡同时
 * 显示、`dumpsys notification` 里三条 `PROMOTED_ONGOING`，而当时我们只请求了 2 个）。
 * 界限只加在"留到用户清掉"的完成卡上。
 */
class PromotionPolicyTest {

    private fun running(
        id: Int,
        waiting: Boolean,
        lastActivityAt: Long,
    ): TaskStore.RunningNotification {
        val task = TaskSnapshot(
            sessionId = "s$id",
            title = "任务$id",
            phase = if (waiting) "running" else "prewarming",
            preview = "",
            pendingInteractionId = if (waiting) "i$id" else "",
            lastActivityAt = lastActivityAt,
            hasBackgroundWork = false,
        )
        return TaskStore.RunningNotification(
            id = id,
            workspaceKey = "ws",
            workspaceTitle = "仓库",
            task = task,
            status = if (waiting) TaskStatus.WAITING else TaskStatus.RUNNING,
            // The slot allocation does not look at the body; the fallback keeps
            // the helper honest about how a body is built (no status prefix any
            // more — that moved into the title, see NotifyState.formatTitle).
            body = NotifyState.formatBody("", "仓库"),
        )
    }

    @Test
    fun `nothing running promotes nothing`() {
        assertTrue(PromotionPolicy.choose(emptyList()).isEmpty())
    }

    @Test
    fun `every running task gets a card, however many there are`() {
        // 用户 2026-09-18 的现场：三个任务同时在跑，就该有三张卡。
        val many = (1..6).map { running(it, false, it.toLong()) }
        assertEquals(setOf(1, 2, 3, 4, 5, 6), PromotionPolicy.choose(many))
    }

    @Test
    fun `waiting tasks are ordered before running ones, then by recency`() {
        val chosen = PromotionPolicy.choose(
            listOf(
                running(1, false, 100),
                running(2, true, 5),
                running(3, true, 50),
                running(4, false, 90),
            ),
        )
        assertEquals(listOf(3, 2, 1, 4), chosen.toList())
    }

    @Test
    fun `a task waiting for the user comes first`() {
        val chosen = PromotionPolicy.choose(
            listOf(running(1, false, 999), running(2, true, 1)),
        )
        assertEquals(listOf(2, 1), chosen.toList())
    }

    // ------------------------------------------------------------- finished cards

    @Test
    fun `a finished card keeps a card when nothing else needs the slot`() {
        // 2026-09-17：任务结束后那张卡原地变「已完成」并留着（用户拍板），所以它也要占位。
        val chosen = PromotionPolicy.choose(
            running = emptyList(),
            finished = listOf(PromotionPolicy.Finished(7, completedAt = 100)),
        )
        assertEquals(setOf(7), chosen)
    }

    @Test
    fun `finished cards never displace a running task`() {
        val chosen = PromotionPolicy.choose(
            running = listOf(running(1, false, 5), running(2, false, 6)),
            finished = (11..15).map { PromotionPolicy.Finished(it, completedAt = it.toLong()) },
        )
        assertTrue("在跑的任务一个都不能被顶掉", chosen.containsAll(listOf(1, 2)))
        // 在跑的按最近活动降序（id 2 的活动时间更近），之后才是最新的完成卡。
        assertEquals(listOf(2, 1, 15, 14, 13), chosen.toList())
    }

    @Test
    fun `only the newest finished cards are kept`() {
        val chosen = PromotionPolicy.choose(
            running = emptyList(),
            finished = listOf(
                PromotionPolicy.Finished(7, completedAt = 100),
                PromotionPolicy.Finished(8, completedAt = 500),
                PromotionPolicy.Finished(9, completedAt = 300),
                PromotionPolicy.Finished(10, completedAt = 200),
                PromotionPolicy.Finished(11, completedAt = 400),
            ),
        )
        assertEquals(
            "只留最新 ${PromotionPolicy.MAX_FINISHED_PROMOTED} 张（别的降级成可滑除通知，不消失）",
            listOf(8, 11, 9),
            chosen.toList(),
        )
    }

    @Test
    fun `a zero finished cap promotes no finished card`() {
        val chosen = PromotionPolicy.choose(
            running = listOf(running(1, false, 1)),
            finished = listOf(PromotionPolicy.Finished(7, completedAt = 999)),
            maxFinished = 0,
        )
        assertEquals(setOf(1), chosen)
    }
}
