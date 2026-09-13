package com.zcode.remote.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * relay 配对证明：`proof = base64url(HMAC-SHA256(key=passHash, msg=nonce|role|deviceSid))`。
 *
 * 与页面实现逐字对齐（docs/05 快照 @4696180：`calculateProof(passHash, nonce, role, deviceSid)`，
 * HMAC 密钥是 passHash 的 UTF-8 字节，消息是 `nonce|role|deviceSid` 的 UTF-8 字节，
 * 输出 base64url 无填充）。role 固定为 `terminal`。
 */
internal fun relayProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal("$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** Tier2 的连接凭证。只驻内存；任何日志路径都不得打印这些字段。 */
data class RelayCreds(
    val wsUrl: String,
    val deviceSid: String,
    val passHash: String,
    val deviceMid: String?,
)

/**
 * Tier2 — 原生直连 relay 客户端，完全不经过 WebView/renderer。
 *
 * 两种模式（2026-09-13 拍板的后台独占形态）：
 *   * 实验模式（durationMs>0，诊断指令 tier2_test）：配对跑一轮自动关闭，
 *     用于 KICK/takeover 语义观测——已定案：配对层面单控制端互斥。
 *   * 接管模式（durationMs<=0）：后台 Tier1 判死时由 ShellRuntime 启动；
 *     配对成功后启动 BridgeManager 覆盖（M3b/c：workspace-list → 每工作区
 *     4-RPC 握手 + sessions-index 订阅），任务事件经 [sessionsSink] 直接进
 *     原生 TaskStore/通知链路。断线自动重连（退避 10s），直到 [stop]（回前台
 *     交还）。接管会踢掉页面连接（KICK 互斥），因此前台绝不进入本模式。
 *
 * 握手链与页面逐字对齐（docs/05 @4699005/@4702045）：
 *   connect → auth_init{role:'terminal', device_sid, meta, client_ts}
 *   ← auth_challenge{nonce}
 *   → auth_response{device_sid, proof, client_ts}
 *   ← auth_ack{pair_status} → paired
 *
 * 日志只打阶段与 pair_status——凭证、URL、sid/hash/mid 一律不落日志。
 */
object Tier2Probe {

    enum class Phase { IDLE, CONNECTING, AUTHENTICATING, PAIRED, CLOSED }

    @Volatile
    private var phase: Phase = Phase.IDLE

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var creds: RelayCreds? = null

    /** 接管模式的任务事件出口（ShellRuntime 注入，喂 TaskStore/通知）。 */
    @Volatile
    var sessionsSink: ((JSONObject) -> Unit)? = null

    private var heartbeatCount = 0
    private var ackCount = 0
    private var startedAtMs = 0L
    private var persistent = false
    private var stopping = false
    private var reconnectAttempt = 0
    private var durationTimer: java.util.Timer? = null
    private var heartbeatTimer: java.util.Timer? = null
    private var reconnectTimer: java.util.Timer? = null

    @Volatile
    private var bridgeManager: BridgeManager? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val currentPhase: Phase get() = phase

    /** 探针是否持有（或正在建立）连接——接管触发与前台交还都看它。 */
    fun isRunning(): Boolean = phase != Phase.IDLE && phase != Phase.CLOSED

    /**
     * @param durationMs 探针存活时长；<=0 表示接管模式（持久，直到 [stop]，
     *   且配对成功后启动桥覆盖 + 断线自动重连）。
     */
    fun start(newCreds: RelayCreds, durationMs: Long = 60_000L) {
        if (isRunning()) {
            Diagnostics.log("warn", "Tier2: 已在运行（phase=$phase），忽略重复启动")
            return
        }
        creds = newCreds
        persistent = durationMs <= 0
        stopping = false
        reconnectAttempt = 0
        heartbeatCount = 0
        ackCount = 0
        startedAtMs = System.currentTimeMillis()
        Diagnostics.log(
            "info",
            "Tier2: 启动原生直连探针（${if (persistent) "接管模式" else "duration=${durationMs / 1000}s"}，凭证仅内存）",
        )
        connectNow()
        if (durationMs > 0) {
            durationTimer = java.util.Timer(true).apply {
                schedule(
                    object : java.util.TimerTask() {
                        override fun run() = stop("duration 到点")
                    },
                    durationMs,
                )
            }
        }
        heartbeatTimer = java.util.Timer(true).apply {
            scheduleAtFixedRate(
                object : java.util.TimerTask() {
                    override fun run() = sendHeartbeat()
                },
                10_000L,
                10_000L,
            )
        }
    }

    private fun connectNow() {
        val c = creds ?: return
        phase = Phase.CONNECTING
        val url = buildString {
            append(c.wsUrl)
            if (!c.deviceMid.isNullOrBlank()) {
                append(if (c.wsUrl.contains('?')) "&" else "?")
                append("mid=").append(java.net.URLEncoder.encode(c.deviceMid, "UTF-8"))
            }
        }
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    fun stop(reason: String) {
        stopping = true
        durationTimer?.cancel()
        durationTimer = null
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        reconnectTimer?.cancel()
        reconnectTimer = null
        bridgeManager?.disposeEverything(reason)
        bridgeManager = null
        val s = socket
        socket = null
        val lasted = (System.currentTimeMillis() - startedAtMs) / 1000
        Diagnostics.log(
            "warn",
            "Tier2: 关闭（$reason）phase=$phase 存活=${lasted}s 心跳=$heartbeatCount ack=$ackCount",
        )
        try {
            s?.close(1000, "tier2 done")
        } catch (e: Exception) {
            Diagnostics.log("warn", "Tier2: close 异常 ${e.message}")
        }
        phase = Phase.CLOSED
    }

    private fun startCoverageIfPersistent() {
        if (!persistent) return
        val c = creds ?: return
        val manager = BridgeManager(
            sendPayloadOut = { payload -> sendBusinessPayload(payload, quiet = false) },
            onSessionsUpdate = { update ->
                try {
                    sessionsSink?.invoke(update)
                } catch (e: Exception) {
                    Diagnostics.log("warn", "Tier2: sessions 回调失败 ${e.message}")
                }
            },
            onLogLine = { line -> Diagnostics.log("debug", line) },
        )
        bridgeManager = manager
        Thread {
            try {
                val workspaces = manager.listWorkspacesBlocking()
                if (workspaces.isEmpty()) {
                    Diagnostics.log("warn", "Tier2: 接管模式拿到空工作区列表")
                    return@Thread
                }
                manager.beginCoverage(workspaces)
            } catch (e: Exception) {
                Diagnostics.log("warn", "Tier2: 覆盖启动失败 ${e.message}")
            }
        }.start()
    }

    private fun scheduleReconnect() {
        if (!persistent || stopping) return
        reconnectAttempt += 1
        val delayMs = 10_000L
        if (reconnectAttempt % 6 == 1) {
            Diagnostics.log("warn", "Tier2: 断线，${delayMs / 1000}s 后重连（第 $reconnectAttempt 次）")
        }
        reconnectTimer = java.util.Timer(true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        if (stopping || !persistent) return
                        bridgeManager?.disposeEverything("重连重建")
                        bridgeManager = null
                        connectNow()
                    }
                },
                delayMs,
            )
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            phase = Phase.AUTHENTICATING
            val c = creds ?: return
            val init = JSONObject().apply {
                put("type", "auth_init")
                put("role", "terminal")
                put("device_sid", c.deviceSid)
                put(
                    "meta",
                    JSONObject().put("platform", "web").put("version", "web").put("name", "mobile-browser"),
                )
                put("client_ts", System.currentTimeMillis())
            }
            webSocket.send(init.toString())
            Diagnostics.log("warn", "Tier2: 已连接，auth_init 已发（等待质询）")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            when (frame.optString("type")) {
                "auth_challenge" -> {
                    val c = creds ?: return
                    val nonce = frame.optString("nonce")
                    val proof = relayProof(c.passHash, nonce, "terminal", c.deviceSid)
                    val response = JSONObject().apply {
                        put("type", "auth_response")
                        put("device_sid", c.deviceSid)
                        put("proof", proof)
                        put("client_ts", System.currentTimeMillis())
                    }
                    webSocket.send(response.toString())
                    Diagnostics.log("warn", "Tier2: 质询已应答（HMAC proof），等待配对判定")
                }
                "auth_ack", "pair_status_ack" -> {
                    val status = frame.optString("pair_status", "unknown")
                    ackCount += 1
                    if (phase != Phase.PAIRED && status == "matched") {
                        phase = Phase.PAIRED
                        if (persistent) {
                            Diagnostics.log(
                                "warn",
                                "Tier2: ★接管配对成功（matched）——页面连接已被顶掉，开始桥覆盖",
                            )
                            startCoverageIfPersistent()
                        } else {
                            Diagnostics.log(
                                "warn",
                                "Tier2: ★配对成功（matched）——现在观察页面连接是否被踢（KICK/takeover 语义定案点）",
                            )
                        }
                    }
                }
                "data" -> {
                    // 业务帧路由（M3b）：rpc-frame / 桥开启应答 / 工作区列表应答。
                    val payload = frame.optJSONObject("payload") ?: return
                    bridgeManager?.acceptRelayPayload(payload)
                }
                "error" -> {
                    Diagnostics.log(
                        "warn",
                        "Tier2: 服务端 error code=${frame.optString("code")} " +
                            "msg=${frame.optString("message").take(80)}",
                    )
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (stopping || phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 连接失败 ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stopping || phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 对端关闭 code=$code reason=${reason.take(60)}")
            scheduleReconnect()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // relay 终端协议是 JSON 文本帧；二进制帧出现即记录不处理。
            Diagnostics.log("debug", "Tier2: 收到二进制帧 ${bytes.size}B（忽略）")
        }
    }

    private fun sendBusinessPayload(payload: JSONObject, quiet: Boolean) {
        val s = socket ?: run {
            if (!quiet) Diagnostics.log("warn", "Tier2: 无 socket，业务帧丢弃 ${payload.optString("zcode_type")}")
            return
        }
        val envelope = JSONObject()
            .put("type", "data")
            .put("payload", payload)
            .put("client_ts", System.currentTimeMillis())
        try {
            s.send(envelope.toString())
        } catch (e: Exception) {
            Diagnostics.log("warn", "Tier2: 业务帧发送失败 ${e.message}")
        }
    }

    private fun sendHeartbeat() {
        val s = socket ?: return
        val c = creds ?: return
        if (phase != Phase.PAIRED && phase != Phase.AUTHENTICATING) return
        heartbeatCount += 1
        val q = JSONObject().apply {
            put("type", "pair_status_query")
            put("device_sid", c.deviceSid)
            put("client_ts", System.currentTimeMillis())
        }
        try {
            s.send(q.toString())
        } catch (e: Exception) {
            Diagnostics.log("warn", "Tier2: 心跳发送失败 ${e.message}")
        }
    }
}
