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

/** Tier2 探针的连接凭证。只驻内存；任何日志路径都不得打印这些字段。 */
data class RelayCreds(
    val wsUrl: String,
    val deviceSid: String,
    val passHash: String,
    val deviceMid: String?,
)

/**
 * Tier2 probe — 原生直连 relay 的最小客户端，完全不经过 WebView/renderer。
 *
 * 目的（2026-09-13 拍板）：验证「第二条完成配对的连接」对页面已配对连接的
 * KICK/takeover 语义——kick_test 已证实未配对的第二条不会踢人，配对后的
 * 行为是后台接管（Tier2）能否成立的决定性未知量。
 *
 * 握手链与页面逐字对齐（docs/05 @4699005/@4702045）：
 *   connect → auth_init{role:'terminal', device_sid, meta, client_ts}
 *   ← auth_challenge{nonce}
 *   → auth_response{device_sid, proof, client_ts}
 *   ← auth_ack{pair_status} → paired；此后每 10s pair_status_query 保活。
 *
 * 生命周期由诊断指令驱动（tier2_test / tier2_stop）：durationMs 到点主动关闭。
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

    private var heartbeatCount = 0
    private var ackCount = 0
    private var startedAtMs = 0L
    private var durationTimer: java.util.Timer? = null
    private var heartbeatTimer: java.util.Timer? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val currentPhase: Phase get() = phase

    fun start(newCreds: RelayCreds, durationMs: Long = 60_000) {
        if (phase != Phase.IDLE && phase != Phase.CLOSED) {
            Diagnostics.log("warn", "Tier2: 已在运行（phase=$phase），先 tier2_stop 再重试")
            return
        }
        creds = newCreds
        heartbeatCount = 0
        ackCount = 0
        startedAtMs = System.currentTimeMillis()
        phase = Phase.CONNECTING
        Diagnostics.log("info", "Tier2: 启动原生直连探针（duration=${durationMs / 1000}s，凭证仅内存）")

        val url = buildString {
            append(newCreds.wsUrl)
            if (!newCreds.deviceMid.isNullOrBlank()) {
                append(if (newCreds.wsUrl.contains('?')) "&" else "?")
                append("mid=").append(java.net.URLEncoder.encode(newCreds.deviceMid, "UTF-8"))
            }
        }
        val request = Request.Builder().url(url).build()

        // 到点主动关闭：实验探针不常驻，常驻形态等语义定案后再设计。
        durationTimer = java.util.Timer(true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() = stop("duration 到点")
                },
                durationMs,
            )
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

        socket = client.newWebSocket(request, listener)
    }

    fun stop(reason: String) {
        durationTimer?.cancel()
        durationTimer = null
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        val s = socket
        socket = null
        val lasted = (System.currentTimeMillis() - startedAtMs) / 1000
        Diagnostics.log(
            "warn",
            "Tier2: 关闭（$reason）phase=$phase 存活=${lasted}s 心跳=$heartbeatCount ack=$ackCount",
        )
        try {
            s?.close(1000, "tier2 probe done")
        } catch (e: Exception) {
            Diagnostics.log("warn", "Tier2: close 异常 ${e.message}")
        }
        phase = Phase.CLOSED
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            phase = Phase.AUTHENTICATING
            val c = creds ?: return
            // auth_init 与页面同构（meta 也保持一致，服务端可能按它区分终端形态）。
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
                        // 关键观测点：本端配对成功后，页面那条连接是否还活着——
                        // 页面侧的 socket close 会以「relay socket closed」出现在同一份日志里。
                        Diagnostics.log(
                            "warn",
                            "Tier2: ★配对成功（matched）——现在观察页面连接是否被踢（KICK/takeover 语义定案点）",
                        )
                    } else if (status != "matched") {
                        Diagnostics.log("info", "Tier2: pair_status=$status")
                    }
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
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 连接失败 ${t.javaClass.simpleName}: ${t.message?.take(120)}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 对端关闭 code=$code reason=${reason.take(60)}")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // relay 终端协议是 JSON 文本帧；二进制帧出现即记录不处理。
            Diagnostics.log("debug", "Tier2: 收到二进制帧 ${bytes.size}B（忽略）")
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
