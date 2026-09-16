package com.zcode.remote.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier2 桥协议引擎（RelayBridge）与 sessions-index 状态机的集成测试。
 * 桌面端用脚本模拟（语义对照 tools/fake-desktop.js），覆盖 JS 测试里
 * 同名场景：握手四步、Initialize-先于-ready 竞态、快照/增量、失败不拖垮其它。
 */
class RelayBridgeTest {

    // ------------------------------------------------- SessionsIndexState 单元

    @Test
    fun `snapshot builds an update sorted by last activity`() {
        val state = SessionsIndexState()
        val wire = snapshotWire(
            listOf(
                sessionJson("s-old", "旧任务", "completedSuccess", 100),
                sessionJson("s-new", "新任务", "running", 900),
            ),
        )
        assertTrue(state.applyWire(wire))
        assertEquals(1L, state.seq)
        val update = state.buildUpdate("/repo/x", JSONObject().put("workspacePath", "/repo/x"), "active")
        assertEquals("/repo/x", update.getString("key"))
        assertEquals("x", update.getString("title"))
        assertEquals("active", update.getString("source"))
        val sessions = update.getJSONArray("sessions")
        assertEquals("s-new", sessions.getJSONObject(0).getString("sessionId"))
        assertEquals("running", sessions.getJSONObject(0).getString("phase"))
        assertEquals("s-old", sessions.getJSONObject(1).getString("sessionId"))
    }

    @Test
    fun `deltas upsert and remove and a seq gap flags resync`() {
        val state = SessionsIndexState()
        assertTrue(state.applyWire(snapshotWire(listOf(sessionJson("s1", "一", "running", 50)))))
        // 连续增量：upsert + remove
        val delta = deltaWire(fromSeq = 1, toSeq = 2, deltas = listOf(
            JSONObject().put("op", "session.upserted")
                .put("session", sessionJson("s2", "二", "running", 80)),
            JSONObject().put("op", "session.removed").put("sessionId", "s1"),
        ))
        assertTrue(state.applyWire(delta))
        assertFalse(state.needsResync)
        val ids = state.listSorted().map { it.sessionId }
        assertEquals(listOf("s2"), ids)
        // 缺口：fromSeq 跳到 5 → 拒绝并标重同步
        val gap = deltaWire(fromSeq = 5, toSeq = 6, deltas = listOf(
            JSONObject().put("op", "session.upserted")
                .put("session", sessionJson("s3", "三", "error", 90)),
        ))
        assertFalse(state.applyWire(gap))
        assertTrue(state.needsResync)
        assertEquals(listOf("s2"), state.listSorted().map { it.sessionId })
    }

    @Test
    fun `fragmented logical frames reassemble in any order`() {
        val state = SessionsIndexState()
        val logical = JSONObject()
            .put("toSeq", 3)
            .put("payload", JSONObject()
                .put("kind", "snapshot")
                .put("snapshot", JSONObject()
                    .put("logEpoch", "epoch-1")
                    .put("sessions", JSONArray().put(sessionJson("s1", "一", "running", 10)))))
        val bytes = logical.toString().toByteArray(Charsets.UTF_8)
        val part1 = bytes.copyOfRange(0, 20)
        val part2 = bytes.copyOfRange(20, bytes.size)
        val base = JSONObject()
            .put("topic", "sessions-index/ws-a")
            .put("kind", "fragment")
            .put("logicalFrameId", "lf-1")
            .put("fragmentCount", 2)
        val frag1 = JSONObject(base.toString())
            .put("fragmentIndex", 1)
            .put("dataBase64", java.util.Base64.getEncoder().encodeToString(part2))
        val frag0 = JSONObject(base.toString())
            .put("fragmentIndex", 0)
            .put("dataBase64", java.util.Base64.getEncoder().encodeToString(part1))
        assertFalse(state.applyWire(frag1))
        assertTrue(state.applyWire(frag0))
        assertEquals(3L, state.seq)
        assertEquals(listOf("s1"), state.listSorted().map { it.sessionId })
    }

    // ------------------------------------------------------ BridgeSession 集成

