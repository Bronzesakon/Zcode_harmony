package com.zcode.remote.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * controller/tasks-index 状态机（运行态正源）。
 *
 * 这些用例锁的是**被真机证实会致命的行为**：运行态从 `liveStatus` 读（不是
 * sessions-index 的持久 `phase`）、旧帧必须丢、缺口必须报（而不是盲接）。
 */
class ControllerTasksStateTest {

    private fun address(taskId: String) = JSONObject()
        .put("workspacePath", "E:\\Zcode_harmony")
        .put("taskId", taskId)

    private fun task(taskId: String, liveStatus: String, title: String = "做一个壳") = JSONObject()
        .put("address", address(taskId))
        .put("meta", JSONObject().put("title", title).put("workspacePath", "E:\\Zcode_harmony"))
        .put("membership", JSONObject().put("active", true))
        .put("sourceAvailability", "online")
        .put("liveStatus", liveStatus)

    private fun snapshot(subId: String, toSeq: Long, vararg tasks: JSONObject) = JSONObject()
        .put("kind", "complete")
        .put(
            "frame",
            JSONObject()
                .put("topic", RelayWire.TOPIC_CONTROLLER_TASKS)
                .put("subscriptionId", subId)
                .put("logEpoch", "epoch-1")
                .put("toSeq", toSeq)
                .put(
                    "payload",
                    JSONObject().put("kind", "snapshot").put(
                        "snapshot",
                        JSONObject().put("logEpoch", "epoch-1").put("tasks", JSONArray(tasks.toList())),
                    ),
                ),
        )

    private fun deltas(subId: String, fromSeq: Long, toSeq: Long, vararg ops: JSONObject) = JSONObject()
        .put("kind", "complete")
        .put(
            "frame",
            JSONObject()
                .put("topic", RelayWire.TOPIC_CONTROLLER_TASKS)
                .put("subscriptionId", subId)
                .put("logEpoch", "epoch-1")
                .put("fromSeq", fromSeq)
                .put("toSeq", toSeq)
                .put(
                    "payload",
                    JSONObject().put("kind", "deltas").put("deltas", JSONArray(ops.toList())),
                ),
        )

    @Test
    fun `snapshot projects liveStatus into shell phases`() {
        val state = ControllerTasksState()
        assertTrue(
            state.applyWire(
                snapshot("sub-1", 10L, task("sess_a", "running"), task("sess_b", "completed")),
            ),
        )
        val live = state.liveTasks().associateBy { it.sessionId }
        assertEquals("running", live.getValue("sess_a").phase)
        assertEquals("completedSuccess", live.getValue("sess_b").phase)
        assertEquals("running", live.getValue("sess_a").liveStatus)
        assertEquals("做一个壳", live.getValue("sess_a").title)
    }

    @Test
    fun `waiting and idle map onto the shell phase vocabulary`() {
        // waiting：确实在跑（卡在等用户确认），卡片状态词由 pendingInteraction 呈现。
        // idle：一轮结束，按终态走完成卡片，而不是让卡片无声消失。
        val state = ControllerTasksState()
        state.applyWire(
            snapshot("sub-1", 1L, task("sess_wait", "waiting"), task("sess_idle", "idle")),
        )
        val live = state.liveTasks().associateBy { it.sessionId }
        assertEquals("running", live.getValue("sess_wait").phase)
        assertEquals("completedSuccess", live.getValue("sess_idle").phase)
    }

    @Test
    fun `upsert replaces and remove drops the task`() {
        val state = ControllerTasksState()
        state.applyWire(snapshot("sub-1", 1L, task("sess_a", "running", "旧标题")))
        assertTrue(
            state.applyWire(
                deltas(
                    "sub-1", 1L, 2L,
                    JSONObject().put("op", "task.upserted")
                        .put("task", task("sess_a", "completed", "新标题")),
                    JSONObject().put("op", "task.removed").put("address", address("sess_b")),
                ),
            ),
        )
        val live = state.liveTasks().associateBy { it.sessionId }
        assertEquals("completedSuccess", live.getValue("sess_a").phase)
        assertEquals("新标题", live.getValue("sess_a").title)
        assertEquals(1, live.size)
    }

