package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the promoted-notification (流体云) slot allocation.
 *
 * The rule that matters is priority: a task waiting on the user must win a slot
 * over a merely-running one, because that is the case the fluid cloud is
 * genuinely for. Everything else is recency.
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
    fun `the most recently active running task gets the slot`() {
        val chosen = PromotionPolicy.choose(
            listOf(running(1, false, 10), running(2, false, 30), running(3, false, 20)),
            max = 1,
        )
        assertEquals(setOf(2), chosen)
    }

    @Test
    fun `a task waiting for the user outranks a fresher running one`() {
        val chosen = PromotionPolicy.choose(
            listOf(running(1, false, 999), running(2, true, 1)),
            max = 1,
        )
        assertEquals(setOf(2), chosen)
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
            max = 3,
        )
        assertEquals(listOf(3, 2, 1), chosen.toList())
    }

    @Test
    fun `the cap is respected and never zero-length when tasks exist`() {
        val many = (1..6).map { running(it, false, it.toLong()) }
        assertEquals(PromotionPolicy.MAX_PROMOTED, PromotionPolicy.choose(many).size)
        assertEquals(setOf(6, 5), PromotionPolicy.choose(many))
    }

    @Test
    fun `a non-positive cap promotes nothing rather than everything`() {
        val tasks = listOf(running(1, true, 1))
        assertTrue(PromotionPolicy.choose(tasks, max = 0).isEmpty())
        assertTrue(PromotionPolicy.choose(tasks, max = -3).isEmpty())
    }

    @Test
    fun `asking for more slots than tasks returns all of them`() {
        val tasks = listOf(running(1, false, 1), running(2, false, 2))
        assertEquals(setOf(1, 2), PromotionPolicy.choose(tasks, max = 10))
    }

    // ------------------------------------------------------------- finished cards

    @Test
    fun `a finished card may hold a slot when nothing else needs it`() {
        // 2026-09-17：任务结束后那张卡原地变「已完成」并留着（用户拍板），所以它也要抢位。
        val chosen = PromotionPolicy.choose(
            running = emptyList(),
            finished = listOf(PromotionPolicy.Finished(7, completedAt = 100)),
        )
        assertEquals(setOf(7), chosen)
    }

    @Test
    fun `a finished card never keeps a running task off the strip`() {
        val chosen = PromotionPolicy.choose(
            running = listOf(running(1, false, 5)),
            finished = listOf(PromotionPolicy.Finished(7, completedAt = 999)),
            max = 1,
        )
        assertEquals("在跑的任务优先，哪怕完成卡更新", setOf(1), chosen)
    }

    @Test
    fun `finished cards fill the leftover slots, newest first`() {
        val chosen = PromotionPolicy.choose(
            running = listOf(running(1, false, 5)),
            finished = listOf(
                PromotionPolicy.Finished(7, completedAt = 100),
                PromotionPolicy.Finished(8, completedAt = 300),
                PromotionPolicy.Finished(9, completedAt = 200),
            ),
        )
        assertEquals(listOf(1, 8), chosen.toList())
    }

    @Test
    fun `a user-waiting card still wins over everything`() {
        val chosen = PromotionPolicy.choose(
            running = listOf(running(1, false, 9_999), running(2, true, 1)),
            finished = listOf(PromotionPolicy.Finished(7, completedAt = 9_999)),
            max = 2,
        )
        assertEquals(listOf(2, 1), chosen.toList())
    }
}