    @Test
    fun `bridge handshake completes and snapshot flows into the update sink`() {
        val outbound = ConcurrentLinkedQueue<JSONObject>()
        val updates = ConcurrentLinkedQueue<JSONObject>()
        val bridge = BridgeSession(
            workspaceKey = "/repo/a",
            scope = JSONObject().put("workspacePath", "/repo/a"),
            bridgeSessionId = "br-1",
            bridgeGeneration = 1,
            sendPayloadOut = { outbound.add(it) },
            onSessionsUpdate = { updates.add(it) },
            onLogLine = {},
        )
        // 桌面先推 Initialize（无参 body），再应答四步调用。
        val desktop = ScriptedDesktop(outbound, bridge)
        desktop.deskStart()
        // Initialize 在 runHandshake 之前送达——门控应已放开。
        bridge.acceptBridgePayload(initializeFrame("br-1"))
        val handshakeDone = CountDownLatch(1)
        val handshakeError = arrayOfNulls<String>(1)
        Thread {
            try {
                bridge.runHandshake()
            } catch (e: Exception) {
                handshakeError[0] = e.message
            } finally {
                handshakeDone.countDown()
            }
        }.start()
        assertTrue("handshake must finish", handshakeDone.await(10, TimeUnit.SECONDS))
        assertEquals(null, handshakeError[0])
        assertEquals("sub-1", bridge.subscriptionId)
        assertTrue("snapshot must reach the sink", updatesLatch(updates, 1).await(10, TimeUnit.SECONDS))
        val update = updates.first()
        assertEquals("/repo/a", update.getString("key"))
        assertEquals("任务一", update.getJSONArray("sessions").getJSONObject(0).getString("title"))
        // ack 已回给桌面端（Initialize 与四步应答都走 rpc-frame 交付）。
        assertTrue("desktop must receive rpc-frame-acks, got ${desktop.acks}", desktop.acks.any { it > 0 })
        desktop.deskStop()
    }

    // ------------------------------------------------------- BridgeManager 集成

    @Test
    fun `a failing workspace does not abort the others and init race is buffered`() {
        val outbound = ConcurrentLinkedQueue<JSONObject>()
        val updates = ConcurrentLinkedQueue<JSONObject>()
        val logs = ConcurrentLinkedQueue<String>()
        val manager = BridgeManager(
            sendPayloadOut = { outbound.add(it) },
            onSessionsUpdate = { updates.add(it) },
            onLogLine = { logs.add(it) },
        )
        val workspaces = listOf(
            JSONObject().put("workspacePath", "/repo/broken"),
            JSONObject().put("workspacePath", "/repo/healthy"),
        )
        val desktop = ManagerDesktop(outbound, manager, workspaces)
        desktop.mgrStart()
        manager.beginCoverage(workspaces)
        assertTrue(
            "one healthy workspace must stream; logs=$logs",
            updatesLatch(updates, 1).await(15, TimeUnit.SECONDS),
        )
        assertEquals("/repo/healthy", updates.first().getString("key"))
        assertTrue("the failing workspace must be logged, got $logs", logs.any { it.contains("bridge open failed for /repo/broken") })
        desktop.mgrStop()
        manager.disposeEverything("test end")
    }

    // ------------------------------------------------------------- 脚本桌面端

    private class ScriptedDesktop(
        private val outbound: ConcurrentLinkedQueue<JSONObject>,
        private val bridge: BridgeSession,
    ) {
        private val assembler = RelayWire.FrameAssembler("br-1")
        private val stop = AtomicBoolean(false)
        private var repliedSubscribe = false
        private var repliedController = false
        val acks = ConcurrentLinkedQueue<Long>()
        private val thread = Thread {
            while (!stop.get()) {
                val payload = outbound.poll()
                if (payload == null) {
                    Thread.sleep(5)
                    continue
                }
                if (payload.optString("zcode_type") == "rpc-frame-ack") {
                    acks.add(payload.optLong("ackMessageSeq"))
                    continue
                }
                if (payload.optString("zcode_type") != "rpc-frame") continue
                val delivered = assembler.accept(payload) ?: continue
                val parsed = RelayWire.parseBody(delivered.message)
                val header = parsed.header
                val id = (header[1] as Number).toLong()
                when (header[3]) {
                    "helloConversationV4" -> reply(id, JSONObject())
                    "initializeConversationV4" -> reply(id, JSONObject().put("ok", true))
                    "subscribeControllerV4" -> {
                        if (!repliedController) {
                            repliedController = true
                            reply(id, JSONObject().put("ack", JSONObject().put("subscriptionId", "sub-ctl-1")))
                        }
                    }
                    "subscribeSessionsIndexV4" -> {
                        if (!repliedSubscribe) {
                            repliedSubscribe = true
                            reply(id, JSONObject().put("ack", JSONObject().put("subscriptionId", "sub-1")))
                        }
                    }
                }
                if (header[0] == RelayWire.REQ_EVENT_LISTEN &&
                    header[3] == RelayWire.EVENT_CONTROLLER_FRAME
                ) {
                    // 运行态流：真机契约里只有 liveStatus 能表达"此刻在跑"。
                    deskPush(
                        id,
                        RelayWire.encodeBody(
                            listOf<Any?>(RelayWire.RES_EVENT_FIRE, id),
                            controllerSnapshotWire("sub-ctl-1", "s-live", "running"),
                        ),
                    )
                }
                if (header[0] == RelayWire.REQ_EVENT_LISTEN &&
                    header[3] == RelayWire.EVENT_SESSIONS_INDEX
                ) {
                    deskPush(
                        id,
                        RelayWire.encodeBody(
                            listOf<Any?>(RelayWire.RES_EVENT_FIRE, id),
                            snapshotWire(listOf(sessionJson("s1", "任务一", "running", 123))),
                        ),
                    )
                }
            }
        }

        private fun reply(id: Long, value: JSONObject) {
            deskPush(id, RelayWire.encodeBody(listOf<Any?>(RelayWire.RES_PROMISE_SUCCESS, id), value))
        }

        // 从 10 起：给测试里预投的 Initialize（messageSeq=1）让位，避免撞号。
        private var seq = 10L
        private fun nextMessageSeq(): Long {
            seq += 1
            return seq
        }

        private fun deskPush(listenOrPromiseId: Long, body: ByteArray) {
            val messageSeq = nextMessageSeq()
            val frames = RelayWire.fragmentMessage(body, "br-1", messageSeq = messageSeq, nextSeq = messageSeq)
            for (frame in frames) bridge.acceptBridgePayload(frame)
        }

        fun deskStart() {
            thread.start()
        }

        fun deskStop() {
            stop.set(true)
        }
    }

