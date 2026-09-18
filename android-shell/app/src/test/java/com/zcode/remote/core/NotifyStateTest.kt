package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the notification decision logic, which is a faithful port of the
 * reference client's `computeNotifyUpdate()` / `formatRunningText()`.
 *
 * These run on the JVM (`./gradlew :app:testDebugUnitTest`), i.e. in CI without
 * a device — the only automated check the shell has for the notification
 * behaviour that the whole project exists to provide.
 */
class NotifyStateTest {

    private fun task(
        id: String,
        phase: String,
        title: String = "任务 $id",
        preview: String = "",
        interactionId: String = "",
        lastActivityAt: Long = 0,
    ) = TaskSnapshot(
        sessionId = id,
        title = title,
        phase = phase,
        preview = preview,
        pendingInteractionId = interactionId,
        lastActivityAt = lastActivityAt,
        hasBackgroundWork = false,
    )

    // ------------------------------------------------------------------ running

    /**
     * Drives a task to a terminal phase **through the observation window**.
     *
     * 2026-09-17: a task must stay stopped for [NotifyState.COMPLETION_HOLD_MS] before
     * it counts as finished (a turn boundary is 0.4–0.55s on the real device), so a
     * single terminal tick no longer announces anything. Returns the update that
     * finally announces it.
     */
    private fun NotifyState.finish(
        ws: String,
        tasks: List<TaskSnapshot>,
        startMs: Long = 1_000L,
    ): NotifyUpdate {
        apply(ws, tasks, startMs)
        return apply(ws, tasks, startMs + NotifyState.COMPLETION_HOLD_MS)
    }

    @Test
    fun `running phases produce running tasks`() {
        val state = NotifyState()
        val update = state.apply("ws", listOf(task("a", "running"), task("b", "prewarming")))
        assertEquals(listOf("a", "b"), update.running.map { it.sessionId })
        assertTrue(update.completed.isEmpty())
        assertFalse(update.attention.isNotEmpty())
    }

    @Test
    fun `terminal phases are not running`() {
        val state = NotifyState()
        val update = state.apply(
            "ws",
            listOf(task("a", "completed"), task("b", "failed"), task("c", "cancelled")),
        )
        assertTrue(update.running.isEmpty())
        assertTrue(update.completed.isEmpty())
    }

    @Test
    fun `an unknown phase is neither running nor terminal`() {
        val state = NotifyState()
        val update = state.apply("ws", listOf(task("a", "someFuturePhase")))
        assertTrue(update.running.isEmpty())
        assertTrue(update.completed.isEmpty())
    }

    // --------------------------------------------------------------- completion

    @Test
    fun `running to terminal fires exactly one completion, after the window`() {
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        // 第一拍只开观察窗：这一拍**不是**结束（真机 0.4–0.55s 的轮次缝就是这么被吃掉的）。
        val started = state.apply("ws", listOf(task("a", "completed", preview = "done")), 1_000L)
        assertTrue("the first terminal tick must only open the window", started.completed.isEmpty())
        assertEquals("the task keeps counting as running while held", setOf("a"), started.heldRunning)
        assertEquals(1_000L + NotifyState.COMPLETION_HOLD_MS, state.nextCompletionDeadlineMs())

        val first = state.apply("ws", listOf(task("a", "completed", preview = "done")), 1_000L + NotifyState.COMPLETION_HOLD_MS)
        assertEquals(listOf("a"), first.completed.map { it.task.sessionId })
        assertEquals("done", first.completed[0].task.preview)
        assertFalse(first.completed[0].failed)
        assertTrue(first.heldRunning.isEmpty())
        assertEquals("nothing is pending any more", 0L, state.nextCompletionDeadlineMs())

        // Still terminal on the next tick: no repeat.
        val second = state.apply("ws", listOf(task("a", "completed")))
        assertTrue(second.completed.isEmpty())
    }

