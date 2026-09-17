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
    /** 桌面端在 bridge-ready 里给的恢复标识；身份三元组的一部分，缺了 ack 会被丢。 */
    val recoveryId: String? = null,
    private val sendPayloadOut: (JSONObject) -> Unit,
    private val onSessionsUpdate: (JSONObject) -> Unit,
    private val onLogLine: (String) -> Unit,
    /** controller 流（运行态）变化时的回调：整表投影，见 [ControllerTasksState.liveTasks]。 */
    private val onLiveTasks: ((List<ControllerTasksState.LiveTask>) -> Unit)? = null,
    /** 会话流推出的运行态（`turnHeader.state`）：会话 id、是否在跑。 */
    private val onTurnState: ((sessionId: String, running: Boolean) -> Unit)? = null,
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
        // **身份三元组必须齐全**：桌面端对入站 payload 做
        // `bridgeSessionId && bridgeGeneration && recoveryId` 全等匹配
        // （bundle `a2t()`），缺字段的 ack 会被直接丢弃。少了 bridgeGeneration
        // 的后果不是"ack 白发"这么轻：桌面端的 replay buffer 会因"发出去的消息
        // 从未被确认"而持续累积，约 45s 后把整座桥判为 degraded
        // （`remote.rpcFrame.ackGraceExceeded`），此后该桥所有 RPC 都不再应答、
        // 推送也停——真机连续三天看到的"新桥好一分钟、随后订阅/resync 集体超时、
        // 只能靠整桥回收续命"就是它（2026-09-14 源码审计定案）。
        val ack = JSONObject()
            .put("zcode_type", "rpc-frame-ack")
            .put("bridgeSessionId", bridgeSessionId)
            .put("bridgeGeneration", bridgeGeneration)
        if (!recoveryId.isNullOrEmpty()) ack.put("recoveryId", recoveryId)
        sendPayloadOut(ack.put("ackMessageSeq", messageSeq))
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
            recoveryId = recoveryId,
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
        // 运行态流排最后：它**不是**覆盖的充分条件（真机实测 controller 订阅会
        // 超时——这条流看起来由桌面端的窗口进程提供，而接管正好把页面顶掉），
        // 所以绝不能让它挡住握手主路径。失败了也只有一行日志。
        subscribeControllerTasks()
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

    /** SI resync 节流：缺口帧成串到达时每帧都置 needsResync，曾造成 300ms 内
     * 6-10 发并发的 resync 风暴（每发都是一次桌面端全量索引重放）。 */
    @Volatile
    private var siResyncAt = 0L
    @Volatile
    private var siResyncPending = false

    private fun requestResync() {
        val subId = subscriptionId ?: return
        val now = System.currentTimeMillis()
        if (siResyncPending || now - siResyncAt < 5_000L) {
            // 冷却窗内再遇缺口：留个标记，冷却到期由在飞的那发补上（它带 base，
            // 桌面端会从 base.seq 重放缺口）。不丢需求，也不再叠加请求。
            siResyncPending = true
            return
        }
        siResyncAt = now
        siResyncPending = false
        val args = JSONObject(scope.toString())
            .put("subscriptionId", subId)
            .put("runtimePolicy", "existing-only")
        // base 是必填可空字段：网页端始终传 base（无基线时显式 null）。省略整个
        // 字段会被桌面端 zod 拒收——真机 2026-09-14 日志里每一条 sessions-index
        // resync 都死于 "expected object, received undefined"，索引缺口从此永远
        // 补不上，桥随后整个变僵尸（只剩二级回收能救）。
        if (state.logEpoch != null) {
            args.put("base", JSONObject().put("logEpoch", state.logEpoch).put("seq", state.seq))
        } else {
            args.put("base", JSONObject.NULL)
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
                // 成功归来后若冷却窗内又撞上缺口，按一次补射。
                if (siResyncPending && System.currentTimeMillis() - siResyncAt >= 5_000L) {
                    siResyncPending = false
                    requestResync()
                }
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
    private val convFramesBySession = ConcurrentHashMap<String, Int>()

    /**
     * 每个会话最后一次收到对话帧的时刻（毫秒）。
     *
     * 用途只有一个：重锚失败时给出"这座桥被晾了多久"的现场量（[sinceLastConvFrameMs]）。
     * 2026-09-16 真机定案——resync **一次**超时**不等于**订阅坏死，而是**桌面端同一时刻
     * 只服务一座桥**（服务权归最近一次订阅成功的那座）：未被服务的那座既没有帧、
     * 也不被应答，只能靠"回收重订"把服务权抢回来。
     */
    private val convLastFrameAtMs = ConcurrentHashMap<String, Long>()
    private val pendingConversationFrames = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val convResyncing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reanchorRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val installingConversationListener = java.util.concurrent.atomic.AtomicBoolean(false)
    private val convAttempting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 收到的对话帧计数（诊断用：区分"桌面端不推"与"我们丢帧"）。 */
    @Volatile
    private var convFrames = 0

    // ------------------------------------------- 运行态流（controller/tasks-index）

    private val controllerState = ControllerTasksState()
    private var controllerListenerId = -1L
    private var controllerSubscriptionId: String? = null
    private val controllerResyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val installingControllerListener = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * controller 流（运行态 `controller/tasks-index`）总开关。
     *
     * ⚠️ **默认关闭**（2026-09-16 01:16 真机定案，而且是**致命**的那一条）：
     * 原生桥对 `zcode-agent.onDynamicControllerFrame` 的那次 `rpc:listen` 会让**桌面端
     * host 进程当场 `uncaughtException` 并自毁**：
     * ```
     * [rpc:listen] zcode-agent.onDynamicSessionsIndexFrame subscribed   ← 索引流没问题
     * [rpc:listen] zcode-agent.onDynamicControllerFrame  FAIL {"name":"Error",…}
     * uncaughtException origin=uncaughtException: …
     * disposing host resources, reason=uncaughtException:uncaughtException
     * → [task-realtime] unregistered host + host process (local-1) exited with code 1
     * ```
     * 配对→崩溃的间隔**固定 4.3 秒**，本轮三次复现（01:05 / 01:13 / 01:16），
     * 每次之后桌面端远程控制整体失效（所有 `workspace-bridge` 回 `desktop-disconnected`）、
     * 页面也跟着 bootstrap 失败。这条流不是"拿不到"的问题，是"**会把桌面端打崩**"。
     *
     * 运行态本来就有第二条腿（会话流 `turnHeader.state` → `TaskStore.applyConversationRunState`，
     * 见 ShellRuntime 的三级优先级：controller > 会话流 > SI 持久态），所以关掉它只损失
     * "controller 提供的更精确的 liveStatus"。要实验时改这里，**但要知道代价**。
     */
    private val controllerStreamEnabled = false

    /**
     * 订阅运行态流。**失败不致命**：拿不到它时行为退回"只有 sessions-index
     * 的持久态"，即接管后的卡片会冻住——所以失败必须留在日志里，别静默。
     */
    private fun subscribeControllerTasks() {
        if (!controllerStreamEnabled) {
            Diagnostics.log(
                "info",
                "controller 流已停用（那次 rpc:listen 会让桌面端 host 崩，运行态走会话流兜底）",
            )
            return
        }
        if (closed || controllerSubscriptionId != null) return
        if (!installingControllerListener.compareAndSet(false, true)) return
        try {
            if (!channels.awaitReady(30_000)) return
            synchronized(this) {
                if (!closed && controllerListenerId < 0) {
                    controllerListenerId = channels.listenEvent(
                        RelayWire.CHANNEL_CONVERSATION,
                        RelayWire.EVENT_CONTROLLER_FRAME,
                        null,
                    ) { data -> onControllerWire(data) }
                }
            }
            val args = JSONObject()
                .put("topic", RelayWire.TOPIC_CONTROLLER_TASKS)
                .put("visibility", "foreground")
            val result = channels.callBlocking(
                RelayWire.CHANNEL_CONVERSATION,
                RelayWire.METHOD_SUBSCRIBE_CONTROLLER,
                listOf<Any?>(args),
                // 短超时：这条流拿不到时我们还有会话尾窗的 turn 状态兜底，
                // 没必要让 30s 的超时拖住覆盖线程。
                12_000,
            ) as? JSONObject
            val subId = result?.optJSONObject("ack")?.optString("subscriptionId").orEmpty()
            if (subId.isEmpty()) {
                onLogLine("controller subscribe: no ack.subscriptionId")
                return
            }
            controllerSubscriptionId = subId
            controllerState.bind(subId)
            onLogLine("subscribed controller tasks-index for $workspaceKey")
        } catch (e: Exception) {
            onLogLine("controller subscribe failed for $workspaceKey: ${e.message}")
        } finally {
            installingControllerListener.set(false)
        }
    }

    private fun onControllerWire(data: Any?) {
        val wire = data as? JSONObject ?: return
        val topic = wire.optString("topic", "")
        if (topic != RelayWire.TOPIC_CONTROLLER_TASKS) return
        if (controllerState.applyWire(wire)) {
            onLiveTasks?.invoke(controllerState.liveTasks())
        }
        if (controllerState.needsResync) {
            controllerState.needsResync = false
            resyncController()
        }
    }

    private fun resyncController() {
        val subId = controllerSubscriptionId ?: return
        if (!controllerResyncing.compareAndSet(false, true)) return
        Thread {
            try {
                val args = JSONObject()
                    .put("subscriptionId", subId)
                    .put("forceSnapshot", true)
                    .put(
                        "base",
                        controllerState.logEpoch?.let {
                            JSONObject().put("logEpoch", it).put("seq", controllerState.seq)
                        } ?: JSONObject.NULL,
                    )
                channels.callBlocking(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.METHOD_RESYNC_CONTROLLER,
                    listOf<Any?>(args),
                    15_000,
                )
                onLogLine("resynced controller tasks-index for $workspaceKey")
            } catch (e: Exception) {
                onLogLine("controller resync failed for $workspaceKey: ${e.message}")
            } finally {
                controllerResyncing.set(false)
            }
        }.start()
    }

    private fun unsubscribeController() {
        val subId = controllerSubscriptionId ?: return
        controllerSubscriptionId = null
        try {
            val args = JSONObject().put("subscriptionId", subId)
            channels.callBlocking(
                RelayWire.CHANNEL_CONVERSATION,
                RelayWire.METHOD_UNSUBSCRIBE_CONTROLLER,
                listOf<Any?>(args),
                2_000,
            )
        } catch (e: Exception) {
            // 尽力而为
        }
        if (controllerListenerId >= 0) {
            try {
                channels.removeListener(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.EVENT_CONTROLLER_FRAME,
                    controllerListenerId,
                )
            } catch (e: Exception) {
                // 关闭路径不再抛
            }
            controllerListenerId = -1L
        }
        controllerState.resetState()
    }

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
        // 幂等：已订阅就不再重复订阅。真机 pre.97 定案——轮询每 12s 无条件重订会把
        // subscriptionId 换掉，桌面端把旧订阅判成 fault.subscription.notOwned，同一拍
        // 的重锚 resync 必然失败并触发整桥回收，形成"每 24s 回收一次"的死循环，桥
        // 永远稳定不下来。订阅建立后的保鲜交给 resyncConversationV4(forceSnapshot)。
        if (convSubscriptions.containsKey(sessionId)) return
        if (!convAttempting.add(sessionId)) return
        installConversationListener()
        if (convListenerId < 0) {
            convAttempting.remove(sessionId)
            return
        }
        try {
            val args = JSONObject(scope.toString())
                .put("sessionId", sessionId)
            // **不传 visibility**：网页端的会话订阅只发 {topic, base}（bundle
            // store.connect），从不带 visibility。我们此前硬编码
            // visibility:"background"——按 schema 它是合法值，但真机表现是
            // "首屏快照之后再无任何 delta 推送、同桥 RPC 也逐渐变聋"，与网页端
            // 前台订阅的行为不符。对齐网页端：省略该字段。
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
            convFramesBySession.remove(sessionId)
            // 新订阅从"现在"起算饿死：否则刚订上就被判成饿死（尤其回收后立刻重锚那一拍）。
            convLastFrameAtMs[sessionId] = System.currentTimeMillis()
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
    /** 当前已建立的对话订阅，用于桥回收时恢复同一批会话。 */
    fun conversationSessionIds(): List<String> = convSubscriptions.keys.toList()

    /** 桥是否还挂着对话订阅（回收决策用：没挂过就没什么可重锚的）。 */
    /** 复制工作区 scope，避免桥回收线程持有可变对象。 */
    fun scopeCopy(): JSONObject = JSONObject(scope.toString())

    /**
     * 周期性重挂：保留对话订阅，在同一 subscription 上强制请求新 snapshot。
     *
     * **阻塞式、不自己起线程**（2026-09-16 改动，与 [BridgeManager.reanchorProgress] 配套）：
     * 多座桥的重锚必须**串行**——真机实测两座桥同毫秒各发一条 resync 时，对端每轮只应答
     * 一个，另一个正好 8.0s 超时，超时再升级成整桥回收，于是每 24s 拆一座桥（输家交替，
     * 10 分钟 25 次）。返回 null = 本轮全部成功；否则是失败原因，由调用方决定要不要回收。
     */
    fun reanchorConversationsBlocking(): String? {
        if (closed || !reanchorRunning.compareAndSet(false, true)) return null
        val entries = convSubscriptions.entries.toList()
        if (entries.isEmpty()) {
            reanchorRunning.set(false)
            return null
        }
        try {
            for ((session, subId) in entries) {
                if (closed || !convResyncing.add(session)) continue
                try {
                    val beforeFrames = convFramesBySession[session] ?: 0
                    val base = JSONObject(scope.toString())
                        .put("subscriptionId", subId)
                        .put("forceSnapshot", true)
                    // 与 SI resync 同理：base 必填可空，无基线时显式 null。
                    val tail = convTails[session]
                    base.put(
                        "base",
                        if (tail != null && !tail.logEpoch().isNullOrEmpty()) {
                            JSONObject().put("logEpoch", tail.logEpoch()).put("seq", tail.seq())
                        } else {
                            JSONObject.NULL
                        },
                    )
                    channels.callBlocking(
                        RelayWire.CHANNEL_CONVERSATION,
                        RelayWire.METHOD_RESYNC_CONV,
                        listOf<Any?>(base),
                        8_000,
                    )
                    // 判据是**这个会话**收到新帧，不是全局计数——别的会话的
                    // 帧不能证明这条订阅还活着。
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (!closed && (convFramesBySession[session] ?: 0) == beforeFrames &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(50L)
                    }
                    if (closed || (convFramesBySession[session] ?: 0) == beforeFrames) {
                        throw RelayWire.WireException("resync returned without a conversation frame")
                    }
                    onLogLine("reanchor conversation for $session (force snapshot)")
                } catch (e: Exception) {
                    return "conversation resync failed for $session: ${e.message}"
                } finally {
                    convResyncing.remove(session)
                }
            }
            return null
        } finally {
            reanchorRunning.set(false)
        }
    }

    /**
     * 最近一次收到任一对话帧距今多少毫秒（没有任何订阅时为 null）。
     *
     * 只用于重锚失败时的现场量——真机证明桌面端**同一时刻只服务一座桥**
     * （服务权归最近订阅成功者），所以"这座桥多久没收到帧"就是它被晾着的证据。
     */
    fun sinceLastConvFrameMs(nowMs: Long = System.currentTimeMillis()): Long? =
        convLastFrameAtMs.values.maxOrNull()?.let { nowMs - it }

    /** 会话流推出的运行态（变化才上报）：sessionId → 归一 phase。 */
    private val convRunState = ConcurrentHashMap<String, String>()

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
        convFramesBySession[id] = (convFramesBySession[id] ?: 0) + 1
        convLastFrameAtMs[id] = System.currentTimeMillis()
        val tail = convTails.getOrPut(id) { RelayWire.ConversationTail() }
        val payload = frame.optJSONObject("payload") ?: return
        val frameLogEpoch = frame.optString("logEpoch").takeIf { it.isNotEmpty() }
        val frameSeq = frame.optLong("toSeq", -1L).takeIf { it >= 0L }
        when (payload.optString("kind")) {
            "snapshot" -> tail.applySnapshot(payload.optJSONObject("snapshot"), frameLogEpoch, frameSeq)
            "deltas" -> {
                // 缺口规则（网页端 applyFrame 的同款）：没有基线时来的 deltas 不可信；
                // 有基线但 fromSeq 对不上本地 seq 就是断档——都必须先补，不能盲接。
                val fromSeq = frame.optLong("fromSeq", -1L)
                val localSeq = tail.seq()
                val hasBase = !tail.logEpoch().isNullOrEmpty()
                if (!hasBase || (fromSeq >= 0L && localSeq > 0L && fromSeq != localSeq)) {
                    onLogLine(
                        "conversation gap for $id (fromSeq=$fromSeq local=$localSeq " +
                            "hasBase=$hasBase) — recovering",
                    )
                    recoverConversation(id, forceSnapshot = !hasBase)
                    return
                }
                tail.applyDeltas(payload.optJSONArray("deltas"), frameSeq)
            }
            else -> return
        }
        reportTurnState(id, tail)
        val text = tail.latestProgressText() ?: return
        if (convLastText[id] == text) return
        convLastText[id] = text
        progressListener?.invoke(id, text)
    }

    /**
     * 会话流自带的运行态（`turnHeader.state`）→ 通知层的运行态覆盖。
     *
     * 只在**变化**时上报，避免每帧都推一次。
     */
    private fun reportTurnState(sessionId: String, tail: RelayWire.ConversationTail) {
        val running = tail.turnRunning() ?: return
        val key = if (running) "running" else "completedSuccess"
        if (convRunState[sessionId] == key) return
        convRunState[sessionId] = key
        onTurnState?.invoke(sessionId, running)
    }

    /**
     * 单会话缺口恢复（网页端 issueRecovery 的同款语义）：
     * 有基线时用 `base` 便宜地补；没基线只能 `forceSnapshot`。
     * 回包 `subscriptionId` 与当前订阅不一致 = 订阅已换代（网页端
     * `resyncGenerationMismatch`）；`notOwned` 则说明订阅已不属于本连接——
     * 这两种都按网页端做法**重订这 1 个会话**，而不是整桥回收。
     */
    private fun recoverConversation(sessionId: String, forceSnapshot: Boolean) {
        val subId = convSubscriptions[sessionId] ?: return
        if (!convResyncing.add(sessionId)) return
        Thread {
            try {
                val tail = convTails[sessionId]
                val hasBase = tail != null && !tail.logEpoch().isNullOrEmpty()
                val args = JSONObject(scope.toString()).put("subscriptionId", subId)
                args.put(
                    "base",
                    if (hasBase) {
                        JSONObject().put("logEpoch", tail!!.logEpoch()).put("seq", tail!!.seq())
                    } else {
                        JSONObject.NULL
                    },
                )
                if (forceSnapshot || !hasBase) args.put("forceSnapshot", true)
                val result = channels.callBlocking(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.METHOD_RESYNC_CONV,
                    listOf<Any?>(args),
                    15_000,
                ) as? JSONObject
                val ackSub = result?.optJSONObject("ack")?.optString("subscriptionId").orEmpty()
                if (ackSub.isNotEmpty() && ackSub != subId) {
                    throw RelayWire.WireException("fault.subscription.resyncGenerationMismatch")
                }
                onLogLine("conversation recovered for $sessionId (base=$hasBase)")
            } catch (e: Exception) {
                onLogLine("conversation recovery failed for $sessionId: ${e.message}")
                if (e.message?.contains("notOwned") == true) {
                    convSubscriptions.remove(sessionId)
                    convTails.remove(sessionId)
                    subscribeConversationProgress(sessionId)
                }
            } finally {
                convResyncing.remove(sessionId)
            }
        }.start()
    }

    private fun unsubscribeConversations() {
        for ((session, subId) in convSubscriptions) {
            try {
                val args = JSONObject(scope.toString()).put("subscriptionId", subId)
                channels.callBlocking(
                    RelayWire.CHANNEL_CONVERSATION,
                    RelayWire.METHOD_UNSUBSCRIBE_CONV,
                    listOf<Any?>(args),
                    // 僵尸桥的退订大概率也无人应答：短超时即可，别拖住后面的重开。
                    2_000,
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
        convResyncing.clear()
        convFramesBySession.clear()
        convLastFrameAtMs.clear()
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
            unsubscribeController()
        } catch (e: Exception) {
            // 关闭路径不再抛
        }
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
                // 同上：关闭路径短超时。
                2_000,
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

/** 桥与桥之间的重锚间隔：给对端留出"一次只处理一个 resync"的余量。 */
private const val REANCHOR_GAP_MS = 500L

/** 刚回收过的桥正在重新握手（约 5s），等它落地再做下一座。 */
private const val REANCHOR_AFTER_RECYCLE_MS = 3_000L

/**
 * 主动轮换的门槛：某座桥超过这么久没有新帧，才算"被对端晾着"，才去回收它换服务权。
 *
 * ⚠️ **必须小于轮换拍长**（`ShellRuntime.LIVE_PROGRESS_POLL_MS × LIVE_REANCHOR_EVERY_POLLS`），
 * 否则拍长会被门槛吃掉：2026-09-17 实测，拍长改到 12s 而门槛还是 15s 时，每拍算下来那座桥
 * 只静默了 12s < 15s ⇒ **每两拍才轮换一次，实际节奏仍是 24s**（日志里 rotate 间隔一格不差是 24s）。
 * 现行 8s < 12s 拍长，所以每拍都轮换。
 *
 * 设这道门槛的本意：**桌面端哪天开始并行服务多座桥时不乱拆**（那样两边都 <8s 有新帧，只做 resync）。
 */
private const val ROTATE_MIN_STARVE_MS = 8_000L

/**
 * 任务摘要（**工作区活动态发现链**，2026-09-17）。
 *
 * 用途：让承载知道"**哪些工作区确实有在跑任务**"，从而只给这些工作区开桥（定向接管），
 * 不必再依赖"用户先把每个工作区在手机上打开一遍"。
 *
 * 数据源（前两条真机每次都在收，此前只记了大小没解析）：
 *  1. `bootstrap-response.result.tasks` —— schema 里 **必填**，真机 24–25 KB；
 *  2. `workspace-list-response.result.tasks` —— schema 里 `optional`，有没有看真机；
 *  3. （未接）页面自己订的 `controller/tasks-index` 帧 —— 纯被动解析，见 `docs/18` §3.7。
 * ⛔ **绝不订阅** `window-controller.onDynamicControllerFrame`：那是 `listen`，真机上把桌面端
 * host 打崩过（`[rpc:listen] … FAIL`）。本文件只读**已经在流的**响应，零新增订阅。
 *
 * 字段形状（子代理 2026-09-17 审计网页 bundle 得出）：
 * `{address:{workspacePath, workspaceIdentity?, taskId?}, meta:{title, workspacePath, …},
 *   liveStatus:'idle'|'running'|'waiting'|'completed'|'error', activity?}`
 * 工作区键算法与页面一致：`workspaceIdentity?.trim() || workspacePath`。
 */
internal object RelayTaskDigest {
    /** 判据：网页链 A 把 `displayStatus` 映射成 `idle|running|completed|error`，
     *  只有 `running` 是"在跑"；`waiting`/`prewarming` 容错留着（真机暂未见）。 */
    private val ACTIVE = setOf("running", "waiting", "prewarming")

    /** 有活动任务的工作区键（去重、保持出现顺序）。 */
    fun activeWorkspaces(result: Any?): List<String> {
        val tasks = tasksOf(result) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            if (statusOf(task) !in ACTIVE) continue
            val key = keyOf(task) ?: continue
            out.add(key)
        }
        return out.toList()
    }

    /**
     * 运行态字段：**真机报文用 `displayStatus`**（156 形状探针实测，扁平字段）；
     * `liveStatus` 是 `controller/tasks-index` 的 `Lue` 形状，留作兼容。
     * 取值枚举（网页链 A）：`idle | running | completed | error`。
     */
    fun statusOf(task: JSONObject): String =
        task.optString("displayStatus").ifEmpty { task.optString("liveStatus") }

    /** 一行摘要：**只含状态与工作区键**，不含任何凭证或正文。 */
    fun describe(result: Any?, source: String): String {
        val tasks = tasksOf(result) ?: return "承载发现[$source]：响应里没有 tasks"
        if (tasks.length() == 0) return "承载发现[$source]：tasks 为空"
        val counts = LinkedHashMap<String, Int>()
        val shown = ArrayList<String>()
        var active = 0
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val status = statusOf(task).ifEmpty { "?" }
            if (status in ACTIVE) active += 1
            counts[status] = (counts[status] ?: 0) + 1
            if (shown.size < 8) shown.add("$status@${keyOf(task) ?: "?"}")
        }
        val hist = counts.entries.joinToString("/") { "${it.key}×${it.value}" }
        return "承载发现[$source]：${tasks.length()} 个任务 · 有活动 $active · $hist · ${shown.joinToString(" | ")}"
    }

    fun keyOf(task: JSONObject): String? {
        // **与页面逐字同规则**：`workspaceIdentity?.trim() || workspacePath`
        // （页面 `GD`/`Co`，src-DHgFesxz.js:44:3789；页面自己也是拿这个 key 去比
        // `bridge.workspaceKey`，见 index:897:338878）。远程工作区必须用 identity，
        // 缺失 identity 才是本地工作区 —— **顺序不能反**（156 我写反过一次）。
        val identity = task.optString("workspaceIdentity").trim()
        if (identity.isNotEmpty()) return identity
        val path = task.optString("workspacePath")
        if (path.isNotEmpty()) return path
        // 兼容 `controller/tasks-index` 的 `Lue` 形状（`address`/`meta` 嵌套，`liveStatus`）：
        // 将来若改用那条源（被动解析），这里不用再改。
        val addr = task.optJSONObject("address") ?: task.optJSONObject("meta")
        if (addr != null) {
            val nestedIdentity = addr.optString("workspaceIdentity").trim()
            if (nestedIdentity.isNotEmpty()) return nestedIdentity
            val nested = addr.optString("workspacePath")
            if (nested.isNotEmpty()) return nested
        }
        // `workspaceLabel` 只是显示名（页面也不拿它当键），兜底用。
        val label = task.optString("workspaceLabel")
        return label.ifEmpty { null }
    }

    /**
     * **形状探针**：只落**键名**（外加名字里带 status/phase/state 的字段值），不落任何标题/路径正文。
     *
     * 用途：把 [activeWorkspaces] 的字段名对准真实报文。2026-09-17 真机实测：
     * `bootstrap-response.result.tasks` 与 `workspace-list-response.result.tasks` **都带 82 个任务**，
     * 但 bundle 审计推出的字段名（`liveStatus` / `address.workspacePath` / `meta.workspacePath`）
     * **一个都没命中**（摘要全是 `?`）——bootstrap 的 `tasks` 元素类型与 controller 的 `Lue` 不是同一个。
     */
    fun shapeOf(result: Any?): String {
        val tasks = tasksOf(result) ?: return "承载发现形状：result 里没有 tasks"
        if (tasks.length() == 0) return "承载发现形状：tasks 为空"
        val first = tasks.optJSONObject(0) ?: return "承载发现形状：tasks[0] 不是对象"
        fun keys(o: JSONObject?): String =
            o?.keys()?.asSequence()?.joinToString(",") ?: "-"
        val sb = StringBuilder("承载发现形状：tasks[0]=[${keys(first)}]")
        for (name in listOf("address", "meta", "activity", "workspace", "task", "status", "payload")) {
            first.optJSONObject(name)?.let { sb.append(" · $name=[${keys(it)}]") }
        }
        val statusLike = first.keys().asSequence()
            .filter {
                it.contains("status", true) || it.contains("phase", true) || it.contains("state", true)
            }
            .joinToString(",") { "$it=${first.optString(it)}" }
        sb.append(" · 状态类字段: ${statusLike.ifEmpty { "-" }}")
        return sb.toString()
    }

    private fun tasksOf(result: Any?): org.json.JSONArray? = when (result) {
        is org.json.JSONArray -> result
        is JSONObject -> result.optJSONArray("tasks")
        else -> null
    }
}

class BridgeManager(
    private val sendPayloadOut: (JSONObject) -> Unit,
    private val onSessionsUpdate: (JSONObject) -> Unit,
    private val onLogLine: (String) -> Unit,
    /** M4：把某工作区的对话进展回调出去（工作区键、会话 id、文本）。 */
    private val progressSink: ((String, String, String) -> Unit)? = null,
    /** M4：握手时要抢在索引订阅之前订的会话（该工作区当前在跑的任务）。 */
    private val runningSessions: (String) -> List<String> = { emptyList() },
    /** 运行态流（controller/tasks-index）整表回调，见 [ControllerTasksState]。 */
    private val liveTaskSink: ((List<ControllerTasksState.LiveTask>) -> Unit)? = null,
    /** 会话流运行态回调（工作区键、会话 id、是否在跑）——controller 拿不到时的兜底。 */
    private val turnStateSink: ((String, String, Boolean) -> Unit)? = null,
    /** 每座桥的开启结果（工作区键、是否成功）：供上层做失败退避（见 Tier2Probe）。 */
    private val onBridgeResult: ((String, Boolean) -> Unit)? = null,
    private val maxWorkspaces: Int = 12,
) {
    private val relayPending = ConcurrentHashMap<String, PendingRelayRequest>()
    private val bridges = ConcurrentHashMap<String, BridgeSession>()
    private val bridgesById = ConcurrentHashMap<String, BridgeSession>()
    private val idToKey = ConcurrentHashMap<String, String>()
    private var bridgeGeneration = 0L
    private val recycleInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 重锚轮的整体互斥：一次只跑一轮（串行，见 [reanchorProgress]）。 */
    private val reanchorInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 发现链落点：`workspace-list-response.result.tasks` 里"有活动任务"的工作区键。
     * 供 [Tier2Probe.discoveredCoverage] 与 `ShellRuntime.carrierCoverageTargets()` 做定向覆盖。
     */
    @Volatile
    var discoveredActiveWorkspaces: List<String> = emptyList()
        private set

    /** [disposeEverything] 后为 true：回收线程不得再开新桥（manager 已无人消费）。 */
    @Volatile
    private var disposed = false

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
                    onBridgeResult?.invoke(openedBridge.workspaceKey, true)
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
                    onBridgeResult?.invoke(
                        workspaceKeyOf(workspace) ?: workspace.optString("workspacePath"),
                        false,
                    )
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

    /**
     * 周期重锚。**主动轮换 + 失败即回收**（N=2 优化版，2026-09-17）。
     *
     * 真机定案的两条事实（`docs/18` §3.3）：① 桌面端**同一时刻只服务一座桥**
     * （服务权归"最近一次订阅成功"的那座）；② 所以"回收重订"是唯一的换服务手段。
     *
     * 旧写法是"给每座桥都发 resync，谁超时谁被回收"——没被服务的那座**必然**白等
     * 8 秒 RPC 超时 + 3 秒重建等待，一轮 14–17s，静默窗（17–21s）就是这么来的。现在：
     *
     *   ① 其余桥（N=2 时＝当前被服务的那座）：发 resync 要一份新快照（保底拉取）；
     *   ② **最饿的那座**（最近收帧距今最久）：**不等超时**，直接回收换服务权。
     *
     * 于是静默窗 ≈ 一次重建（~5s），周期仍由 [LIVE_REANCHOR_EVERY_POLLS]（12s）决定。
     * 判据行：`reanchor rotate for <key>：最近收帧 Ns 前 ⇒ 主动回收换服务权`。
     *
     * **一轮的耗时与 N 无关（2026-09-17 定案）**：只对**被服务的那座**（最近有帧的）发 resync，
     * 其余一律不发——它们发出去**必然**白等 8s RPC 超时（对端同一时刻只应答一座），
     * 而且超时还会升级成整桥回收。每轮只轮换**最饿的一座**，所以：
     *
     * ```
     * N=1：只有一座 → 照常 resync 它（没有轮换可做）
     * N=2：resync 被服务那座 + 轮换最饿那座            ≈ 5s
     * N=5：同上，中间那三座这一轮什么都不做            ≈ 5s   ← 与 N=2 同量级
     * ```
     *
     * 每座桥因此按顺序轮流拿到服务窗（N 座 → 每座服务 1 轮、静默 N-1 轮）。
     * 桥数上限见 [Tier2Probe.DEFAULT_MAX_COVERAGE]（现行 5，成本取舍；产品上限是 2 张提升卡）。
     */
    fun reanchorProgress() {
        if (disposed || !reanchorInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                val live = bridges.values.toList().filter { it.conversationSessionIds().isNotEmpty() }
                if (live.isEmpty()) return@Thread
                // 谁被服务（最近有帧）／谁最饿（最久没帧）——两个极值，各自一个用途。
                val served = live.minByOrNull { convSilenceMs(it) }
                val starved = live.maxByOrNull { convSilenceMs(it) }
                // ① 只有"被服务的那座"值得发 resync（单桥时它就是 served）。
                if (served != null && (served !== starved || live.size == 1)) {
                    val key = served.workspaceKey
                    val err = served.reanchorConversationsBlocking()
                    if (err != null) {
                        val silent = convSilenceText(convSilenceMs(served))
                        onLogLine("reanchor failed for $key：$err；最近收帧 $silent ⇒ 整桥回收")
                        recycleBridge(key, served.scopeCopy(), served.conversationSessionIds())
                        Thread.sleep(REANCHOR_AFTER_RECYCLE_MS)
                    } else {
                        Thread.sleep(REANCHOR_GAP_MS)
                    }
                }
                // ② 每轮只轮换**最饿的一座**（N≥2 才有意义；只在它真的挨饿时才动）。
                if (live.size >= 2 && starved != null) {
                    val age = convSilenceMs(starved)
                    if (age > ROTATE_MIN_STARVE_MS) {
                        onLogLine(
                            "reanchor rotate for ${starved.workspaceKey}：" +
                                "最近收帧 ${convSilenceText(age)} ⇒ 主动回收换服务权（N=${live.size}）",
                        )
                        recycleBridge(
                            starved.workspaceKey,
                            starved.scopeCopy(),
                            starved.conversationSessionIds(),
                        )
                        Thread.sleep(REANCHOR_AFTER_RECYCLE_MS)
                    }
                }
            } catch (e: Exception) {
                onLogLine("reanchor round failed: ${e.message}")
            } finally {
                reanchorInFlight.set(false)
            }
        }.start()
    }

    /** 距最近一次对话帧的毫秒数；从没收到过按"最久"算（挑轮换目标用）。 */
    private fun convSilenceMs(bridge: BridgeSession): Long =
        bridge.sinceLastConvFrameMs() ?: Long.MAX_VALUE

    private fun convSilenceText(ms: Long): String =
        if (ms == Long.MAX_VALUE) "无帧" else "${ms / 1000}s 前"

    /**
     * 二级回收：同一工作区整桥重建。先取快照（旧桥可能并发关闭中，取不到就
     * 用 resync 时刻的快照兜底），再 close + 移出路由表，最后重新握手并把
     * 原有会话订阅全部恢复。失败只记日志——下一拍 reanchor 会再试。
     */
    private fun recycleBridge(key: String, scope: JSONObject, sessions: List<String>) {
        if (!recycleInFlight.add(key)) return
        Thread {
            try {
                if (disposed) {
                    onLogLine("tier2 bridge recycle skipped for $key (manager disposed)")
                    return@Thread
                }
                val old = bridges[key]
                if (old != null) {
                    bridges.remove(key, old)
                    bridgesById.remove(old.bridgeSessionId, old)
                    idToKey.remove(old.bridgeSessionId)
                    try {
                        old.closeBridge()
                    } catch (e: Exception) {
                        // 关闭路径不再抛
                    }
                }
                // 工作区对象可以由 scope 重建（openBridgeBlocking 只取这两个字段）。
                val workspace = JSONObject()
                    .put("workspacePath", scope.optString("workspacePath", ""))
                if (scope.optString("workspaceIdentity", "").isNotEmpty()) {
                    workspace.put("workspaceIdentity", scope.optString("workspaceIdentity"))
                }
                val reopened = openBridgeBlocking(workspace) ?: return@Thread
                reopened.progressListener = { sessionId, text ->
                    progressSink?.invoke(reopened.workspaceKey, sessionId, text)
                }
                reopened.runHandshake(sessions)
                onLogLine("tier2 bridge recycled for $key (${sessions.size} session(s) restored)")
            } catch (e: Exception) {
                onLogLine("tier2 bridge recycle failed for $key: ${e.message}")
                // 旧桥已从 bridges 摘除：下一拍 reanchorProgress 找不到桥、也不会再
                // 触发回收——活进展会永久停更。15s 后补一次重试（一次性）。
                if (!disposed) {
                    Thread {
                        try {
                            Thread.sleep(15_000L)
                        } catch (e: InterruptedException) {
                            return@Thread
                        }
                        if (!disposed && bridges[key] == null && !recycleInFlight.contains(key)) {
                            onLogLine("tier2 bridge recycle retrying for $key")
                            recycleBridge(key, scope, sessions)
                        }
                    }.start()
                }
            } finally {
                recycleInFlight.remove(key)
            }
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

    /** 桥开启串行锁：inflightOpenId/preOpenBuffer 是单例字段，并发开两座桥
     * （周期重锚回收 + 初始覆盖同时到）会交叉污染对方的 Initialize 缓冲。 */
    private val openLock = Object()

    private fun openBridgeBlocking(workspace: JSONObject): BridgeSession? =
        synchronized(openLock) { openBridgeBlockingLocked(workspace) }

    private fun openBridgeBlockingLocked(workspace: JSONObject): BridgeSession? {
        val key = workspaceKeyOf(workspace) ?: return null
        val bridgeSessionId = randomWireId("zcshell-bridge")
        val requestId = randomWireId("zcshell-bopen")
        val recoveryId = randomWireId("zcshell-recovery")
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
                .put("recoveryId", recoveryId)
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
            // schema 要求 reason + error 两个字段都在；只读 error 会丢掉原因码
            // （desktop-bootstrap-timeout / relay-unavailable 是可重试的）。
            val reason = reply.optString("reason", "unexpected-error")
            val error = reply.optString("error", "")
            throw RelayWire.WireException(
                if (error.isEmpty()) reason else "$reason: $error",
            )
        }
        val info = reply.optJSONObject("bridge") ?: JSONObject()
        val actualId = info.optString("bridgeSessionId").ifEmpty { bridgeSessionId }
        val generation = if (info.has("bridgeGeneration") && !info.isNull("bridgeGeneration")) {
            info.optLong("bridgeGeneration")
        } else {
            bridgeGeneration
        }
        // 身份三元组的第三项：客户端自生成、在 open/数据帧/ack 三处保持一致
        // （网页端也是自己生成 recoveryId 再带进 workspace-bridge-open）。
        val recovery = reply.optString("recoveryId").takeIf { it.isNotEmpty() }
            ?: info.optString("recoveryId").takeIf { it.isNotEmpty() }
            ?: recoveryId
        idToKey[actualId] = key
        idToKey[bridgeSessionId] = key
        val bridge = BridgeSession(
            workspaceKey = key,
            scope = scope,
            bridgeSessionId = actualId,
            bridgeGeneration = generation,
            recoveryId = recovery,
            sendPayloadOut = sendPayloadOut,
            onSessionsUpdate = onSessionsUpdate,
            onLogLine = onLogLine,
            onLiveTasks = liveTaskSink,
            onTurnState = turnStateSink?.let { sink ->
                { sessionId, running -> sink(key, sessionId, running) }
            },
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
        // 定向接管的发现链之一：这条响应的 `tasks` 在 schema 里是可选的，有就白拿一份活动态。
        onLogLine(RelayTaskDigest.describe(result, "workspace-list"))
        onLogLine(RelayTaskDigest.shapeOf(result))
        discoveredActiveWorkspaces = RelayTaskDigest.activeWorkspaces(result)
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
        disposed = true
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