    private class ManagerDesktop(
        private val outbound: ConcurrentLinkedQueue<JSONObject>,
        private val manager: BridgeManager,
        private val workspaces: List<JSONObject>,
    ) {
        private val stop = AtomicBoolean(false)
        private val assemblers = java.util.concurrent.ConcurrentHashMap<String, RelayWire.FrameAssembler>()
        private val subscriptionReplied = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val thread = Thread {
            while (!stop.get()) {
                val payload = outbound.poll()
                if (payload == null) {
                    Thread.sleep(5)
                    continue
                }
                when (payload.optString("zcode_type")) {
                    "workspace-list-request" -> {
                        manager.acceptRelayPayload(
                            JSONObject()
                                .put("zcode_type", "workspace-list-response")
                                .put("requestId", payload.optString("requestId"))
                                .put("result", JSONArray(workspaces)),
                        )
                    }
                    "workspace-bridge-open" -> {
                        val id = payload.optString("bridgeSessionId")
                        val path = payload.optString("workspaceKey")
                        if (path == "/repo/broken") {
                            manager.acceptRelayPayload(
                                JSONObject()
                                    .put("zcode_type", "workspace-bridge-error")
                                    .put("requestId", payload.optString("requestId"))
                                    .put("bridgeSessionId", id)
                                    .put("error", "no such workspace"),
                            )
                            continue
                        }
                        // Initialize 先于 ready —— 竞态形态照搬真实桌面端。
                        pushInitialize(id)
                        manager.acceptRelayPayload(
                            JSONObject()
                                .put("zcode_type", "workspace-bridge-ready")
                                .put("requestId", payload.optString("requestId"))
                                .put("bridgeSessionId", id)
                                .put(
                                    "bridge",
                                    JSONObject()
                                        .put("bridgeSessionId", id)
                                        .put("bridgeGeneration", payload.optLong("bridgeGeneration"))
                                        .put("workspaceKey", path),
                                ),
                        )
                    }
                    "rpc-frame" -> desktopRpcFrame(payload)
                }
            }
        }

        private fun desktopRpcFrame(payload: JSONObject) {
            val id = payload.optString("bridgeSessionId")
            val assembler = assemblers.computeIfAbsent(id) {
                RelayWire.FrameAssembler(id, onLog = {})
            }
            val delivered = assembler.accept(payload) ?: return
            val parsed = RelayWire.parseBody(delivered.message)
            val header = parsed.header
            val rid = (header[1] as Number).toLong()
            when (header[3]) {
                "helloConversationV4" -> replyFrame(id, rid, JSONObject())
                "initializeConversationV4" -> replyFrame(id, rid, JSONObject().put("ok", true))
                "subscribeControllerV4" -> {
                    if (subscriptionReplied.putIfAbsent("$id|ctl", true) == null) {
                        replyFrame(id, rid, JSONObject().put("ack", JSONObject().put("subscriptionId", "sub-ctl-$id")))
                    }
                }
                "subscribeSessionsIndexV4" -> {
                    if (subscriptionReplied.putIfAbsent(id, true) == null) {
                        replyFrame(id, rid, JSONObject().put("ack", JSONObject().put("subscriptionId", "sub-$id")))
                    }
                }
            }
            if (header[0] == RelayWire.REQ_EVENT_LISTEN &&
                header[3] == RelayWire.EVENT_CONTROLLER_FRAME
            ) {
                mgrPush(
                    id,
                    rid,
                    RelayWire.encodeBody(
                        listOf<Any?>(RelayWire.RES_EVENT_FIRE, rid),
                        controllerSnapshotWire("sub-ctl-$id", "s-ctl", "running"),
                    ),
                )
            }
            if (header[0] == RelayWire.REQ_EVENT_LISTEN && header[3] == RelayWire.EVENT_SESSIONS_INDEX) {
                mgrPush(
                    id,
                    rid,
                    RelayWire.encodeBody(
                        listOf<Any?>(RelayWire.RES_EVENT_FIRE, rid),
                        snapshotWire(listOf(sessionJson("s1", "健康任务", "running", 200))),
                    ),
                )
            }
        }

        private fun pushInitialize(bridgeId: String) {
            mgrPush(bridgeId, -1, RelayWire.encodeBody(listOf<Any?>(RelayWire.RES_INITIALIZE), null))
        }

        private fun replyFrame(bridgeId: String, requestId: Long, value: JSONObject) {
            mgrPush(bridgeId, requestId, RelayWire.encodeBody(listOf<Any?>(RelayWire.RES_PROMISE_SUCCESS, requestId), value))
        }

        private fun mgrPush(bridgeId: String, ignored: Long, body: ByteArray) {
            val frames = RelayWire.fragmentMessage(body, bridgeId, messageSeq = nextSeq(bridgeId), nextSeq = nextSeq(bridgeId))
            for (frame in frames) manager.acceptRelayPayload(frame)
        }

        private val seqs = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private fun nextSeq(bridgeId: String): Long = seqs.merge(bridgeId, 1L, Long::plus) ?: 1L

        fun mgrStart() {
            thread.start()
        }

        fun mgrStop() {
            stop.set(true)
        }
    }