    @Test
    fun `a turn gap shorter than the window is not a completion`() {
        // 真机现场（2026-09-17 22:51:50）：结束 → 0.55s 后又在跑。旧逻辑在这里撤了卡、
        // 发了完成卡、还响了一声提示。新逻辑：什么都没发生。
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")), 1_000L)
        val ended = state.apply("ws", listOf(task("a", "completedSuccess")), 1_100L)
        assertTrue(ended.completed.isEmpty())
        assertEquals(setOf("a"), ended.heldRunning)
        val again = state.apply("ws", listOf(task("a", "running")), 1_600L)
        assertTrue("a new turn cancels the pending completion", again.completed.isEmpty())
        assertTrue(again.heldRunning.isEmpty())
        assertEquals("and nothing is left to flush", 0L, state.nextCompletionDeadlineMs())
        // 之后真的停了，仍然只算一次完成。
        state.apply("ws", listOf(task("a", "completed")), 2_000L)
        assertEquals(
            1,
            state.apply("ws", listOf(task("a", "completed")), 2_000L + NotifyState.COMPLETION_HOLD_MS).completed.size,
        )
    }

    @Test
    fun `a re-run fires a second completion`() {
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        assertEquals(1, state.finish("ws", listOf(task("a", "completed"))).completed.size)
        state.apply("ws", listOf(task("a", "running")), 9_000L)
        assertEquals(1, state.finish("ws", listOf(task("a", "failed")), 10_000L).completed.size)
    }

    @Test
    fun `a task that disappears then returns terminal does not fire`() {
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        state.apply("ws", emptyList())
        val update = state.apply("ws", listOf(task("a", "completed")))
        assertTrue("a removed task must not be reported as newly completed", update.completed.isEmpty())
    }

    @Test
    fun `every terminal phase is recognised after running`() {
        for (phase in NotifyState.TERMINAL_PHASES) {
            val state = NotifyState()
            state.apply("ws", listOf(task("a", "running")))
            val update = state.finish("ws", listOf(task("a", phase)))
            assertEquals("phase $phase must fire a completion", 1, update.completed.size)
        }
    }

    @Test
    fun `failures are flagged for the wording of the notification`() {
        for (phase in listOf("failed", "error", "cancelled", "completedInterrupted")) {
            val state = NotifyState()
            state.apply("ws", listOf(task("a", "running")))
            assertTrue(
                "$phase must be reported as a failure",
                state.finish("ws", listOf(task("a", phase))).completed[0].failed,
            )
        }
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        assertFalse(state.finish("ws", listOf(task("a", "completedSuccess"))).completed[0].failed)
    }

    @Test
    fun `workspaces track phases independently`() {
        val state = NotifyState()
        state.apply("ws-1", listOf(task("a", "running")))
        state.apply("ws-2", listOf(task("b", "running")))
        // ws-2 reports, ws-1 does not: ws-1's phase must survive.
        val update = state.finish("ws-2", listOf(task("b", "completed")))
        assertEquals(1, update.completed.size)
        assertEquals("ws-2", update.completed[0].workspaceKey)
        // A later ws-1 transition is still detected.
        val later = state.finish("ws-1", listOf(task("a", "completed")), 5_000L)
        assertEquals(1, later.completed.size)
        assertEquals("ws-1", later.completed[0].workspaceKey)
    }

    @Test
    fun `session ids that collide across workspaces do not cross-fire`() {
        val state = NotifyState()
        state.apply("ws-1", listOf(task("same-id", "running")))
        state.apply("ws-2", listOf(task("same-id", "running")))
        val update = state.finish("ws-2", listOf(task("same-id", "completed")))
        assertEquals(1, update.completed.size)
        assertEquals("ws-2", update.completed[0].workspaceKey)
    }

    // ---------------------------------------------------------------- attention

    @Test
    fun `a pending interaction is announced once`() {
        val state = NotifyState()
        val first = state.apply("ws", listOf(task("a", "running", interactionId = "i-1")))
        assertEquals(listOf("i-1"), first.attention.map { it.interactionId })

        val second = state.apply("ws", listOf(task("a", "running", interactionId = "i-1")))
        assertTrue(second.attention.isEmpty())

        // A different interaction is a new thing to announce.
        val third = state.apply("ws", listOf(task("a", "running", interactionId = "i-2")))
        assertEquals(listOf("i-2"), third.attention.map { it.interactionId })
    }