    @Test
    fun `stale delta is dropped without asking for a resync`() {
        // 网页端在比 fromSeq 之前就 `if(toSeq<=seq) return`。少了这一步，乱序旧帧
        // 会被判成断档，把 resync 打成风暴（真机 pre.97：300ms 内 6-10 发并发）。
        val state = ControllerTasksState()
        state.applyWire(snapshot("sub-1", 10L, task("sess_a", "running")))
        assertFalse(state.applyWire(deltas("sub-1", 5L, 8L)))
        assertFalse(state.needsResync)
        assertEquals("running", state.liveTasks().first().phase)
    }

    @Test
    fun `sequence gap asks for a resync`() {
        val state = ControllerTasksState()
        state.applyWire(snapshot("sub-1", 10L, task("sess_a", "running")))
        assertFalse(state.applyWire(deltas("sub-1", 12L, 13L)))
        assertTrue(state.needsResync)
    }

    @Test
    fun `deltas before any snapshot are refused`() {
        val state = ControllerTasksState()
        state.bind("sub-1")
        assertFalse(state.applyWire(deltas("sub-1", 0L, 1L)))
    }

    @Test
    fun `conversation run state outranks the persisted phase`() {
        // 真机形态：sessions-index 说"全完成"，而会话流还在跑——卡片必须活着。
        val store = TaskStore()
        store.applyWorkspace(
            key = "/repo/a",
            title = "a",
            path = "/repo/a",
            identity = "",
            source = "active",
            tasks = listOf(
                TaskSnapshot(
                    sessionId = "sess_a",
                    title = "任务一",
                    phase = "completedSuccess",
                    preview = "",
                    pendingInteractionId = "",
                    lastActivityAt = 1L,
                    hasBackgroundWork = false,
                ),
            ),
        )
        val update = store.applyConversationRunState("/repo/a", "sess_a", true)
        assertEquals(1, update.running.size)
        assertEquals(listOf("/repo/a" to "sess_a"), store.runningTaskRefs())
        // 一轮结束：**先开观察窗**（真机 0.4–0.55s 的轮次缝就是在这里被吃掉的）——
        // 这一拍不撤卡、也不算完成，任务仍按"运行中"算（2026-09-17）。
        val ended = store.applyConversationRunState("/repo/a", "sess_a", false)
        assertTrue("结束那一拍只开窗", ended.completed.isEmpty())
        assertEquals(1, store.runningTaskRefs().size)
        // 窗口走完（结束后不再有新帧，靠这一拍回灌）才宣布完成并退出运行集。
        val done = store.flushDueCompletions(System.currentTimeMillis() + NotifyState.COMPLETION_HOLD_MS)
        assertEquals(1, done.completed.size)
        assertEquals(0, store.runningTaskRefs().size)
    }

    @Test
    fun `a running task in a workspace we never followed still shows up`() {
        // 真机 2026-09-18 09:14 的现场：页面显示 7 个工作区 41 个任务、任务正在 default 里跑，
        // 而壳只跟随过另一个工作区的 sessions-index（`runningTaskRefs()` 为空 ⇒ 卡片、接管、
        // 提前接管全都无从谈起）。第三条源（页面自己的 controller 流）给出运行态后，壳必须
        // **凭空**长出那个工作区和它的运行任务。
        val store = TaskStore()
        store.applyWorkspace(
            key = "/repo/other",
            title = "别的仓库",
            path = "/repo/other",
            identity = "",
            source = "active",
            tasks = listOf(
                TaskSnapshot(
                    sessionId = "sess_done",
                    title = "已经完事的",
                    phase = "completedSuccess",
                    preview = "",
                    pendingInteractionId = "",
                    lastActivityAt = 1L,
                    hasBackgroundWork = false,
                ),
            ),
        )
        assertEquals(0, store.runningTaskRefs().size)

        val update = store.applyLiveTasks(
            listOf(
                ControllerTasksState.LiveTask(
                    workspaceKey = "C:\\ws\\default",
                    sessionId = "sess_run",
                    title = "正在跑的任务",
                    phase = "running",
                    liveStatus = "running",
                ),
            ),
        )
        assertEquals(listOf("C:\\ws\\default" to "sess_run"), store.runningTaskRefs())
        assertEquals(1, update.running.size)
        assertEquals("运行中 · 正在跑的任务", update.running[0].title)
    }