    private fun updatesLatch(updates: ConcurrentLinkedQueue<JSONObject>, expected: Int): CountDownLatch {
        val latch = CountDownLatch(1)
        Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (updates.size >= expected) break
                Thread.sleep(20)
            }
            latch.countDown()
        }.start()
        return latch
    }
}

private fun sessionJson(id: String, title: String, phase: String, lastActivityAt: Long): JSONObject =
    JSONObject()
        .put("sessionId", id)
        .put("title", title)
        .put("phase", phase)
        .put("lastAssistantPreview", "预览 $id")
        .put("lastActivityAt", lastActivityAt)
        .put("hasBackgroundWork", false)

/** controller/tasks-index 快照：任务的运行态在 `liveStatus`，不在会话索引的 phase。 */
private fun controllerSnapshotWire(subId: String, taskId: String, liveStatus: String): JSONObject =
    JSONObject()
        .put("topic", RelayWire.TOPIC_CONTROLLER_TASKS)
        .put("kind", "complete")
        .put(
            "frame",
            JSONObject()
                .put("topic", RelayWire.TOPIC_CONTROLLER_TASKS)
                .put("subscriptionId", subId)
                .put("logEpoch", "epoch-1")
                .put("toSeq", 1)
                .put(
                    "payload",
                    JSONObject().put("kind", "snapshot").put(
                        "snapshot",
                        JSONObject().put("logEpoch", "epoch-1").put(
                            "tasks",
                            JSONArray().put(
                                JSONObject()
                                    .put(
                                        "address",
                                        JSONObject()
                                            .put("workspacePath", "/repo/a")
                                            .put("taskId", taskId),
                                    )
                                    .put("meta", JSONObject().put("title", "活任务"))
                                    .put("membership", JSONObject().put("active", true))
                                    .put("sourceAvailability", "online")
                                    .put("liveStatus", liveStatus),
                            ),
                        ),
                    ),
                ),
        )

private fun snapshotWire(sessions: List<JSONObject>): JSONObject =
    JSONObject()
        .put("topic", "sessions-index/ws-a")
        .put("kind", "complete")
        .put(
            "frame",
            JSONObject()
                .put("toSeq", 1)
                .put(
                    "payload",
                    JSONObject()
                        .put("kind", "snapshot")
                        .put(
                            "snapshot",
                            JSONObject()
                                .put("workspaceId", "ws-a")
                                .put("logEpoch", "epoch-1")
                                .put("sessions", JSONArray(sessions)),
                        ),
                ),
        )

private fun deltaWire(fromSeq: Long, toSeq: Long, deltas: List<JSONObject>): JSONObject =
    JSONObject()
        .put("topic", "sessions-index/ws-a")
        .put("kind", "complete")
        .put(
            "frame",
            JSONObject()
                .put("fromSeq", fromSeq)
                .put("toSeq", toSeq)
                .put(
                    "payload",
                    JSONObject().put("kind", "deltas").put("deltas", JSONArray(deltas)),
                ),
        )

private fun initializeFrame(bridgeSessionId: String): JSONObject =
    RelayWire.fragmentMessage(
        RelayWire.encodeBody(listOf<Any?>(RelayWire.RES_INITIALIZE), null),
        bridgeSessionId,
        messageSeq = 1,
        nextSeq = 1,
    ).first()