    @Test
    fun `an interaction without an id is ignored`() {
        val state = NotifyState()
        val update = state.apply("ws", listOf(task("a", "running", interactionId = "")))
        assertTrue(update.attention.isEmpty())
    }

    @Test
    fun `a waiting task is also still running`() {
        val state = NotifyState()
        val update = state.apply("ws", listOf(task("a", "running", interactionId = "i-1")))
        assertEquals(1, update.running.size)
        assertEquals(TaskStatus.WAITING, state.statusOf(update.running[0]))
    }

    // ------------------------------------------------------------- status words

    @Test
    fun `only two status words exist for a live task`() {
        val state = NotifyState()
        assertEquals(TaskStatus.RUNNING, state.statusOf(task("a", "running")))
        assertEquals(TaskStatus.RUNNING, state.statusOf(task("a", "prewarming")))
        assertEquals(TaskStatus.WAITING, state.statusOf(task("a", "running", interactionId = "i")))
    }

    @Test
    fun `the completed word belongs to the card, never to a live task`() {
        // D15: 已完成 is a third label, but it must never leak into statusOf —
        // that is what keeps D9's two-word rule for live tasks intact.
        val state = NotifyState()
        for (task in listOf(
            task("a", "running"),
            task("b", "running", interactionId = "i"),
            task("c", "completed"),
            task("d", "prewarming"),
        )) {
            assertNotEquals(TaskStatus.COMPLETED, state.statusOf(task))
        }
        assertEquals("已完成", TaskStatus.COMPLETED.label)
    }

    // ------------------------------------------------------------- title / body

    @Test
    fun `the title carries the status word as a prefix`() {
        assertEquals(
            "运行中 · 重构登录",
            NotifyState.formatTitle(TaskStatus.RUNNING.label, "重构登录"),
        )
        assertEquals(
            "等待确认 · 重构登录",
            NotifyState.formatTitle(TaskStatus.WAITING.label, "重构登录"),
        )
        assertEquals(
            "已完成 · 重构登录",
            NotifyState.formatTitle(TaskStatus.COMPLETED.label, "重构登录"),
        )
    }

    @Test
    fun `an untitled task shows the status word alone, without a dangling separator`() {
        assertEquals("运行中", NotifyState.formatTitle(TaskStatus.RUNNING.label, ""))
    }

    @Test
    fun `the title is collapsed onto one line`() {
        // A newline in a task title would otherwise wrap the title row and take a
        // line away from the live progress, which is the part that needs room.
        assertEquals("第一行 第二行", NotifyState.singleLine("第一行\n第二行"))
        assertEquals("a b", NotifyState.singleLine("  a \t\n  b  "))
        assertEquals(
            "运行中 · a b",
            NotifyState.formatTitle(TaskStatus.RUNNING.label, "a\nb"),
        )
    }

    @Test
    fun `the body is the progress, with the workspace name as the fallback`() {
        assertEquals("已修改 auth_service", NotifyState.formatBody("已修改 auth_service", "仓库"))
        assertEquals("仓库", NotifyState.formatBody("", "仓库"))
        assertEquals("仓库", NotifyState.formatBody("   ", "仓库"))
        // Newlines collapse here too: the body is drawn by the platform's
        // BigTextStyle, which is happy with one line as well as several.
        assertEquals("a b", NotifyState.formatBody("a\nb", "仓库"))
    }

    @Test
    fun `the title falls back to the session id`() {
        assertEquals("session-1", task("session-1", "running", title = "").displayTitle)
        assertEquals("任务名", task("s", "running", title = "任务名").displayTitle)
    }

    @Test
    fun `displayTitle is left verbatim for the locator`() {
        // The notification-tap locator matches displayTitle against text in the
        // page, so it must not be collapsed the way the notification title is.
        assertEquals("a\nb", task("s", "running", title = "a\nb").displayTitle)
    }

