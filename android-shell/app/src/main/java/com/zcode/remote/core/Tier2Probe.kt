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

    /**
     * 一次覆盖最多开几座桥。
     *
     * **这是成本取舍，不是平台/协议限制**（2026-09-17 定案并固化）：
     *  - 每座桥 = 桌面端**一条常驻会话**，且每轮轮换要**回收 + 重新握手 + 重订**一次；
     *  - 所以这个数直接乘上"每轮一次的握手成本"；
     *  - **轮换本身已经与 N 和 M 都无关**（[com.zcode.remote.core.BridgeManager.reanchorProgress]
     *    每轮只对"被服务的那座"发 resync、而那座也只 resync 自己**最饿的一条会话**，
     *    只轮换最饿的一座），因此抬高这个数**不会**让轮换互相冲突——它只决定"最多同时
     *    照顾几个工作区"。
     *
     * 3 → **5**（2026-09-17）：为了在鸿蒙侧做 **5 并发**可行性实验（用户拍板，只做理论验证）。
     * **产品的真实天花板是 2**：ColorOS 只并发提升 **2 张**流体云卡
     * （`PromotionPolicy.MAX_PROMOTED`），第 3 个及以后的任务只在常驻通知里。
     * 协议与桌面端侧**没有**明文上限；真机实测过的最大并发是 2 座（158）。
     *
     * ⚠️ **单源**（2026-09-17）：`ShellRuntime.MULTI_WS_COVERAGE_CAP` **直接引用本常量**，
     * 不再各写一个 5——改这里一处就够，两处不同步会直接编译不过。
     */
    internal const val DEFAULT_MAX_COVERAGE = 5

    /** 同一工作区连续开桥失败几次后进入冷却。 */
    private const val BRIDGE_FAIL_COOLDOWN_AFTER = 2

    /** 冷却时长：别每轮重连都去撞同一个必失败的桥。 */
    private const val BRIDGE_FAIL_COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * 覆盖刷新的周期（[startCoverageRefresh]）：60s 够快（新任务最多一分钟就被覆盖），
     * 又远慢于 12s 的轮换拍，不会和轮换抢握手。
     */
    private const val COVERAGE_REFRESH_MS = 60_000L

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
    @Volatile
    private var stopping = false
    private var reconnectAttempt = 0
    private var durationTimer: java.util.Timer? = null

    /** 页面正在显示的工作区（view-state 帧用，也是默认的覆盖目标）；空串=未知。 */
    @Volatile
    private var onlyWorkspace: String = ""

    /**
     * **显式覆盖目标**（多工作区）。非空时只用它，空=退回"只开页面当前工作区"。
     *
     * 内容由 [ShellRuntime] 决定（多工作区开关打开时＝page 工作区 ∪ 有在跑任务的工作区，
     * 上限 [DEFAULT_MAX_COVERAGE]），或由诊断指令 `coverage_ws:<k1>,<k2>` 直接指定（E1 用）。
     *
     * **失败冷却**：只有 `coverage_ws:` 诊断清单（[coverageIsDiagnostic]）豁免——实验要能
     * 看到失败本身；生产路径（多工作区开关 + 发现链）**照旧受冷却**，否则每轮重连都会去撞
     * 同一座必失败的桥。2026-09-17 之前这段注释说的是"显式覆盖都不受冷却"，与代码不符。
     */
    @Volatile
    private var coverageWorkspaces: List<String> = emptyList()

    /** 开桥失败计数 / 冷却截止（毫秒，`SystemClock` 不可用，这里用墙钟）。 */
    private val bridgeFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val bridgeCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 发现链落点：`bootstrap-response.result.tasks` 里"有活动任务"的工作区键。
     * 与 [BridgeManager.discoveredActiveWorkspaces]（workspace-list 那条）一起供
     * [discoveredCoverage] 使用——**这两条都是我们在收的响应，零新增订阅**。
     */
    @Volatile
    private var bootstrapActiveWorkspaces: List<String> = emptyList()

    /** `coverage_ws:` 诊断清单＝只用它（E1 语义），不并入发现链。 */
    @Volatile
    private var coverageIsDiagnostic = false

    /**
     * 定向接管的覆盖来源：从**已经在收的响应**里解析出来的"有活动任务的工作区"。
     *
     * 它解决的是老缺口——壳只认识"页面打开过的工作区"（`pageWorkspaceKey` 来自页面上报），
     * 所以多工作区覆盖以前要求用户先把每个工作区在手机上打开一遍。现在承载一动就能自己知道。
     * 顺序：bootstrap（必填源）优先，其次 workspace-list（可选源）。
     */
    fun discoveredCoverage(): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(bootstrapActiveWorkspaces)
        out.addAll(bridgeManager?.discoveredActiveWorkspaces.orEmpty())
        return out.toList()
    }

    /** 页面正在显示的任务（view-state 帧里要用；空串=未知）。 */
    @Volatile
    private var onlyTaskId: String = ""

    /**
     * 覆盖刷新定时器（见 [startCoverageRefresh]）：承载期间定期补开"新出现的活动工作区"的桥。
     */
    private var coverageTimer: java.util.Timer? = null

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

    /** 探针是否持有（或正在建立）连接——接管触发与前台交还都看它。 */
    fun isRunning(): Boolean = phase != Phase.IDLE && phase != Phase.CLOSED

    /**
     * M4：对话进展的出口（ShellRuntime 注入：工作区键、会话 id、文本）。
     * 接管开始时挂上，桥把"最新一行"解出来就回调到这里。
     */
    @Volatile
    var progressSink: ((workspaceKey: String, sessionId: String, text: String) -> Unit)? = null

    /**
     * 运行态出口（controller/tasks-index 的整表投影，见 [ControllerTasksState]）。
     * 与 [progressSink] 同时挂上：运行态决定"哪些任务值得跟踪"，进展决定"卡片正文"。
     */
    @Volatile
    var liveTaskSink: ((List<ControllerTasksState.LiveTask>) -> Unit)? = null

    /**
     * 会话流运行态出口（兜底）：controller 流拿不到时，用会话尾窗的
     * `turnHeader.state` 判"在跑"。真机实测 controller 订阅会超时（那条流
     * 看来由桌面窗口进程提供，而接管正好顶掉页面），没有这条兜底，
     * 后台卡片就会冻在接管那一刻。
     */
    @Volatile
    var turnStateSink: ((workspaceKey: String, sessionId: String, running: Boolean) -> Unit)? = null

    /**
     * M4：某工作区当前在跑的会话（ShellRuntime 注入）。握手时用它把对话订阅
     * **抢在索引订阅之前**发出去——顺序错了桌面端就永远不回包（见 runHandshake）。
     */
    @Volatile
    var runningSessionsProvider: ((workspaceKey: String) -> List<String>)? = null

    /**
     * M4：订阅某任务的对话详情。未接管 / 该工作区没有桥时静默无操作
     * （调用方下一轮再试）。阻塞，调用方自己保证在后台线程。
     */
    fun subscribeProgress(workspaceKey: String, sessionId: String) {
        val manager = bridgeManager ?: return
        try {
            manager.subscribeProgress(workspaceKey, sessionId)
        } catch (e: Exception) {
            Diagnostics.log("debug", "Tier2: 订阅对话详情失败（$workspaceKey）: ${e.message}")
        }
    }

    /** 周期重挂：逼桌面端再推一份对话快照（推送稀疏时的保险）。 */
    fun reanchorProgress() {
        val manager = bridgeManager ?: return
        try {
            manager.reanchorProgress()
        } catch (e: Exception) {
            Diagnostics.log("debug", "Tier2: 重挂对话订阅失败: ${e.message}")
        }
    }

    /**
     * @param durationMs 探针存活时长；<=0 表示接管模式（持久，直到 [stop]，
     *   且配对成功后启动桥覆盖 + 断线自动重连）。
     * @param onlyWorkspace 页面**自己**正在显示的工作区键：view-state 帧用它，也是
     *   没有 [coverageWorkspaces] 时的唯一覆盖目标。空串表示注入层还没上报。
     * @param coverageWorkspaces 多工作区覆盖清单（非空时只用它，顺序即开桥顺序）。
     * @param coverageIsDiagnostic `coverage_ws:` 给的清单＝只用它（E1 实验语义），
     *   并且**豁免开桥失败冷却**（实验要能看到失败本身）；false（承载生产路径）时会在
     *   **配对拿到 bootstrap 响应之后**并入"发现链"给出的活动工作区，且照旧受冷却。
     */
    fun start(
        newCreds: RelayCreds,
        durationMs: Long = 60_000L,
        onlyWorkspace: String = "",
        onlyTaskId: String = "",
        coverageWorkspaces: List<String> = emptyList(),
        coverageIsDiagnostic: Boolean = false,
    ) {
        startInternal(
            newCreds,
            durationMs,
            takeoverOverride = null,
            onlyWorkspace = onlyWorkspace,
            onlyTaskId = onlyTaskId,
            coverageWorkspaces = coverageWorkspaces,
            coverageIsDiagnostic = coverageIsDiagnostic,
        )
    }

    /**
     * 真机验证用：接管语义（开覆盖 + 重连）跑固定时长后自动交还，
     * 让 `tier2_takeover` 诊断指令能在不依赖"后台判死"的情况下验证 M3b/c。
     */
    fun startTakeoverForTest(newCreds: RelayCreds, autoStopMs: Long) {
        startInternal(
            newCreds,
            durationMs = -1L,
            takeoverOverride = autoStopMs,
            onlyWorkspace = "",
            onlyTaskId = "",
        )
    }

    private fun startInternal(
        newCreds: RelayCreds,
        durationMs: Long,
        takeoverOverride: Long?,
        onlyWorkspace: String = "",
        onlyTaskId: String = "",
        coverageWorkspaces: List<String> = emptyList(),
        coverageIsDiagnostic: Boolean = false,
    ) {
        if (isRunning()) {
            Diagnostics.log("warn", "Tier2: 已在运行（phase=$phase），忽略重复启动")
            return
        }
        creds = newCreds
        this.onlyWorkspace = onlyWorkspace
        this.onlyTaskId = onlyTaskId
        this.coverageWorkspaces = coverageWorkspaces
        this.coverageIsDiagnostic = coverageIsDiagnostic
        bridgeFailCount.clear()
        bridgeCooldownUntil.clear()
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
        if (takeoverOverride != null) {
            durationTimer = java.util.Timer(true).apply {
                schedule(
                    object : java.util.TimerTask() {
                        override fun run() = stop("接管验证到点")
                    },
                    takeoverOverride,
                )
            }
        } else if (durationMs > 0) {
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

    private fun isCurrent(webSocket: WebSocket): Boolean =
        !stopping && phase != Phase.CLOSED && socket === webSocket

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
        phase = Phase.CLOSED
        durationTimer?.cancel()
        durationTimer = null
        stopCoverageRefresh()
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        reconnectTimer?.cancel()
        reconnectTimer = null
        val manager = bridgeManager
        bridgeManager = null
        val s = socket
        socket = null
        val lasted = (System.currentTimeMillis() - startedAtMs) / 1000
        Diagnostics.log(
            "warn",
            "Tier2: 关闭（$reason）存活=${lasted}s 心跳=$heartbeatCount ack=$ackCount",
        )
        try {
            s?.close(1000, "tier2 done")
        } catch (e: Exception) {
            Diagnostics.log("warn", "Tier2: close 异常 ${e.message}")
        }
        if (manager != null) {
            Thread {
                try {
                    manager.disposeEverything(reason)
                } catch (e: Exception) {
                    Diagnostics.log("debug", "Tier2: 后台清理桥失败 ${e.message}")
                }
            }.start()
        }
    }

    /**
     * 配对之后的**第一帧业务数据**：`bootstrap-request`，然后才开桥。
     *
     * 这是 2026-09-16 01:12 用 CDP 抓页面自己的那一轮接入录下来的逐帧顺序
     * （`tools/cdp-capture.mjs`）：
     * ```
     * auth_init → auth_challenge → auth_response → auth_ack(pair_status=matched)
     * → {zcode_type:'bootstrap-request'}        ← 响应约 24 KB
     * → {zcode_type:'workspace-bridge-open'}    ← 响应约 0.5 KB
     * → rpc-frame / rpc-frame-ack …
     * ```
     * **我们此前整条链路都跳过了 bootstrap**（原生 `BridgeManager` 与注入层
     * `zcode-protocol.js` 都直接从 `workspace-list-request` 开始）。后果不是"少拿一份数据"，
     * 而是桌面端**不认这次接入**：干净 A/B 显示原生裸配对之后 4.3 秒桌面端就
     * `[task-realtime] unregistered host` + `host process (local-1) exited with code 1`
     * （docs/16 §8）——bootstrap 很可能就是"把这次终端登记成移动连接/窗口归属"的那一步。
     *
     * 顺序照页面来：先发 bootstrap，给它 1.2s 收响应（真机实测 300ms 内就回，24KB），
     * 再开桥覆盖。
     */
    private fun sendBootstrapThenCoverage() {
        if (stopping || phase == Phase.CLOSED || bridgeManager != null) return
        // ① 页面配对后**立刻**发的两帧（2026-09-16 01:12 抓包逐帧录下来的顺序）：
        //    mobile-diagnostic(state-transition: paired) 与 mobile-view-state-update。
        //    后者带着 `viewState.activeWorkspaceKey/activeTaskId`——**这很可能就是桌面端
        //    给新终端绑定窗口的依据**：真机 A/B 显示，裸配对（不发这两帧）之后 4.3 秒
        //    桌面端就 `unregistered host`（docs/16 §8/§10），而页面自己的重连从不触发它。
        val now = System.currentTimeMillis()
        sendBusinessPayload(
            JSONObject()
                .put("zcode_type", "mobile-diagnostic")
                .put("event", "state-transition")
                .put("timestamp", now)
                .put("state", "paired")
                .put("previousState", "authenticating")
                .put("visibilityState", "hidden")
                .put("online", true),
            quiet = false,
        )
        if (onlyWorkspace.isNotEmpty()) {
            val viewState = JSONObject()
                .put("activeWorkspaceKey", onlyWorkspace)
                .put("updatedAt", now)
            if (onlyTaskId.isNotEmpty()) {
                viewState.put("activeTaskId", onlyTaskId)
            }
            sendBusinessPayload(
                JSONObject()
                    .put("zcode_type", "mobile-view-state-update")
                    .put("viewState", viewState)
                    .put(
                        "deviceInfo",
                        JSONObject()
                            .put("platform", "web")
                            .put("version", "web")
                            .put("name", "mobile-browser")
                            .put("language", "zh-CN")
                            .put("timeZone", java.util.TimeZone.getDefault().id),
                    ),
                quiet = false,
            )
            Diagnostics.log(
                "info",
                "Tier2: 已发 mobile-view-state-update（工作区 $onlyWorkspace" +
                    (if (onlyTaskId.isEmpty()) "" else " · 任务 $onlyTaskId") + "）",
            )
        }
        // ② 然后是 bootstrap-request（页面顺序：diagnostic → bootstrap → bridge-open）。
        sendBusinessPayload(
            JSONObject()
                .put("zcode_type", "bootstrap-request")
                .put("requestId", "zcshell-bootstrap-" + java.util.UUID.randomUUID()),
            quiet = false,
        )
        Diagnostics.log("warn", "Tier2: 已发 bootstrap-request（补上页面配对后的第一步，1.2s 后开桥）")
        java.util.Timer(true).schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    startCoverage()
                }
            },
            1200L,
        )
    }

    private fun startCoverage() {
        if (stopping || phase == Phase.CLOSED || bridgeManager != null) return
        creds ?: return
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
            progressSink = { key, sessionId, text ->
                try {
                    progressSink?.invoke(key, sessionId, text)
                } catch (e: Exception) {
                    Diagnostics.log("warn", "Tier2: 活进展回调失败 ${e.message}")
                }
            },
            runningSessions = { key -> runningSessionsProvider?.invoke(key).orEmpty() },
            liveTaskSink = { tasks ->
                try {
                    liveTaskSink?.invoke(tasks)
                } catch (e: Exception) {
                    Diagnostics.log("warn", "Tier2: 运行态回调失败 ${e.message}")
                }
            },
            turnStateSink = { key, sessionId, running ->
                try {
                    turnStateSink?.invoke(key, sessionId, running)
                } catch (e: Exception) {
                    Diagnostics.log("warn", "Tier2: 会话运行态回调失败 ${e.message}")
                }
            },
            onBridgeResult = { key, ok -> recordBridgeResult(key, ok) },
            maxWorkspaces = DEFAULT_MAX_COVERAGE,
        )
        bridgeManager = manager
        Thread {
            try {
                if (stopping || phase == Phase.CLOSED || bridgeManager !== manager) return@Thread
                val workspaces = manager.listWorkspacesBlocking()
                if (stopping || phase == Phase.CLOSED || bridgeManager !== manager) return@Thread
                if (workspaces.isEmpty()) {
                    Diagnostics.log("warn", "Tier2: 接管模式拿到空工作区列表")
                    return@Thread
                }
                // **有在跑任务的工作区排最前**：真机 2026-09-15 定案——桌面端对
                // "页面正在看的那个窗口工作区"只在我们刚把页面顶掉后的很短一段时间里
                // 肯开桥（11:52 那次 6 s 内成功；等循环走到它时已过 16 s，就变成
                // `desktop-disconnected: 未找到桌面窗口 host process`）。
                // 所以别按列表顺序慢慢开，先把要跟踪的那个抢下来。
                val ordered = workspaces.sortedByDescending { workspace ->
                    val key = workspaceKeyOf(workspace)
                    if (key == null) 0 else runningSessionsProvider?.invoke(key).orEmpty().size
                }
                // 覆盖目标三档（2026-09-16 第二版：多工作区）：
                //   ① **显式清单**（多工作区开关打开 / `coverage_ws:` 诊断）——只用它，按给定顺序；
                //      `coverage_ws:` 诊断清单**不受失败冷却影响**（实验要能看到失败），
                //      生产路径照旧受冷却（见下面 `live` 的取法）；
                //   ② **页面工作区**（默认）——与页面自己的行为一致，是经过真机验收的生产路径；
                //   ③ 都没有 ——按"在跑任务数"排序取前 [DEFAULT_MAX_COVERAGE] 个。
                //      **不再全量**：扫全部工作区曾被认为是桌面端拆 host 的诱因，虽然后来查明真凶是
                //      controller 流那次 `rpc:listen`（docs/16 §8/§10），但没有理由为此开满 12 座桥。
                // **发现链要在这一刻读**（不是承载启动前）：数据来自配对之后的
                // `bootstrap-response.result.tasks`，承载启动时它还没到（154 实测踩到：
                // 清单在 `maybeStartNativeCarrier` 里算，日志显示"发现链 0 个"）。
                // 只在多工作区开关打开时并入（开关关＝只开页面工作区，语义不变）；
                // `coverage_ws:` 诊断清单不并入（E1 要的是"只用它"）。
                val discovered =
                    if (coverageIsDiagnostic || coverageWorkspaces.isEmpty()) {
                        emptyList()
                    } else {
                        discoveredCoverage()
                    }
                if (discovered.isNotEmpty()) {
                    Diagnostics.log(
                        "info",
                        "Tier2: 发现链给出 ${discovered.size} 个有活动任务的工作区：${discovered.joinToString()}",
                    )
                }
                // **截断要在进"覆盖清单"这一档之前做**（2026-09-17，鸿蒙侧审计 4 号）：
                // 清单 = 显式清单 + 发现链，条数可以超过 [DEFAULT_MAX_COVERAGE]，而
                // `beginCoverage` 只会开前 maxWorkspaces 座——不截断就会打出"多工作区覆盖 7 座"
                // 而实际只开 5 座的假账（日志与现场对不上，是最贵的一类误导）。
                // 取法：**显式清单/发现链优先**（保持它们给出的顺序），条数不够时才轮到
                // ③ 的"其余按在跑任务数排序"（`ordered` 已经按在跑任务数降序）。
                val wanted = (coverageWorkspaces + discovered).distinct()
                val explicit = wanted.take(DEFAULT_MAX_COVERAGE)
                if (explicit.size < wanted.size) {
                    Diagnostics.log(
                        "info",
                        "Tier2: 覆盖清单 ${wanted.size} 座超过上限 $DEFAULT_MAX_COVERAGE" +
                            "（显式清单/发现链优先、其余按在跑任务数排序），取前 $DEFAULT_MAX_COVERAGE：" +
                            explicit.joinToString(),
                    )
                }
                val only = onlyWorkspace
                val targets = when {
                    explicit.isNotEmpty() -> {
                        val wantedKeys = explicit.toSet()
                        val hit = ordered.filter {
                            val k = workspaceKeyOf(it)
                            k != null && k in wantedKeys
                        }
                        // 冷却的适用范围**按来源分**（2026-09-17 与注释对齐）：
                        //   * `coverage_ws:` 诊断清单 → **豁免**：E1 实验的目的就是"看到失败"，
                        //     上一轮失败把它冷却掉，实验就再也看不到同一座桥的失败现场了；
                        //   * 生产路径（多工作区开关 + 发现链）→ **照旧受冷却**：同一座桥连续
                        //     失败还每轮去撞，就是用户看到的"正在尝试重连"churn。
                        val cooledCount = hit.count { cooledDown(workspaceKeyOf(it)) }
                        val live = if (coverageIsDiagnostic) {
                            if (cooledCount > 0) {
                                Diagnostics.log(
                                    "info",
                                    "Tier2: 诊断覆盖清单豁免失败冷却——其中 $cooledCount 座在冷却中，本轮照开",
                                )
                            }
                            hit
                        } else {
                            if (cooledCount > 0) {
                                Diagnostics.log(
                                    "info",
                                    "Tier2: 覆盖清单里 $cooledCount 座在冷却中，本轮跳过",
                                )
                            }
                            hit.filterNot { cooledDown(workspaceKeyOf(it)) }
                        }
                        if (live.isEmpty()) {
                            Diagnostics.log(
                                "warn",
                                "Tier2: 覆盖清单（${explicit.joinToString()}）在桌面端列表里没有可用项，本轮不开桥",
                            )
                            emptyList()
                        } else {
                            Diagnostics.log(
                                "info",
                                "Tier2: 多工作区覆盖 ${live.size} 座（上限 $DEFAULT_MAX_COVERAGE）：" +
                                    live.mapNotNull { workspaceKeyOf(it) }.joinToString(),
                            )
                            live
                        }
                    }
                    only.isNotEmpty() -> {
                        val hit = ordered.filter { workspaceKeyOf(it) == only }
                        if (hit.isNotEmpty()) {
                            Diagnostics.log("info", "Tier2: 只开页面当前工作区的桥：$only")
                            hit
                        } else {
                            Diagnostics.log(
                                "warn",
                                "Tier2: 页面工作区 $only 不在桌面端列表里（${ordered.size} 个），" +
                                    "退回按在跑任务排序的前 $DEFAULT_MAX_COVERAGE 个",
                            )
                            ordered.take(DEFAULT_MAX_COVERAGE)
                        }
                    }
                    else -> {
                        Diagnostics.log(
                            "warn",
                            "Tier2: 注入层还没上报页面工作区，按在跑任务排序开前 $DEFAULT_MAX_COVERAGE 个",
                        )
                        ordered.take(DEFAULT_MAX_COVERAGE)
                    }
                }
                if (targets.isEmpty()) return@Thread
                manager.beginCoverage(targets)
                startCoverageRefresh(manager)
            } catch (e: Exception) {
                Diagnostics.log("warn", "Tier2: 覆盖启动失败 ${e.message}")
            }
        }.start()
    }

    /**
     * 覆盖刷新（2026-09-18 加）：承载期间每 [COVERAGE_REFRESH_MS] 重问一次工作区列表，
     * 把**新出现的"有活动任务的工作区"**补进覆盖。
     *
     * 为什么需要它：页面被顶掉之后，第三条源（页面自己的 controller 流）就停了
     * （`docs/18` §3.11 B），而发现链只在**配对那一刻**跑一次（`bootstrap-response` +
     * `workspace-list-response`）。于是承载期间你在**没被覆盖的工作区**里新起任务，
     * 壳是不知道的——卡片不会出现，直到下一次接管重来一遍。真机把这个边界诊断出来之后
     * 用户拍板补上（`docs/18` §3.11 F 第 1 条）。
     *
     * 三条纪律：① 只**补**不拆（覆盖一旦建立就交给轮换去管），② 受**失败冷却**约束
     * （同一座桥连续被拒就别每 60s 再撞一次），③ 受**总上限**约束
     * （[BridgeManager.remainingCoverageSlots]，别让定时刷新把桥数顶过 5）。
     */
    private fun startCoverageRefresh(manager: BridgeManager) {
        stopCoverageRefresh()
        val timer = java.util.Timer("tier2-coverage-refresh", true)
        coverageTimer = timer
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    if (stopping || phase == Phase.CLOSED || bridgeManager !== manager) return
                    try {
                        val workspaces = manager.listWorkspacesBlocking()
                        if (stopping || phase == Phase.CLOSED || bridgeManager !== manager) return
                        val covered = manager.coveredKeys()
                        val discovered = manager.discoveredActiveWorkspaces
                        val missing = discovered
                            .filter { it.isNotEmpty() && it !in covered && !cooledDown(it) }
                        // 每拍一行 debug：这条机制在"没有缺口"时是完全静默的，
                        // 而"它到底有没有在跑"必须能从日志里回答（60s 一行，不吵）。
                        Diagnostics.log(
                            "debug",
                            "Tier2: 覆盖刷新：已覆盖 ${covered.size} 座 · 发现链 ${discovered.size} 座 · " +
                                "待补 ${missing.size} 座",
                        )
                        if (missing.isEmpty()) return
                        val slots = manager.remainingCoverageSlots()
                        if (slots <= 0) {
                            Diagnostics.log(
                                "debug",
                                "Tier2: 覆盖刷新——${missing.size} 座有活动任务的工作区没被覆盖，" +
                                    "但桥数已到上限 $DEFAULT_MAX_COVERAGE",
                            )
                            return
                        }
                        val byKey = HashMap<String, JSONObject>()
                        for (workspace in workspaces) {
                            val key = workspaceKeyOf(workspace) ?: continue
                            byKey[key] = workspace
                        }
                        val targets = missing.mapNotNull { byKey[it] }.take(slots)
                        if (targets.isEmpty()) {
                            Diagnostics.log(
                                "info",
                                "Tier2: 覆盖刷新——发现 ${missing.size} 座有活动任务的工作区" +
                                    "（${missing.joinToString()}），但桌面端列表里没有可用项",
                            )
                            return
                        }
                        Diagnostics.log(
                            "info",
                            "Tier2: 覆盖刷新——新发现 ${targets.size} 座有活动任务的工作区，" +
                                "补开桥（已覆盖 ${covered.size} 座 · 上限 $DEFAULT_MAX_COVERAGE）：" +
                                targets.mapNotNull { workspaceKeyOf(it) }.joinToString(),
                        )
                        manager.beginCoverage(targets)
                    } catch (e: Exception) {
                        Diagnostics.log("debug", "Tier2: 覆盖刷新失败 ${e.message}")
                    }
                }
            },
            COVERAGE_REFRESH_MS,
            COVERAGE_REFRESH_MS,
        )
    }

    private fun stopCoverageRefresh() {
        coverageTimer?.cancel()
        coverageTimer = null
    }

    /** 该工作区是否在"连续开桥失败"的冷却里（冷却到期自动解除）。 */
    private fun cooledDown(key: String?): Boolean {
        if (key == null) return false
        val until = bridgeCooldownUntil[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            bridgeCooldownUntil.remove(key)
            bridgeFailCount.remove(key)
            return false
        }
        return true
    }

    /** 桥开启结果：成功清账；连续失败到阈值则冷却，避免每轮重连都撞同一座必失败的桥。 */
    private fun recordBridgeResult(key: String, ok: Boolean) {
        if (ok) {
            bridgeFailCount.remove(key)
            bridgeCooldownUntil.remove(key)
            return
        }
        val n = (bridgeFailCount[key] ?: 0) + 1
        bridgeFailCount[key] = n
        if (n >= BRIDGE_FAIL_COOLDOWN_AFTER) {
            bridgeCooldownUntil[key] = System.currentTimeMillis() + BRIDGE_FAIL_COOLDOWN_MS
            Diagnostics.log(
                "warn",
                "Tier2: 工作区 $key 连续 $n 次开桥失败，冷却 ${BRIDGE_FAIL_COOLDOWN_MS / 60_000} 分钟",
            )
        }
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
            if (!isCurrent(webSocket)) return
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
            if (!isCurrent(webSocket)) return
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
                        } else {
                            Diagnostics.log(
                                "warn",
                                "Tier2: ★配对成功（matched）——实验模式，同时开桥覆盖（验证 M3b/c 解码）",
                            )
                        }
                        sendBootstrapThenCoverage()
                    }
                }
                "data" -> {
                    // 业务帧路由（M3b）：rpc-frame / 桥开启应答 / 工作区列表应答。
                    val payload = frame.optJSONObject("payload") ?: return
                    val kind = payload.optString("zcode_type")
                    if (kind.startsWith("bootstrap")) {
                        // 只记大小与类型：bootstrap 响应可能带凭证，**原文绝不落日志**。
                        Diagnostics.log(
                            "info",
                            "Tier2: 收到 $kind（${text.length} 字符）",
                        )
                        if (kind == "bootstrap-response") {
                            // 定向接管的发现链（主源）：这条响应里 `result.tasks` 是 schema **必填**，
                            // 每次 24–25 KB，之前只记了大小没解析。摘要只落"状态 + 工作区键"。
                            val result = payload.optJSONObject("result")
                            Diagnostics.log("info", RelayTaskDigest.describe(result, "bootstrap"))
                            Diagnostics.log("info", RelayTaskDigest.shapeOf(result))
                            bootstrapActiveWorkspaces = RelayTaskDigest.activeWorkspaces(result)
                        }
                    }
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
            if (!isCurrent(webSocket)) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 连接失败 ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            phase = Phase.CLOSED
            Diagnostics.log("warn", "Tier2: 对端关闭 code=$code reason=${reason.take(60)}")
            scheduleReconnect()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket)) return
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
