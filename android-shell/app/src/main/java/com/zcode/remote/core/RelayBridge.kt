package com.zcode.remote.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier2 桥协议引擎（M3b）：ChannelClient 状态机 + 桥开启流程。
 *
 * 逐条对照 zcode-protocol.js：ChannelClient（Initialize 门控、promise 配对、
 * 事件监听）、Bridge（rpc-frame 收发 + ack）、subscribeSessionsIndex 四步握手
 * （hello → initialize(clientHello) → subscribe → listen）与 resync。
 * 阻塞式调用（CountDownLatch）替代 JS 的 Promise——握手跑在专属线程，
 * 分发跑在 OkHttp 读线程，两者以锁互斥，无死锁路径。
 */
class RelayChannel(
    private val sendBodyBytes: (ByteArray) -> Unit,
    private val onLogLine: (String) -> Unit,
) {
    private class PendingCall(val latch: CountDownLatch, var result: Any? = null, var error: String? = null)

    private var nextId = 0x100000L
    private val pendingCalls = ConcurrentHashMap<Long, PendingCall>()
    private val eventListeners = ConcurrentHashMap<Long, (Any?) -> Unit>()
    private val initializedLatch = CountDownLatch(1)

    fun markInitialized() {
        initializedLatch.countDown()
    }

    fun awaitReady(timeoutMs: Long): Boolean = initializedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * 阻塞式 promise 调用（对应 JS call()）：Initialize 未到先等门控，
     * 超时/对端报错抛 [RelayWire.WireException]。
     */
    fun callBlocking(channel: String, method: String, arg: Any?, timeoutMs: Long): Any? {
        if (!awaitReady(timeoutMs)) {
            throw RelayWire.WireException("channel init timeout (no Initialize frame)")
        }
        val id = synchronized(this) { nextId++ }
        val pending = PendingCall(CountDownLatch(1))
        pendingCalls[id] = pending
        onLogLine("call $channel.$method id=$id")
        val header = listOf<Any?>(RelayWire.REQ_PROMISE, id, channel, method)
        sendBodyBytes(RelayWire.encodeBody(header, arg))
        if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pendingCalls.remove(id)
            throw RelayWire.WireException("$channel.$method timed out")
        }
        pending.error?.let { throw RelayWire.WireException(it) }
        return pending.result
    }

    /** 注册事件监听；返回监听 id（dispose 用）。仅用于已 Initialize 的通道。 */
    fun listenEvent(channel: String, event: String, arg: Any?, onFire: (Any?) -> Unit): Long {
        val id = synchronized(this) { nextId++ }
        eventListeners[id] = onFire
        onLogLine("listen $channel.$event id=$id")
        val header = listOf<Any?>(RelayWire.REQ_EVENT_LISTEN, id, channel, event)
        sendBodyBytes(RelayWire.encodeBody(header, arg))
        return id
    }

    /** 摘除监听并按协议发 REQ_EVENT_DISPOSE。 */
    fun removeListener(channel: String, event: String, id: Long) {
        if (eventListeners.remove(id) == null) return
        val header = listOf<Any?>(RelayWire.REQ_EVENT_DISPOSE, id, channel, event)
        sendBodyBytes(RelayWire.encodeBody(header, null))
    }

    /** 完整 body 分发（对应 JS handleMessage）：Initialize/promise/event 三路。 */
    fun handleBodyBytes(bytes: ByteArray) {
        val parsed = try {
            RelayWire.parseBody(bytes)
        } catch (e: Exception) {
            onLogLine("ipc: undecodable body: ${e.message}")
            return
        }
        val type = parsed.header.firstOrNull() as? Int ?: return
        if (type == RelayWire.RES_INITIALIZE) {
            markInitialized()
            return
        }
        if (parsed.header.size < 2) return
        val id = (parsed.header[1] as? Number)?.toLong() ?: return
        if (type == RelayWire.RES_EVENT_FIRE) {
            val listener = eventListeners[id]
            if (listener != null) {
                try {
                    listener(parsed.args)
                } catch (e: Exception) {
                    onLogLine("ipc: event handler failed: ${e.message}")
                }
            }
            return
        }
        val pending = pendingCalls.remove(id) ?: return
        when (type) {
            RelayWire.RES_PROMISE_SUCCESS -> pending.result = parsed.args
            RelayWire.RES_PROMISE_ERROR ->
                pending.error = (parsed.args as? JSONObject)?.optString("message")
                    ?.takeIf { it.isNotEmpty() } ?: (parsed.args?.toString() ?: "error")
            RelayWire.RES_PROMISE_ERROR_OBJ ->
                pending.error = parsed.args?.toString() ?: "error"
        }
        pending.latch.countDown()
    }
}