    @Test
    fun `notification ids are stable and workspace scoped`() {
        val first = NotifyState.notificationIdFor("ws-1", "s-1")
        assertEquals(first, NotifyState.notificationIdFor("ws-1", "s-1"))
        assertNotEquals(first, NotifyState.notificationIdFor("ws-2", "s-1"))
        assertNotEquals(first, NotifyState.notificationIdFor("ws-1", "s-2"))
        assertTrue(first >= NotifyState.ONGOING_ID_BASE)
    }

    @Test
    fun `there is one id per task, live or finished`() {
        // 2026-09-17：完成不再是"另开一条记录"（旧 D15 那张另开 id 的 15s 完成卡会闪，
        // 而且和运行卡抢两个提升位）。同一条记录原地改状态 ⇒ id 只有一个来源。
        val live = NotifyState.notificationIdFor("ws-1", "s-1")
        assertTrue(live >= NotifyState.ONGOING_ID_BASE)
        assertTrue(live < NotifyState.ONGOING_ID_BASE + NotifyState.ONGOING_ID_RANGE)
    }

    // ------------------------------------------------------------------- store

    @Test
    fun `the store reports notifications to add and to cancel`() {
        val store = TaskStore()
        val added = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(
                task("a", "running", title = "重构登录", preview = "已改 auth"),
                task("b", "prewarming", title = "写测试"),
            ),
        )
        assertEquals(2, added.running.size)
        assertTrue(added.removedIds.isEmpty())
        val first = added.running.first { it.task.sessionId == "a" }
        // D15: the status word prefixes the *title*, and the body is progress only.
        assertEquals("运行中 · 重构登录", first.title)
        assertEquals("已改 auth", first.body)