    @Test
    fun `a newer sessions-index snapshot beats stale live sources`() {
        // 真机 2026-09-18 09:29:36：桌面端暂停任务。SI 当场报了 completedInterrupted，
        // 而两条"活跃"覆盖层都还停在 running（controller 那份是页面被顶掉前的最后一份
        // 快照，会话流那份是暂停前一帧）。固定优先级会让卡片永远显示"运行中"。
        val store = TaskStore()
        store.applyWorkspace(
            key = "/repo/a", title = "a", path = "/repo/a", identity = "", source = "active",
            tasks = listOf(snap("sess_a", "running")),
            nowMs = 1_000L,
        )
        store.applyLiveTasks(
            listOf(ControllerTasksState.LiveTask("/repo/a", "sess_a", "任务一", "running", "running")),
            nowMs = 2_000L,
        )
        store.applyConversationRunState("/repo/a", "sess_a", true, nowMs = 3_000L)
        assertEquals(1, store.runningTaskRefs().size)

        store.applyWorkspace(
            key = "/repo/a", title = "a", path = "/repo/a", identity = "", source = "active",
            tasks = listOf(snap("sess_a", "completedInterrupted")),
            nowMs = 9_000L,
        )
        // 终态先进观察窗（0.4–0.55s 的轮次缝就是在这里被吃掉的），窗内仍按运行中算。
        assertEquals("观察窗内仍算运行中", 1, store.runningTaskRefs().size)
        val done = store.flushDueCompletions(9_000L + NotifyState.COMPLETION_HOLD_MS)
        assertEquals("新到的终态必须真的报一次完成", 1, done.completed.size)
        assertEquals(0, store.runningTaskRefs().size)
    }

    @Test
    fun `a newer live report beats the persisted phase`() {
        // 覆盖层存在的理由（2026-09-14 定案）：SI 的相位是持久态，可能说"全完成"，
        // 而会话流/正文还在流 —— 卡片必须活着。加了时刻表之后这条不变式仍然成立。
        val store = TaskStore()
        store.applyWorkspace(
            key = "/repo/a", title = "a", path = "/repo/a", identity = "", source = "active",
            tasks = listOf(snap("sess_a", "completedSuccess")),
            nowMs = 1_000L,
        )
        assertEquals(0, store.runningTaskRefs().size)
        store.applyConversationRunState("/repo/a", "sess_a", true, nowMs = 2_000L)
        assertEquals(1, store.runningTaskRefs().size)
        // 正文在流 ⇒ 这条会话此刻活着：给它续期，旧 SI 不会把它盖掉。
        store.applyLivePreview("/repo/a", "sess_a", "第七十一次回复", nowMs = 5_000L)
        assertEquals(1, store.runningTaskRefs().size)
        // 反过来：SI 又新报了一次终态，这次它说了算（同样要等观察窗走完）。
        store.applyWorkspace(
            key = "/repo/a", title = "a", path = "/repo/a", identity = "", source = "active",
            tasks = listOf(snap("sess_a", "completedSuccess")),
            nowMs = 9_000L,
        )
        val done = store.flushDueCompletions(9_000L + NotifyState.COMPLETION_HOLD_MS)
        assertEquals(1, done.completed.size)
        assertEquals(0, store.runningTaskRefs().size)
    }

    private fun snap(sessionId: String, phase: String) = TaskSnapshot(
        sessionId = sessionId,
        title = "任务一",
        phase = phase,
        preview = "",
        pendingInteractionId = "",
        lastActivityAt = 1L,
        hasBackgroundWork = false,
    )

    @Test
    fun `controller live status outranks the conversation run state`() {
        val store = TaskStore()
        store.applyConversationRunState("/repo/a", "sess_a", true)
        store.applyLiveTasks(
            listOf(
                ControllerTasksState.LiveTask(
                    workspaceKey = "/repo/a",
                    sessionId = "sess_a",
                    title = "任务一",
                    phase = "completedSuccess",
                    liveStatus = "completed",
                ),
            ),
        )
        assertEquals(0, store.runningTaskRefs().size)
    }
}
