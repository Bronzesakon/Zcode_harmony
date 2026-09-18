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
    )

    // ------------------------------------------------------------------ running
    //
    // B1（2026-09-18）：这组测试原先是断 `NotifyUpdate.running` 的——那是 NotifyState
    // 自己算出来、**生产端从来不读**的一份清单（壳上的运行清单由 TaskStore.buildRunningList
    // 重算，那一份才带通知 id / 状态词 / 正文）。字段已删，所以这里改断**展示层口径**：
    // `TaskStore.Update.running`（`List<RunningNotification>`，真正被渲染的那一份）。
    // 于是"谁算运行中"只有一处实现：`TaskStore.runningIn()`。

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
        val store = TaskStore()
        val update = store.applyWorkspace(
            "ws", "仓库", "/ws", "ws", "active",
            listOf(task("a", "running"), task("b", "prewarming")),
        )
        assertEquals(listOf("a", "b"), update.running.map { it.task.sessionId })
        assertTrue(update.completed.isEmpty())
        assertFalse(update.attention.isNotEmpty())
    }

    @Test
    fun `terminal phases are not running`() {
        val store = TaskStore()
        val update = store.applyWorkspace(
            "ws", "仓库", "/ws", "ws", "active",
            listOf(task("a", "completed"), task("b", "failed"), task("c", "cancelled")),
        )
        assertTrue(update.running.isEmpty())
        assertTrue(update.completed.isEmpty())
    }

    @Test
    fun `an unknown phase is neither running nor terminal`() {
        val store = TaskStore()
        val update = store.applyWorkspace(
            "ws", "仓库", "/ws", "ws", "active",
            listOf(task("a", "someFuturePhase")),
        )
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

    @Test
    fun `the finished card's status word follows the terminal phase`() {
        // 2026-09-18 用户现场：在桌面端点"中断"，卡片却宣布「已完成」。三种收尾必须分清。
        assertEquals(TaskStatus.COMPLETED, NotifyState.finishedStatusOf("completedSuccess"))
        assertEquals(TaskStatus.COMPLETED, NotifyState.finishedStatusOf("completed"))
        assertEquals(TaskStatus.INTERRUPTED, NotifyState.finishedStatusOf("completedInterrupted"))
        assertEquals(TaskStatus.INTERRUPTED, NotifyState.finishedStatusOf("cancelled"))
        assertEquals(TaskStatus.FAILED, NotifyState.finishedStatusOf("failed"))
        assertEquals(TaskStatus.FAILED, NotifyState.finishedStatusOf("error"))
        // 每个终态都得有词，而且**绝不能**复用"运行中/等待确认"这两个 live 词（D9）。
        for (phase in NotifyState.TERMINAL_PHASES) {
            val status = NotifyState.finishedStatusOf(phase)
            assertNotEquals(TaskStatus.RUNNING, status)
            assertNotEquals(TaskStatus.WAITING, status)
        }
        // 未知相位按"已完成"兜底（宁可说完成，也不无端指控失败）。
        assertEquals(TaskStatus.COMPLETED, NotifyState.finishedStatusOf("someFutureTerminalPhase"))
    }

    // ---------------------------------------------------------------- attention
    @Test
    fun `a pending interaction is announced once`() {
        val state = NotifyState()
        val first = state.apply("ws", listOf(task("a", "running", interactionId = "i-1")))
        // 去重的单位是**交互号**，而交互号只住在任务快照上（B4：AttentionEvent 自己那份
        // 冗余副本已删），所以两条断言＝"响了几次" + "响的是哪个号"。
        assertEquals(1, first.attention.size)
        assertEquals("i-1", first.attention[0].task.pendingInteractionId)

        val second = state.apply("ws", listOf(task("a", "running", interactionId = "i-1")))
        assertTrue(second.attention.isEmpty())

        // A different interaction is a new thing to announce.
        val third = state.apply("ws", listOf(task("a", "running", interactionId = "i-2")))
        assertEquals(1, third.attention.size)
        assertEquals("i-2", third.attention[0].task.pendingInteractionId)
    }

    @Test
    fun `an interaction without an id is ignored`() {
        val state = NotifyState()
        val update = state.apply("ws", listOf(task("a", "running", interactionId = "")))
        assertTrue(update.attention.isEmpty())
    }

    @Test
    fun `a waiting task is also still running`() {
        val store = TaskStore()
        val update = store.applyWorkspace(
            "ws", "仓库", "/ws", "ws", "active",
            listOf(task("a", "running", interactionId = "i-1")),
        )
        assertEquals(1, update.running.size)
        // 状态词直接取自展示层那个对象：卡片的「等待确认」就是它渲染出来的。
        assertEquals(TaskStatus.WAITING, update.running[0].status)
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
        // A22（2026-09-18）：这条上界原先只钉在一条叫 `there is one id per task, live or
        // finished` 的测试里。那条测试其实**从没验证过它命名的那个不变式**——"同一条记录
        // 原地改状态、完成卡与运行卡同 id"真正被验证的地方是
        // `the store reports notifications to add and to cancel`（撤掉的 id 必须等于完成
        // 事件的 id），它自己只多钉了下面这一句 ⇒ 重复测试删掉，断言接到这里，
        // 号段两端都还留在测试网里（id 越过 ONGOING_ID_RANGE 就会闯进别的通知的号段）。
        assertTrue(first < NotifyState.ONGOING_ID_BASE + NotifyState.ONGOING_ID_RANGE)
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
        // B3：`hasRunningTasks` 已删（它只是 `runningTaskRefs()` 的 `.any { isNotEmpty() }`），
        // 断言语义不变，改用唯一的那个出口。
        assertTrue(store.runningTaskRefs().isNotEmpty())
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
        assertTrue(store.runningTaskRefs().isEmpty())
    }

    @Test
    fun `a workspace update keeps its title when the next frame omits it`() {
        val store = TaskStore()
        store.applyWorkspace("ws-1", "我的仓库", "/a", "ws-1", "active", listOf(task("a", "running")))
        val update = store.applyWorkspace("ws-1", "", "", "", "active", listOf(task("a", "running")))
        assertEquals("我的仓库", update.running[0].workspaceTitle)
    }

    // ------------------------------------------------- 4c 防降级：陈旧读数 + 可信窗口
    //
    // 用户拍板的四条口径，测试逐条对着它们写：
    //   Q12 带保质期（窗口内保护、过期回常规判定）· Q13 **连卡片一起保护**
    //   Q14 保质期约 2 分钟 · Q15 锚点是**应用退到后台**那一刻，**不是**"检出漏增量"那一刻。
    //
    // 驱动用的是**真实入口的形状**：注入层检出序号缺口那一拍发的是「仅标记帧」
    // （`zcode-protocol.js` 的 `_emitSessionsStale`：`stale = true, staleOnly = true`，
    // `sessions` 是缺口之前的**冻结旧读数**）⟹ 原生只取标记、**不落地列表**。
    // 场景一律取真机 2026-09-14 定案的形状：SI 说"已完成"，"在跑"只由 controller 覆盖层撑着。

    /** 4c 用的固定时间轴：所有时刻显式传入，这组测试不碰墙钟。 */
    private val c4BaseMs = 1_700_000_000_000L

    /** SI 的持久相位是终态，而 controller 覆盖层说它正在跑（⇐ 时刻更新的那条源说了算）。 */
    private fun siDoneLiveRunning(store: TaskStore, key: String, atMs: Long) {
        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "active",
            tasks = listOf(task("a", "completedSuccess")),
            nowMs = atMs,
        )
        store.applyLiveTasks(
            listOf(ControllerTasksState.LiveTask(key, "a", "任务 a", "running", "running")),
            nowMs = atMs + 1_000L,
        )
    }

    /** 注入层检出缺口那一拍：**仅标记帧**（列表是冻结的旧读数，语义上不许被当成读数）。 */
    private fun staleMarker(store: TaskStore, key: String, atMs: Long) {
        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "passive",
            tasks = listOf(task("a", "completedSuccess")),
            stale = true,
            staleOnly = true,
            nowMs = atMs,
        )
    }

    private fun logCount(fragment: String): Int =
        Diagnostics.snapshot().count { it.contains(fragment) }

    @Test
    fun `4c 仅标记帧不落地列表：冻结旧读数不许重盖 SI 时刻戳`() {
        val store = TaskStore()
        val key = "ws-4c-marker"
        siDoneLiveRunning(store, key, c4BaseMs)
        assertEquals("起手：在跑只由 controller 覆盖层撑着", listOf(key to "a"), store.runningTaskRefs())

        staleMarker(store, key, c4BaseMs + 2_000L)
        // 这一帧若被当成读数落了地，`siPhasesAt` 会被重盖成"此刻"（晚于 controller 的时刻），
        // SI 的 completedSuccess 立刻压过覆盖层 ⟹ 下面两条断言必红。这就是"只取标记"的钉子。
        assertEquals("仅标记帧不许改动运行读数", listOf(key to "a"), store.runningTaskRefs())
        assertEquals(listOf("a"), store.runningNotifications().map { it.task.sessionId })
        assertEquals("置位记一行（真机 grep 用），且只记一行", 1, logCount("陈旧读数标记：$key"))
    }

    @Test
    fun `4c 窗口内：陈旧工作区的在跑任务不被抹空、不被翻成终态`() {
        val store = TaskStore()
        val key = "ws-4c-card"
        siDoneLiveRunning(store, key, c4BaseMs)
        staleMarker(store, key, c4BaseMs + 2_000L)
        // 应用退到后台 ⇒ 武装可信窗口（Q15 的锚点）。
        store.setBackgrounded(true, nowMs = c4BaseMs + 3_000L)
        assertTrue(store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 3_000L))

        // controller 覆盖层整表清空（真机上那条流会超时/断线）⇒ SI 的终态本来会浮上来。
        val update = store.applyLiveTasks(emptyList(), nowMs = c4BaseMs + 4_000L)
        assertEquals("不许被抹空（闸门读的就是它）", listOf(key to "a"), store.runningTaskRefs())
        assertEquals("不许被抹空（卡片）", listOf("a"), update.running.map { it.task.sessionId })
        assertEquals("Q13：连卡片一起保护", listOf("a"), store.runningNotifications().map { it.task.sessionId })
        assertTrue("也不许被翻成终态——否则会冒出一张假的『已完成』", update.completed.isEmpty())

        // ⚠️ 上面那一拍**不足以**证明这条保护：相位从 running 翻成终态时会先开既有的
        // 观察窗（COMPLETION_HOLD_MS），窗内"仍算运行中"与"被保护"长得一模一样——
        // 第一次写这条测试就踩了这个坑（把保护整段关掉，上面三条断言照样全绿）。
        // 所以必须等窗走完再验一次：那一拍之后还留在运行集里，才真是这条保护干的。
        val afterHold = store.flushDueCompletions(c4BaseMs + 4_000L + NotifyState.COMPLETION_HOLD_MS + 1L)
        assertEquals("观察窗走完仍在跑（真保护，不是观察窗的假象）", listOf(key to "a"), store.runningTaskRefs())
        assertEquals("卡片还活着", listOf("a"), afterHold.running.map { it.task.sessionId })
        assertTrue("也不许冒出完成事件", afterHold.completed.isEmpty())
    }

    @Test
    fun `4c 窗口过期：丢弃保护回到常规判定，并逐字留一行可 grep 的日志`() {
        val store = TaskStore()
        val key = "ws-4c-expiry"
        siDoneLiveRunning(store, key, c4BaseMs)
        staleMarker(store, key, c4BaseMs + 2_000L)
        store.setBackgrounded(true, nowMs = c4BaseMs + 3_000L)
        store.applyLiveTasks(emptyList(), nowMs = c4BaseMs + 4_000L)
        // 等观察窗走完再看（理由同上一条测试：观察窗会与保护互相掩盖）。
        store.flushDueCompletions(c4BaseMs + 4_000L + NotifyState.COMPLETION_HOLD_MS + 1L)
        assertEquals("窗口内：保护着", listOf(key to "a"), store.runningTaskRefs())

        val deadline = c4BaseMs + 3_000L + TaskStore.STALE_TRUST_WINDOW_MS
        assertTrue("截止时刻本身仍在窗口内", store.hasStaleWorkspaceInTrustWindow(nowMs = deadline))
        val before = logCount("陈旧读数的可信窗口已过期")
        assertFalse("越过 2 分钟 ⇒ 回到常规判定", store.hasStaleWorkspaceInTrustWindow(nowMs = deadline + 1L))
        assertFalse("过期是单向的，不会自己复活", store.hasStaleWorkspaceInTrustWindow(nowMs = deadline + 60_000L))
        assertEquals("过期恰好记一行（真机回归判据）", before + 1, logCount("陈旧读数的可信窗口已过期"))
        assertTrue(
            "那行日志要能看出是哪个工作区在保护期里",
            Diagnostics.snapshot().any { it.contains("陈旧读数的可信窗口已过期") && it.contains(key) },
        )
        // "回到常规判定"不是一句空话：被顶回去的相位立刻现出原形（SI 说已完成）。
        assertTrue("窗口一过，SI 的终态照常生效", store.runningTaskRefs().isEmpty())
        assertTrue(store.runningNotifications().isEmpty())
    }

    @Test
    fun `4c 整窗快照：陈旧标记与可信窗口一并清除`() {
        val store = TaskStore()
        val key = "ws-4c-heal"
        siDoneLiveRunning(store, key, c4BaseMs)
        staleMarker(store, key, c4BaseMs + 2_000L)
        store.setBackgrounded(true, nowMs = c4BaseMs + 3_000L)
        assertTrue(store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 3_000L))

        val before = logCount("陈旧读数已自愈")
        // 整窗快照（普通帧）到达 = 缺口自愈：注入层只在成功应用过一帧之后才发这种帧。
        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "passive",
            tasks = listOf(task("a", "completedSuccess")),
            nowMs = c4BaseMs + 5_000L,
        )
        assertFalse("自愈 ⇒ 可信窗口一并收掉", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 5_000L))
        assertEquals("自愈也要留一行，否则真机分不清自愈与一直陈旧", before + 1, logCount("陈旧读数已自愈"))
        // 保护解除后照**常规判定**走：这份快照说已完成 ⟹ 进既有的观察窗（COMPLETION_HOLD_MS，
        // 真机 0.4–0.55s 的轮次缝就是它吃掉的），窗口走完照常宣布完成、退出运行集。
        // 注意这正是与保护期的**分界**：保护期内连这个完成都不会宣布（见上面那条测试）。
        val flushed = store.flushDueCompletions(c4BaseMs + 5_000L + NotifyState.COMPLETION_HOLD_MS + 1L)
        assertEquals("自愈后照常宣布完成（保护不再插手）", 1, flushed.completed.size)
        assertTrue("完成之后退出运行集", store.runningTaskRefs().isEmpty())
    }

    @Test
    fun `4c 闸门：只有退后台时已有陈旧工作区才放行，且只放行 2 分钟`() {
        val running = "ws-4c-gate-running"
        val stale = "ws-4c-gate-stale"
        // ①② 反例：一份**没有陈旧读数**的 store —— 退后台不武装窗口（不留一个空窗口让闸门敞开）。
        val busy = TaskStore()
        busy.setBackgrounded(true, nowMs = c4BaseMs)
        assertFalse("没有陈旧读数就没有可保护的东西", busy.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs))
        busy.applyWorkspace(
            key = running, title = "仓库", path = running, identity = running, source = "active",
            tasks = listOf(task("a", "running")),
            nowMs = c4BaseMs + 1_000L,
        )
        busy.setBackgrounded(true, nowMs = c4BaseMs + 2_000L)
        assertFalse("有在跑任务但不陈旧，同样不武装", busy.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 2_000L))

        // ③ **用户痛点的现场**：一个陈旧工作区，而这一刻**一条在跑任务都没有**
        //（缺口吞掉的正是"有新任务开始"）⟹ 闸门原来的判据 runningRefs.isEmpty() 会说
        // "接管也没内容可推"。换一份干净 store，因为 runningTaskRefs() 是所有工作区的并集。
        val store = TaskStore()
        store.applyWorkspace(
            key = stale, title = "仓库", path = stale, identity = stale, source = "passive",
            tasks = listOf(task("b", "completedSuccess")),
            nowMs = c4BaseMs + 2_500L,
        )
        staleMarker(store, stale, c4BaseMs + 3_000L)
        store.setBackgrounded(true, nowMs = c4BaseMs + 4_000L)
        assertTrue("现场前提：这一刻确实一条在跑任务都没有", store.runningTaskRefs().isEmpty())
        val hold = store.staleGateHold(nowMs = c4BaseMs + 4_000L)
        assertTrue("闸门不许仅因『没有在跑任务』而关（Q13 第二句）", hold.holds)
        assertEquals(listOf(stale), hold.workspaceKeys)
        // Q14：保质期 = 约 2 分钟。这条**故意钉住数值**——它是用户拍板的行为参数
        // （直接决定"误接管把页面顶掉"的风险敞口多久），改动必须是一次显式决定，
        // 不能靠顺手调参。下面的用例都拿这个常量算时刻，常量本身漂了它们照样全绿，
        // 所以这一行不能省。
        assertEquals("Q14：保质期 = 2 分钟", 120_000L, TaskStore.STALE_TRUST_WINDOW_MS)
        assertEquals("Q14：约 2 分钟", TaskStore.STALE_TRUST_WINDOW_MS, hold.remainingMs)
        assertTrue(store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 4_000L + TaskStore.STALE_TRUST_WINDOW_MS))
        // ④ 保质期一到就关：同一个陈旧事实不再构成保护（缺口可能到页面重载才自愈 ⟹ 必须有这条）。
        val after = c4BaseMs + 4_000L + TaskStore.STALE_TRUST_WINDOW_MS + 1L
        assertFalse(store.hasStaleWorkspaceInTrustWindow(nowMs = after))
        assertFalse(store.staleGateHold(nowMs = after).holds)
        // 别的工作区一概不受牵连：这条保护的射程只有陈旧的那一个。
        assertTrue("不陈旧的工作区照常算在跑", busy.runningTaskRefs().isNotEmpty())
    }

    // ------------------------------------------- 4c：锚点从"事件"改成"状态"（用户追加裁定）
    //
    // 口径：`setBackgrounded(已离开)` 是**状态**，两种先后顺序都要覆盖——
    //   ① 离开时已有陈旧记录 ⟹ 离开那一拍武装（上面 ⑤ 与 ② 的路径）；
    //   ② **离开之后才检出缺口** ⟹ 检出那一刻当场武装（下面第一条，今天缺的就是它）；
    // 回前台 ⟹ **立即收**（第二条）；窗口过期 ⟹ 回常规判定（③ 已钉）。

    @Test
    fun `4c 检出缺口时已在后台：当场武装，且同一次离开里不续期`() {
        val store = TaskStore()
        val key = "ws-4c-late-gap"
        // 先离开：这一刻还没有任何陈旧记录 ⟹ 没有东西可武装（窗口不为空而开）。
        store.setBackgrounded(true, nowMs = c4BaseMs)
        assertFalse("离开那一拍没有陈旧读数 ⟹ 不武装", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs))

        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "passive",
            tasks = listOf(task("a", "completedSuccess")),
            nowMs = c4BaseMs + 1_000L,
        )
        staleMarker(store, key, c4BaseMs + 2_000L)
        // ② 这一半就是"离开之后才检出"：离开事件不会再来一次，只有这一句能救它。
        assertTrue("检出缺口时已在后台 ⟹ 当场武装", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 2_000L))
        assertEquals(
            "窗口从**检出**那一刻起算（不是从离开那一刻）",
            TaskStore.STALE_TRUST_WINDOW_MS,
            store.staleGateHold(nowMs = c4BaseMs + 2_000L).remainingMs,
        )

        // 看门狗每 5s 都会同步一次"已离开"：同一次离开里**绝不续期**，否则窗口永远不过期。
        store.setBackgrounded(true, nowMs = c4BaseMs + 62_000L)
        assertEquals(
            "重复同步不续期（剩余 = 120s - 60s）",
            60_000L,
            store.staleGateHold(nowMs = c4BaseMs + 62_000L).remainingMs,
        )
        assertFalse(
            "按检出时刻算满 2 分钟就关",
            store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 2_000L + TaskStore.STALE_TRUST_WINDOW_MS + 1L),
        )
    }

    @Test
    fun `4c 检出缺口时在前台：不武装，随后离开才武装`() {
        val store = TaskStore()
        val key = "ws-4c-front-gap"
        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "passive",
            tasks = listOf(task("a", "completedSuccess")),
            nowMs = c4BaseMs,
        )
        staleMarker(store, key, c4BaseMs + 1_000L)
        assertFalse("人在前台：检出缺口也不武装", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 1_000L))

        store.setBackgrounded(true, nowMs = c4BaseMs + 2_000L)
        assertTrue("后来才离开 ⟹ 离开那一拍用上已有的陈旧记录", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 2_000L))
        assertEquals(
            "窗口从**离开**那一刻起算",
            TaskStore.STALE_TRUST_WINDOW_MS,
            store.staleGateHold(nowMs = c4BaseMs + 2_000L).remainingMs,
        )
    }

    @Test
    fun `4c 回到前台：窗口立即失效，再次离开会重新武装`() {
        val store = TaskStore()
        val key = "ws-4c-foreground"
        store.applyWorkspace(
            key = key, title = "仓库", path = key, identity = key, source = "passive",
            tasks = listOf(task("a", "completedSuccess")),
            nowMs = c4BaseMs,
        )
        staleMarker(store, key, c4BaseMs + 1_000L)
        store.setBackgrounded(true, nowMs = c4BaseMs + 2_000L)
        assertTrue(store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 2_000L))

        val before = logCount("回到前台，提前收掉保护")
        store.setBackgrounded(false, nowMs = c4BaseMs + 3_000L)
        assertFalse("回前台 ⟹ **立即**收掉（不白等 2 分钟）", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 3_000L))
        assertFalse(store.staleGateHold(nowMs = c4BaseMs + 3_000L).holds)
        // 不变量：窗口只在"已离开"态里存在（holds ⟹ 闸门真的在判定），所以回前台的此刻
        // 剩余时间必须是 0，而不是"还在倒数"。
        assertEquals("回前台后窗口不复存在", 0L, store.staleGateHold(nowMs = c4BaseMs + 3_000L).remainingMs)
        assertEquals("提前收也留一行（真机 grep 用）", before + 1, logCount("回到前台，提前收掉保护"))
        // 幂等：前台里反复同步不会刷屏。
        store.setBackgrounded(false, nowMs = c4BaseMs + 4_000L)
        assertEquals("重复同步不重复记", before + 1, logCount("回到前台，提前收掉保护"))

        // 陈旧**事实**不因回前台而消失（只有整窗快照能清）⟹ 再次离开会重新武装。
        store.setBackgrounded(true, nowMs = c4BaseMs + 5_000L)
        assertTrue("再次离开 ⟹ 重新武装", store.hasStaleWorkspaceInTrustWindow(nowMs = c4BaseMs + 5_000L))
        assertEquals(
            "新一轮窗口重新计时",
            TaskStore.STALE_TRUST_WINDOW_MS,
            store.staleGateHold(nowMs = c4BaseMs + 5_000L).remainingMs,
        )
    }
}