        // 'a' finishes, 'b' keeps running. 结束那一拍**什么都不变**：a 还在观察窗里，
        // 仍然算运行中（所以卡片不会被撤——2026-09-17 之前这里会撤卡 + 另发完成卡）。
        val held = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(task("b", "prewarming", title = "写测试"), task("a", "completed")),
        )
        assertEquals(2, held.running.size)
        assertTrue(held.removedIds.isEmpty())
        assertTrue(held.completed.isEmpty())
        assertTrue("窗口到期要有人回来算一次", held.nextFlushAtMs > 0L)

        // 窗口走完（任务结束后不再有新帧，只能靠这一拍回灌）：撤一张、一个完成事件，
        // 而且**撤的就是那张卡自己的 id** —— 通知层据此"改状态"而不是"撤了再建"。
        val next = store.flushDueCompletions(System.currentTimeMillis() + NotifyState.COMPLETION_HOLD_MS)
        assertEquals(1, next.running.size)
        assertEquals("b", next.running[0].task.sessionId)
        assertEquals(listOf(first.id), next.removedIds)
        assertEquals(listOf("a"), next.completed.map { it.task.sessionId })
        assertEquals(
            first.id,
            NotifyState.notificationIdFor(next.completed[0].workspaceKey, next.completed[0].task.sessionId),
        )
        assertEquals("窗口用掉了就不该再有下一次", 0L, next.nextFlushAtMs)
    }

    @Test
    fun `a turn gap never cancels the card`() {
        // 真机 2026-09-17 22:51:50 的回归：结束 → 0.55s 后新一轮。旧逻辑撤卡 + 发完成卡 +
        // 建新卡（卡片闪、提示白响、两张卡抢两个提升位）。新逻辑：卡一秒都不用动。
        val store = TaskStore()
        val added = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(task("a", "running", title = "压测", preview = "在跑")),
        )
        val liveId = added.running[0].id
        val ended = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(task("a", "completedSuccess")),
        )
        assertTrue("结束那一拍不撤卡", ended.removedIds.isEmpty())
        assertEquals(listOf(liveId), ended.running.map { it.id })
        assertTrue(ended.completed.isEmpty())

        val resumed = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(task("a", "running", title = "压测", preview = "第二轮")),
        )
        assertTrue(resumed.removedIds.isEmpty())
        assertTrue(resumed.completed.isEmpty())
        assertEquals(listOf(liveId), resumed.running.map { it.id })
        assertEquals("观察窗该被撤销，不留定时器", 0L, resumed.nextFlushAtMs)
    }

    @Test
    fun `a running task with no progress yet shows its workspace name instead`() {
        val store = TaskStore()
        val update = store.applyWorkspace(
            key = "/repo/x", title = "学习周报", path = "/repo/x", identity = "ws", source = "active",
            tasks = listOf(task("a", "running", title = "写周报", preview = "")),
        )
        assertEquals("运行中 · 写周报", update.running[0].title)
        assertEquals("学习周报", update.running[0].body)
    }

    @Test
    fun `the decorated title is never what the locator searches the page for`() {
        // Regression guard for a real one: the notification-tap locator matches
        // its title against text in the page, and the page never renders our
        // 状态 prefix. Handing it `title` broke "tap to jump to the task" on the
        // device (2026-09-12) with no test failing.
        val store = TaskStore()
        val update = store.applyWorkspace(
            key = "/repo/x", title = "仓库", path = "/repo/x", identity = "ws", source = "active",
            tasks = listOf(task("a", "running", title = "重构登录页", preview = "在改 CSS")),
        )
        val running = update.running[0]
        assertEquals("运行中 · 重构登录页", running.title)
        assertEquals("重构登录页", running.locateTitle)
        assertNotEquals(running.title, running.locateTitle)
        // And the raw name must survive verbatim, newlines included, since the
        // page's own text is what it will be compared against. Fresh store: the
        // running list is unioned across every workspace, so reusing this one
        // would hand back the previous workspace's task at index 0.
        val multiline = TaskStore().applyWorkspace(
            key = "/repo/y", title = "仓库", path = "/repo/y", identity = "ws", source = "active",
            tasks = listOf(task("b", "running", title = "第一行\n第二行")),
        ).running[0]
        assertEquals("第一行\n第二行", multiline.locateTitle)
        assertEquals("运行中 · 第一行 第二行", multiline.title)
    }

    @Test
    fun `the store unions running tasks across workspaces`() {
        val store = TaskStore()
        store.applyWorkspace("ws-1", "一", "/a", "ws-1", "active", listOf(task("a", "running")))
        val update = store.applyWorkspace("ws-2", "二", "/b", "ws-2", "passive", listOf(task("b", "running")))
        assertEquals(2, update.running.size)
        assertEquals(setOf("ws-1", "ws-2"), update.running.mapTo(HashSet()) { it.workspaceKey })
        assertTrue(store.hasRunningTasks)
        assertEquals(2, store.workspaceCount())
    }

    @Test
    fun `removing a workspace cancels its running notifications`() {
        val store = TaskStore()
        val added = store.applyWorkspace("ws-1", "一", "/a", "ws-1", "active", listOf(task("a", "running")))
        store.applyWorkspace("ws-2", "二", "/b", "ws-2", "active", listOf(task("b", "running")))
        val removed = store.removeWorkspace("ws-1")
        assertEquals(listOf(added.running[0].id), removed.removedIds)
        assertEquals(1, removed.running.size)
        assertEquals("ws-2", removed.running[0].workspaceKey)
    }

    @Test
    fun `reset cancels everything`() {
        val store = TaskStore()
        val added = store.applyWorkspace("ws-1", "一", "/a", "ws-1", "active", listOf(task("a", "running")))
        val reset = store.reset()
        assertEquals(listOf(added.running[0].id), reset.removedIds)
        assertTrue(reset.running.isEmpty())
        assertFalse(store.hasRunningTasks)
    }

    @Test
    fun `a workspace update keeps its title when the next frame omits it`() {
        val store = TaskStore()
        store.applyWorkspace("ws-1", "我的仓库", "/a", "ws-1", "active", listOf(task("a", "running")))
        val update = store.applyWorkspace("ws-1", "", "", "", "active", listOf(task("a", "running")))
        assertEquals("我的仓库", update.running[0].workspaceTitle)
    }
}