/** 单工作区桥（对应 JS Bridge）：rpc-frame 收发 + 四步握手 + 会话索引订阅。 */
class BridgeSession(
    val workspaceKey: String,
    val scope: JSONObject,
    val bridgeSessionId: String,
    val bridgeGeneration: Long,
    private val sendPayloadOut: (JSONObject) -> Unit,
    private val onSessionsUpdate: (JSONObject) -> Unit,
    private val onLogLine: (String) -> Unit,
) {
    private val senderSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var messageSeq = 0L
    private val assembler = RelayWire.FrameAssembler(bridgeSessionId, onLog = onLogLine)
    val channels = RelayChannel(
        sendBodyBytes = { bytes -> sendFragmented(bytes) },
        onLogLine = { line -> onLogLine("[$workspaceKey] $line") },
    )
    val state = SessionsIndexState()
    var subscriptionId: String? = null
        private set
    private var listenId = -1L
    @Volatile
    var closed = false
        private set

    /** Desktop 推来的 relay payload（rpc-frame/ack）；Initialize 通常在这里到。 */
    fun acceptBridgePayload(payload: JSONObject) {
        if (closed) return
        when (payload.optString("zcode_type")) {
            "rpc-frame" -> {
                val delivered = assembler.accept(payload) ?: return
                sendAck(delivered.messageSeq)
                channels.handleBodyBytes(delivered.message)
            }
            else -> Unit
        }
    }

    private fun sendAck(messageSeq: Long) {
        sendPayloadOut(
            JSONObject()
                .put("zcode_type", "rpc-frame-ack")
                .put("bridgeSessionId", bridgeSessionId)
                .put("ackMessageSeq", messageSeq),
        )
    }

    private fun sendFragmented(bytes: ByteArray) {
        messageSeq += 1
        val frames = RelayWire.fragmentMessage(
            bytes,
            bridgeSessionId,
            messageSeq = messageSeq,
            nextSeq = senderSeq.getAndAdd(
                ((bytes.size + RelayWire.MAX_FRAGMENT_BYTES - 1) / RelayWire.MAX_FRAGMENT_BYTES).toLong(),
            ) + 1,
            bridgeGeneration = bridgeGeneration,
        )
        for (frame in frames) sendPayloadOut(frame)
    }

    /**
     * 四步握手（阻塞，跑在专属线程）：hello → initialize(clientHello) →
     * subscribe → listen。任何一步失败抛 WireException，由管理器记日志跳过。
     */
    fun runHandshake() {
        channels.callBlocking(RelayWire.CHANNEL_CONVERSATION, "helloConversationV4", emptyList<Any?>(), 45_000)
        val clientHello = JSONObject()
            .put("kind", "clientHello")
            .put("protocolVersion", 3)
            .put("clientId", randomWireId("zcshell-client"))
            .put("clientKind", "mobileApp")
            .put("appVersion", "3.6.5")
        channels.callBlocking(
            RelayWire.CHANNEL_CONVERSATION,
            "initializeConversationV4",
            listOf<Any?>(clientHello),
            45_000,
        )
        val subscribeArgs = JSONObject(scope.toString())
            .put("runtimePolicy", "existing-only")
        val result = channels.callBlocking(
            RelayWire.CHANNEL_CONVERSATION,
            RelayWire.METHOD_SUBSCRIBE_SI,
            listOf<Any?>(subscribeArgs),
            60_000,
        )
        val ack = (result as? JSONObject)?.optJSONObject("ack")
        val subId = ack?.optString("subscriptionId").orEmpty()
        if (subId.isEmpty()) {
            throw RelayWire.WireException("subscribeSessionsIndexV4: no ack.subscriptionId")
        }
        subscriptionId = subId
        listenId = channels.listenEvent(
            RelayWire.CHANNEL_CONVERSATION,
            RelayWire.EVENT_SESSIONS_INDEX,
            scope,
        ) { data -> onSessionsWire(data) }
        onLogLine("subscribed sessions-index for $workspaceKey")
    }

    private fun onSessionsWire(data: Any?) {
        val wire = data as? JSONObject ?: return
        val topic = wire.optString("topic", "")
        if (!topic.startsWith("sessions-index/")) return
        if (state.applyWire(wire)) {
            onSessionsUpdate(state.buildUpdate(workspaceKey, scope, "active"))
        }
        if (state.needsResync) {
            state.needsResync = false
            requestResync()
        }
    }

    private fun requestResync() {
        val subId = subscriptionId ?: return
        val args = JSONObject(scope.toString())
            .put("subscriptionId", subId)
            .put("runtimePolicy", "existing-only")
        if (state.logEpoch != null) {
            args.put("base", JSONObject().put("logEpoch", state.logEpoch).put("seq", state.seq))
        }
        onLogLine("resync sessions-index for $workspaceKey (gap at seq ${state.seq})")
        Thread {
            try {
                channels.callBlocking(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.METHOD_RESYNC_SI,
                    listOf<Any?>(args),
                    30_000,
                )
            } catch (e: Exception) {
                onLogLine("resync failed for $workspaceKey: ${e.message}")
            }
        }.start()
    }

    /** 摘监听 + 退订（尽力而为）。 */
    fun closeBridge() {
        if (closed) return
        closed = true
        if (listenId >= 0) {
            try {
                channels.removeListener(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.EVENT_SESSIONS_INDEX,
                    listenId,
                )
            } catch (e: Exception) {
                // 关闭路径不再抛
            }
        }
        val subId = subscriptionId ?: return
        try {
            val args = JSONObject(scope.toString())
                .put("subscriptionId", subId)
                .put("runtimePolicy", "existing-only")
            channels.callBlocking(
                RelayWire.CHANNEL_CONVERSATION,
                RelayWire.METHOD_UNSUBSCRIBE_SI,
                listOf<Any?>(args),
                15_000,
            )
        } catch (e: Exception) {
            // 尽力而为
        }
    }
}

