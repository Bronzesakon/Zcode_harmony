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
        // 一轮结束 → 完成卡片，并退出运行集。
        val done = store.applyConversationRunState("/repo/a", "sess_a", false)
        assertEquals(1, done.completed.size)
        assertEquals(0, store.runningTaskRefs().size)
    }

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
