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
    fun `running to terminal fires exactly one completion`() {
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        val first = state.apply("ws", listOf(task("a", "completed", preview = "done")))
        assertEquals(listOf("a"), first.completed.map { it.task.sessionId })
        assertEquals("done", first.completed[0].task.preview)
        assertFalse(first.completed[0].failed)

        // Still terminal on the next tick: no repeat.
        val second = state.apply("ws", listOf(task("a", "completed")))
        assertTrue(second.completed.isEmpty())
    }

    @Test
    fun `a re-run fires a second completion`() {
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        assertEquals(1, state.apply("ws", listOf(task("a", "completed"))).completed.size)
        state.apply("ws", listOf(task("a", "running")))
        assertEquals(1, state.apply("ws", listOf(task("a", "failed"))).completed.size)
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
            val update = state.apply("ws", listOf(task("a", phase)))
            assertEquals("phase $phase must fire a completion", 1, update.completed.size)
        }
    }

    @Test
    fun `failures are flagged for the wording of the notification`() {
        for (phase in listOf("failed", "error", "cancelled", "completedInterrupted")) {
            val state = NotifyState()
            state.apply("ws", listOf(task("a", "running")))
            assertTrue("$phase must be reported as a failure", state.apply("ws", listOf(task("a", phase))).completed[0].failed)
        }
        val state = NotifyState()
        state.apply("ws", listOf(task("a", "running")))
        assertFalse(state.apply("ws", listOf(task("a", "completedSuccess"))).completed[0].failed)
    }

    @Test
    fun `workspaces track phases independently`() {
        val state = NotifyState()
        state.apply("ws-1", listOf(task("a", "running")))
        state.apply("ws-2", listOf(task("b", "running")))
        // ws-2 reports, ws-1 does not: ws-1's phase must survive.
        val update = state.apply("ws-2", listOf(task("b", "completed")))
        assertEquals(1, update.completed.size)
        assertEquals("ws-2", update.completed[0].workspaceKey)
        // A later ws-1 transition is still detected.
        val later = state.apply("ws-1", listOf(task("a", "completed")))
        assertEquals(1, later.completed.size)
        assertEquals("ws-1", later.completed[0].workspaceKey)
    }

    @Test
    fun `session ids that collide across workspaces do not cross-fire`() {
        val state = NotifyState()
        state.apply("ws-1", listOf(task("same-id", "running")))
        state.apply("ws-2", listOf(task("same-id", "running")))
        val update = state.apply("ws-2", listOf(task("same-id", "completed")))
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
    fun `only two status words exist`() {
        val state = NotifyState()
        assertEquals(TaskStatus.RUNNING, state.statusOf(task("a", "running")))
        assertEquals(TaskStatus.RUNNING, state.statusOf(task("a", "prewarming")))
        assertEquals(TaskStatus.WAITING, state.statusOf(task("a", "running", interactionId = "i")))
    }

    @Test
    fun `the body is status dot preview`() {
        assertEquals(
            "运行中 · 已修改 auth_service",
            NotifyState.formatBody(TaskStatus.RUNNING, "已修改 auth_service"),
        )
        assertEquals("运行中", NotifyState.formatBody(TaskStatus.RUNNING, ""))
        assertEquals("运行中", NotifyState.formatBody(TaskStatus.RUNNING, "   "))
        assertEquals("等待确认 · 需要授权读取 test/", NotifyState.formatBody(TaskStatus.WAITING, "需要授权读取 test/"))
    }

    @Test
    fun `the title falls back to the session id`() {
        assertEquals("session-1", task("session-1", "running", title = "").displayTitle)
        assertEquals("任务名", task("s", "running", title = "任务名").displayTitle)
    }

    @Test
    fun `notification ids are stable and workspace scoped`() {
        val first = NotifyState.notificationIdFor("ws-1", "s-1")
        assertEquals(first, NotifyState.notificationIdFor("ws-1", "s-1"))
        assertNotEquals(first, NotifyState.notificationIdFor("ws-2", "s-1"))
        assertNotEquals(first, NotifyState.notificationIdFor("ws-1", "s-2"))
        assertTrue(first >= NotifyState.ONGOING_ID_BASE)
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
        assertEquals("重构登录", first.title)
        assertEquals("运行中 · 已改 auth", first.body)

        // 'a' finishes, 'b' keeps running: one added, one cancelled, one event.
        val next = store.applyWorkspace(
            key = "ws", title = "仓库", path = "/repo", identity = "ws", source = "active",
            tasks = listOf(task("b", "prewarming", title = "写测试"), task("a", "completed")),
        )
        assertEquals(1, next.running.size)
        assertEquals("b", next.running[0].task.sessionId)
        assertEquals(listOf(first.id), next.removedIds)
        assertEquals(listOf("a"), next.completed.map { it.task.sessionId })
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