/**
 * 桥管理器（对应 JS RemoteClient.start 的主动订阅面）：workspace-list →
 * 逐工作区 open+subscribe（失败跳过不拖垮其它）→ 会话更新回调。
 * Tier2 接管模式下页面已死，无需页面覆盖判断。
 */
class BridgeManager(
    private val sendPayloadOut: (JSONObject) -> Unit,
    private val onSessionsUpdate: (JSONObject) -> Unit,
    private val onLogLine: (String) -> Unit,
    private val maxWorkspaces: Int = 12,
) {
    private val relayPending = ConcurrentHashMap<String, PendingRelayRequest>()
    private val bridges = ConcurrentHashMap<String, BridgeSession>()
    private val idToKey = ConcurrentHashMap<String, String>()
    private var bridgeGeneration = 0L

    /**
     * 桥开启在飞窗口的缓冲：桌面端会在 ready 应答**之前**就推送 Initialize
     * （JS 注释点名的竞态），而桥对象要等 ready 才出生——这段时间的 rpc-frame
     * 按到达序缓冲，桥出生后回放（对照 JS _pendingBridgePayloads）。
     */
    @Volatile
    private var inflightOpenId: String? = null
    private val preOpenBuffer = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    private class PendingRelayRequest(val latch: CountDownLatch, @Volatile var reply: JSONObject? = null)

    /** 开始覆盖（跑在专属线程）；[workspaces] 为 desktop 的 workspace 对象列表。 */
    fun beginCoverage(workspaces: List<JSONObject>) {
        Thread {
            var opened = 0
            for (workspace in workspaces) {
                if (Thread.currentThread().isInterrupted) return@Thread
                if (opened >= maxWorkspaces) break
                var bridge: BridgeSession? = null
                try {
                    bridge = openBridgeBlocking(workspace) ?: continue
                    // 必须先注册再握手：四步握手的应答帧经 acceptRelayPayload
                    // 按 idToKey→bridges 路由回来，注册晚了会全部落空（超时）。
                    bridges[bridge.workspaceKey] = bridge
                    bridge.runHandshake()
                    opened += 1
                    onLogLine("bridge ready for ${bridge.workspaceKey} (${bridge.bridgeSessionId})")
                } catch (e: Exception) {
                    if (bridge != null) {
                        bridges.remove(bridge.workspaceKey)
                        try {
                            bridge.closeBridge()
                        } catch (closeError: Exception) {
                            // 关闭路径不再抛
                        }
                    }
                    onLogLine("bridge open failed for ${workspace.optString("workspacePath")}: ${e.message}")
                }
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            onLogLine("tier2 active coverage: $opened workspace(s)")
        }.start()
    }

    /** relay `data` payload 分发：桥帧 / 桥开启应答 / 工作区列表应答。 */
    fun acceptRelayPayload(payload: JSONObject) {
        when (payload.optString("zcode_type")) {
            "workspace-list-response", "workspace-bridge-ready", "workspace-bridge-error" -> {
                val requestId = payload.optString("requestId")
                val pending = relayPending.remove(requestId)
                pending?.reply = payload
                pending?.latch?.countDown()
            }
            "rpc-frame", "rpc-frame-ack" -> {
                val key = idToKey[payload.optString("bridgeSessionId")]
                val bridge = key?.let { bridges[it] }
                if (bridge != null) {
                    bridge.acceptBridgePayload(payload)
                } else if (inflightOpenId != null) {
                    // 桥还没出生（Initialize 竞态窗口）：缓冲，桥出生后回放。
                    if (preOpenBuffer.size < 64) preOpenBuffer.add(payload)
                }
            }
            else -> Unit
        }
    }

    private fun awaitRelayReply(requestId: String, timeoutMs: Long): JSONObject? {
        val pending = PendingRelayRequest(CountDownLatch(1))
        relayPending[requestId] = pending
        if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            relayPending.remove(requestId)
            return null
        }
        return pending.reply
    }

    private fun openBridgeBlocking(workspace: JSONObject): BridgeSession? {
        val key = workspaceKeyOf(workspace) ?: return null
        val bridgeSessionId = randomWireId("zcshell-bridge")
        val requestId = randomWireId("zcshell-bopen")
        val scope = JSONObject().put("workspacePath", workspace.optString("workspacePath", ""))
        val identity = workspace.optString("workspaceIdentity", "")
        if (identity.isNotEmpty()) scope.put("workspaceIdentity", identity)
        bridgeGeneration += 1
        inflightOpenId = bridgeSessionId
        sendPayloadOut(
            JSONObject()
                .put("zcode_type", "workspace-bridge-open")
                .put("requestId", requestId)
                .put("bridgeSessionId", bridgeSessionId)
                .put("bridgeGeneration", bridgeGeneration)
                .put("workspaceKey", key),
        )
        val reply = try {
            awaitRelayReply(requestId, 30_000)
                ?: throw RelayWire.WireException("workspace-bridge-open timed out")
        } catch (e: Exception) {
            inflightOpenId = null
            preOpenBuffer.clear()
            throw e
        }
        if (reply.optString("zcode_type") == "workspace-bridge-error") {
            inflightOpenId = null
            preOpenBuffer.clear()
            throw RelayWire.WireException(reply.optString("error", "workspace-bridge-error"))
        }
        val info = reply.optJSONObject("bridge") ?: JSONObject()
        val actualId = info.optString("bridgeSessionId").ifEmpty { bridgeSessionId }
        val generation = if (info.has("bridgeGeneration") && !info.isNull("bridgeGeneration")) {
            info.optLong("bridgeGeneration")
        } else {
            bridgeGeneration
        }
        idToKey[actualId] = key
        idToKey[bridgeSessionId] = key
        inflightOpenId = null
        val bridge = BridgeSession(
            workspaceKey = key,
            scope = scope,
            bridgeSessionId = actualId,
            bridgeGeneration = generation,
            sendPayloadOut = sendPayloadOut,
            onSessionsUpdate = onSessionsUpdate,
            onLogLine = onLogLine,
        )
        // 回放竞态窗口里到达的帧（Initialize 通常在其中）。
        val replayIds = setOf(bridgeSessionId, actualId)
        while (true) {
            val buffered = preOpenBuffer.poll() ?: break
            if (buffered.optString("bridgeSessionId") in replayIds) {
                bridge.acceptBridgePayload(buffered)
            }
        }
        return bridge
    }

    /** 工作区列表（对应 JS listWorkspaces）。 */
    fun listWorkspacesBlocking(timeoutMs: Long = 20_000): List<JSONObject> {
        val requestId = randomWireId("zcshell-ws")
        sendPayloadOut(JSONObject().put("zcode_type", "workspace-list-request").put("requestId", requestId))
        val reply = awaitRelayReply(requestId, timeoutMs)
            ?: throw RelayWire.WireException("workspace-list-request timed out")
        val result = reply.opt("result")
        val list = when (result) {
            is org.json.JSONArray -> result
            is JSONObject -> result.optJSONArray("workspaces")
            else -> null
        } ?: org.json.JSONArray()
        val out = ArrayList<JSONObject>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (workspaceKeyOf(item) != null) out.add(item)
        }
        onLogLine("workspace list: ${out.size}")
        return out
    }

    fun disposeEverything(reason: String) {
        for ((_, bridge) in bridges) {
            try {
                bridge.closeBridge()
            } catch (e: Exception) {
                // 关闭路径不再抛
            }
        }
        bridges.clear()
        idToKey.clear()
        relayPending.clear()
        onLogLine("tier2 bridges disposed ($reason)")
    }
}

internal fun workspaceKeyOf(workspace: JSONObject): String? {
    val path = workspace.optString("workspacePath", "")
    if (path.isNotEmpty()) return path
    val identity = workspace.optString("workspaceIdentity", "")
    if (identity.isNotEmpty()) return identity
    return null
}

internal fun randomWireId(prefix: String): String =
    "$prefix-${java.lang.Long.toString(System.currentTimeMillis(), 36)}-" +
        java.lang.Long.toString((Math.random() * 0x7FFFFFFF).toLong(), 36)
