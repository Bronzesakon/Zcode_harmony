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

    @Synchronized
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
    fun runHandshake(progressSessions: List<String> = emptyList()) {
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
        // M4：**必须先订阅对话，再订阅索引**。真机两次对照（2026-09-13）：
        //   18:35 轮询线程抢在握手前把 subscribeConversationV4 发出去
        //        → 立刻回 ACK(snapshot)，进展可用；
        //   20:0x 索引订阅先发（原来的写法）→ 同一座桥上 subscribeConversationV4
        //        **永远不回包**（每 36s 重试、连续 12 分钟全超时）。
        // 索引订阅带 runtimePolicy=existing-only，看来会把这座桥的 runtime 钉死成
        // 索引用途。所以这里同步地、显式地把它排在索引订阅之前（阻塞约 1.5s，
        // 换确定性——别再靠线程竞争去撞对顺序）。
        for (session in progressSessions) {
            subscribeConversationProgress(session)
        }
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

    // ---------------------------------------------- M4：对话详情订阅（跟手主通道）

    private var convListenerId = -1L
    private val convAssembler = RelayWire.LogicalFrameAssembler()
    private val convSubscriptions = ConcurrentHashMap<String, String>()
    private val convTails = ConcurrentHashMap<String, RelayWire.ConversationTail>()
    private val convLastText = ConcurrentHashMap<String, String>()
    private val pendingConversationFrames = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val convResyncing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reanchorRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val installingConversationListener = java.util.concurrent.atomic.AtomicBoolean(false)
    private val convAttempting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 收到的对话帧计数（诊断用：区分"桌面端不推"与"我们丢帧"）。 */
    @Volatile
    private var convFrames = 0

    /** M4 进展回调（BridgeManager 建桥时挂上：握手与轮询共用）。 */
    @Volatile
    var progressListener: ((sessionId: String, text: String) -> Unit)? = null

    /**
     * 订阅某会话的对话详情。**同步阻塞**（调用方自己保证在后台线程）——顺序是
     * 语义的一部分，见 [runHandshake] 里的两次真机对照：对话订阅必须早于索引订阅。
     *
     * 幂等且可反复调用：已订阅→立即返回；有在飞的尝试→跳过；失败后由调用方下一轮
     * 重试（真机踩过：调用方若把"试过一次"记成终态，第一个轮询拍（桥还没开）
     * 就会把该会话永久拉黑）。
     */
    fun subscribeConversationProgress(sessionId: String) {
        subscribeConversationProgressInternal(sessionId, fromReanchor = false)
    }

    private fun subscribeConversationProgressInternal(sessionId: String, fromReanchor: Boolean) {
        if (closed) return
        if (!convAttempting.add(sessionId)) return
        installConversationListener()
        if (convListenerId < 0) {
            convAttempting.remove(sessionId)
            return
        }
        try {
            val args = JSONObject(scope.toString())
                .put("sessionId", sessionId)
                .put("visibility", "background")
            val result = channels.callBlocking(
                RelayWire.CHANNEL_CONVERSATION,
                RelayWire.METHOD_SUBSCRIBE_CONV,
                listOf<Any?>(args),
                30_000,
            ) as? JSONObject
            val ack = result?.optJSONObject("ack")
            val subId = ack?.optString("subscriptionId").orEmpty()
            if (ack == null || subId.isEmpty()) {
                onLogLine("conversation subscribe: no ack.subscriptionId for $sessionId")
                return
            }
            convSubscriptions[sessionId] = subId
            convTails[sessionId] = RelayWire.ConversationTail()
            convLastText.remove(sessionId)
            pendingConversationFrames.remove(subId)?.forEach { onConversationWire(it) }
            onLogLine(
                "subscribed conversation for $sessionId (${ack.optString("mode")}, " +
                    "帧已收 ${convFrames} 个)",
            )
        } catch (e: Exception) {
            onLogLine("conversation subscribe failed for $sessionId: ${e.message}")
        } finally {
            convAttempting.remove(sessionId)
        }
    }

    /**
     * 周期性重挂：退订后重订阅，逼桌面端再推一份 snapshot。
     *
     * **这是真机逼出来的**：pre.87 实测订阅建立那一刻拿到一份 snapshot
     * （"正在执行 Bash"），此后 73 分钟桌面端**一个帧都没再推**——流体云的
     * `when` 冻在原地。所以"最新进展"不能只赌 push：每隔一段时间重挂一次，
     * 最坏也只慢一个重挂周期，而不会回到几十分钟级的滞后。
     */
    /** 周期性重挂：保留对话订阅，在同一 subscription 上强制请求新 snapshot。 */
    fun reanchorConversations() {
        if (closed || !reanchorRunning.compareAndSet(false, true)) return
        val entries = convSubscriptions.entries.toList()
        if (entries.isEmpty()) {
            reanchorRunning.set(false)
            return
        }
        Thread {
            try {
                for ((session, subId) in entries) {
                    if (closed || !convResyncing.add(session)) continue
                    try {
                        val base = JSONObject(scope.toString())
                            .put("subscriptionId", subId)
                            .put("forceSnapshot", true)
                        val tail = convTails[session]
                        if (tail != null && !tail.logEpoch().isNullOrEmpty()) {
                            base.put("base", JSONObject()
                                .put("logEpoch", tail.logEpoch())
                                .put("seq", tail.seq()))
                        }
                        channels.callBlocking(
                            RelayWire.CHANNEL_CONVERSATION,
                            RelayWire.METHOD_RESYNC_CONV,
                            listOf<Any?>(base),
                            20_000,
                        )
                        onLogLine("reanchor conversation for $session (force snapshot)")
                    } catch (e: Exception) {
                        onLogLine("conversation resync failed for $session: ${e.message}")
                    } finally {
                        convResyncing.remove(session)
                    }
                }
            } finally {
                reanchorRunning.set(false)
            }
        }.start()
    }

    /** 必须先注册监听再订阅：ACK 之前到达的帧按 topic 缓冲在桌面端，不怕早发。 */
    private fun installConversationListener() {
        if (closed || convListenerId >= 0 ||
            !installingConversationListener.compareAndSet(false, true)
        ) return
        try {
            if (!channels.awaitReady(30_000)) return
            synchronized(this) {
                if (!closed && convListenerId < 0) {
                    convListenerId = channels.listenEvent(
                        RelayWire.CHANNEL_CONVERSATION,
                        RelayWire.EVENT_CONVERSATION_FRAME,
                        JSONObject(scope.toString()),
                    ) { data -> onConversationWire(data) }
                }
            }
        } finally {
            installingConversationListener.set(false)
        }
    }

    private fun onConversationWire(data: Any?) {
        val frame = convAssembler.acceptEnvelope(data as? JSONObject) ?: return
        val topic = frame.optString("topic")
        if (!topic.startsWith("conversation/")) return
        convFrames += 1
        val subId = frame.optString("subscriptionId")
        var sessionId: String? = null
        for ((session, sub) in convSubscriptions) {
            if (sub == subId) {
                sessionId = session
                break
            }
        }
        val id = sessionId ?: run {
            pendingConversationFrames.compute(subId) { _, frames ->
                val out = frames ?: ArrayList()
                if (out.size < 8) out.add(frame)
                out
            }
            return
        }
        val tail = convTails.getOrPut(id) { RelayWire.ConversationTail() }
        val payload = frame.optJSONObject("payload") ?: return
        val frameLogEpoch = frame.optString("logEpoch").takeIf { it.isNotEmpty() }
        val frameSeq = frame.optLong("toSeq", -1L).takeIf { it >= 0L }
        when (payload.optString("kind")) {
            "snapshot" -> tail.applySnapshot(payload.optJSONObject("snapshot"), frameLogEpoch, frameSeq)
            "deltas" -> tail.applyDeltas(payload.optJSONArray("deltas"), frameSeq)
            else -> return
        }
        val text = tail.latestProgressText() ?: return
        if (convLastText[id] == text) return
        convLastText[id] = text
        progressListener?.invoke(id, text)
    }

    private fun unsubscribeConversations() {
        for ((session, subId) in convSubscriptions) {
            try {
                val args = JSONObject(scope.toString()).put("subscriptionId", subId)
                channels.callBlocking(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.METHOD_UNSUBSCRIBE_CONV,
                    listOf<Any?>(args),
                    10_000,
                )
                onLogLine("unsubscribed conversation for $session")
            } catch (e: Exception) {
                // 尽力而为
            }
        }
        convSubscriptions.clear()
        convTails.clear()
        convLastText.clear()
        pendingConversationFrames.clear()
        reanchoringSessions.clear()
        if (convListenerId >= 0) {
            try {
                channels.removeListener(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.EVENT_CONVERSATION_FRAME,
                    convListenerId,
                )
            } catch (e: Exception) {
                // 关闭路径不再抛
            }
            convListenerId = -1L
        }
    }

    /** 摘监听 + 退订（尽力而为）。 */
    fun closeBridge() {
        if (closed) return
        closed = true
        try {
            unsubscribeConversations()
        } catch (e: Exception) {
            // 关闭路径不再抛
        }
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
    /** M4：把某工作区的对话进展回调出去（工作区键、会话 id、文本）。 */
    private val progressSink: ((String, String, String) -> Unit)? = null,
    /** M4：握手时要抢在索引订阅之前订的会话（该工作区当前在跑的任务）。 */
    private val runningSessions: (String) -> List<String> = { emptyList() },
    private val maxWorkspaces: Int = 12,
) {
    private val relayPending = ConcurrentHashMap<String, PendingRelayRequest>()
    private val bridges = ConcurrentHashMap<String, BridgeSession>()
    private val bridgesById = ConcurrentHashMap<String, BridgeSession>()
    private val idToKey = ConcurrentHashMap<String, String>()
    private var bridgeGeneration = 0L
    @Volatile
    private var disposed = false
    private val reanchorRunning = java.util.concurrent.atomic.AtomicBoolean(false)

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
                    // 用非空局部量：闭包里捕获可空 var 会让智能转换失效（K2 直接报错）。
                    val openedBridge = openBridgeBlocking(workspace) ?: continue
                    bridge = openedBridge
                    openedBridge.progressListener = { sessionId, text ->
                        progressSink?.invoke(openedBridge.workspaceKey, sessionId, text)
                    }
                    openedBridge.runHandshake(runningSessions(openedBridge.workspaceKey))
                    opened += 1
                    onLogLine(
                        "bridge ready for ${openedBridge.workspaceKey} (${openedBridge.bridgeSessionId})",
                    )
                } catch (e: Exception) {
                    if (bridge != null) {
                        bridges.remove(bridge.workspaceKey, bridge)
                        bridgesById.remove(bridge.bridgeSessionId, bridge)
                        idToKey.remove(bridge.bridgeSessionId)
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

    /**
     * 订阅某工作区的对话详情（M4 主通道）→ 回调"最新进展"文本。
     */
    fun subscribeProgress(key: String, sessionId: String) {
        val bridge = bridges[key] ?: return
        bridge.subscribeConversationProgress(sessionId)
    }

    /** 周期重挂（见 [BridgeSession.reanchorConversations]）。 */
    fun reanchorProgress() {
        for (bridge in bridges.values) {
            try {
                bridge.reanchorConversations()
            } catch (e: Exception) {
                onLogLine("reanchor conversation failed: ${e.message}")
            }
        }
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
                val bridge = bridgesById[payload.optString("bridgeSessionId")]
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
        val bridge = BridgeSession(
            workspaceKey = key,
            scope = scope,
            bridgeSessionId = actualId,
            bridgeGeneration = generation,
            sendPayloadOut = sendPayloadOut,
            onSessionsUpdate = onSessionsUpdate,
            onLogLine = onLogLine,
        )
        // Register before replaying: frames arriving after ready route directly to this instance.
        if (bridges.putIfAbsent(key, bridge) != null) {
            bridge.closeBridge()
            return null
        }
        bridgesById[actualId] = bridge
        idToKey[actualId] = key
        val replayIds = setOf(bridgeSessionId, actualId)
        while (true) {
            val buffered = preOpenBuffer.poll() ?: break
            if (buffered.optString("bridgeSessionId") in replayIds) {
                bridge.acceptBridgePayload(buffered)
            }
        }
        inflightOpenId = null
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
        bridgesById.clear()
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
