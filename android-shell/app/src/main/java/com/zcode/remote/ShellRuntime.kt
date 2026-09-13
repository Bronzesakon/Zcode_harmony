package com.zcode.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.Prefs
import com.zcode.remote.core.RelayCreds
import com.zcode.remote.core.SurvivalVerdict
import com.zcode.remote.core.Tier2Probe
import com.zcode.remote.core.TaskSnapshot
import com.zcode.remote.core.TaskStatus
import com.zcode.remote.core.TaskStore
import com.zcode.remote.notify.Notifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide state for the shell: the task store, the notifier and the
 * foreground service that keeps the process (and therefore the WebView's relay
 * socket) alive while the app is backgrounded.
 *
 * A plain singleton rather than a bound service: there is exactly one Activity
 * and one WebView, so a Binder round trip would add nothing. Bridge callbacks
 * arrive on the WebView's JavaBridge thread, so the few things that must be
 * ordered against the main thread (throttled notification updates) go through a
 * main-thread Handler.
 */
object ShellRuntime {

    /** The reference client throttles ongoing updates to ~900 ms. */
    private const val ONGOING_THROTTLE_MS = 900L

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private lateinit var notifier: Notifier

    /**
     * Main-thread handler for everything this class schedules.
     *
     * Asynchronous on API 28+, because an ordinary message posted to the main
     * looper is NOT delivered while the window is invisible: the Choreographer's
     * sync barrier starves it (measured earlier on this device — a dispatch due
     * at +15s arrived 3m26s late, at the instant the app returned to the
     * foreground). The pump's own timer was made asynchronous for that reason;
     * this handler schedules the JS evaluation the pump triggers, so it is the
     * same hazard one hop later.
     *
     * Note on the evidence: a 2026-09-11 field round logged 39 pump dispatches
     * with a 0-tick verdict, which first looked like this starvation. It was not
     * — see `SurvivalVerdict`. The 15.00s cadence of the injected layer's own
     * perf reports proves the evaluation was running. This change is made on the
     * starvation risk above, not on that reading.
     */
    private val mainHandler: Handler =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Handler.createAsync(Looper.getMainLooper())
        } else {
            Handler(Looper.getMainLooper())
        }

    val store = TaskStore()

    // Coalescing state for ongoing notifications. Both fields are touched from
    // whichever thread the bridge callback arrives on, so they are guarded.
    private val lock = Any()
    private var pendingRemovedIds = LinkedHashSet<Int>()
    private var latestRunning: List<TaskStore.RunningNotification> = emptyList()
    private var pendingFlush: Runnable? = null
    private var lastPublishAt = 0L

    private var serviceRunning = false
    private var injectedReady = false
    private var reportedActive = false

    // ---------------------------------------------------------- liveness probe
    //
    // §8 step 4 of the migration doc is the milestone that decides whether this
    // whole approach stands: background the app for half an hour and check that
    // frames are still arriving. That test cannot be run without a device, so
    // rather than asserting it, the shell measures it and reports one verdict
    // line into the log. `pairAcks` is the decisive counter: the desktop answers
    // every heartbeat, so a non-zero count across a background window proves the
    // link is alive even when no task happened to be running — task deltas alone
    // are silent on a quiet desktop, which is how this test usually gets
    // misread as a failure.

    /** Liveness counters as reported by the injected script. */
    data class Liveness(
        val inboundFrames: Int,
        val pairAcks: Int,
        val socketsOpened: Int,
        val socketsClosed: Int,
        /**
         * Heartbeat ticks the injected layer ran while the app was backgrounded,
         * and how long after going background the first one came. The delay is
         * what makes the verdict decidable: a heartbeat that really runs in the
         * background starts ticking at once, whereas a link that only came back
         * with the foreground produces its "background" ticks in one burst at the
         * very end of the window — identical counters, opposite meaning.
         */
        val backgroundTicks: Int,
        val backgroundFirstTickDelayMs: Long,
        /** Uptime timestamp of the most recent inbound frame (0 = none yet). */
        val lastInboundAtElapsed: Long,
        val paired: Boolean,
        val socketState: Int,
    )

    @Volatile
    private var liveness: Liveness? = null

    private var backgroundStartedAt = 0L
    private var backgroundFrameBase = 0
    private var backgroundAckBase = 0
    private var backgroundTickBase = 0

    /** Set on return to foreground; the next liveness report resolves it. */
    @Volatile
    private var verdictPending = false

    fun livenessSnapshot(): Liveness? = liveness

    /** Age of the newest inbound frame, or null if nothing ever arrived. */
    fun lastInboundAgeMs(): Long? {
        val snapshot = liveness ?: return null
        if (snapshot.lastInboundAtElapsed <= 0) return null
        return SystemClock.elapsedRealtime() - snapshot.lastInboundAtElapsed
    }

    private fun onLiveness(data: JSONObject) {
        val receivedAt = SystemClock.elapsedRealtime()
        lastLivenessAt = receivedAt
        val ago = data.optLong("lastInboundAgoMs", -1)
        liveness = Liveness(
            inboundFrames = data.optInt("inboundFrames"),
            pairAcks = data.optInt("pairAcks"),
            socketsOpened = data.optInt("socketsOpened"),
            socketsClosed = data.optInt("socketsClosed"),
            backgroundTicks = data.optInt("backgroundTicks"),
            backgroundFirstTickDelayMs = data.optLong("backgroundFirstTickDelayMs", -1),
            lastInboundAtElapsed = if (ago >= 0) receivedAt - ago else 0,
            paired = data.optBoolean("paired"),
            socketState = data.optInt("socketState", -1),
        )
        maybeTakeOverInBackground(ago)
        // A background window just closed and this is the first fresh reading:
        // this is the milestone-4 verdict.
        if (verdictPending && backgroundStartedAt == 0L) {
            verdictPending = false
            val current = liveness ?: return
            val frames = current.inboundFrames - backgroundFrameBase
            val acks = current.pairAcks - backgroundAckBase
            // Delta against the base recorded when the window opened, exactly like
            // frames/acks. Reading the page's absolute counter was the bug: a
            // foreground flap at the moment of return zeroed it (see
            // SurvivalVerdict).
            val ticks = (current.backgroundTicks - backgroundTickBase).coerceAtLeast(0)
            val firstDelay = current.backgroundFirstTickDelayMs
            Diagnostics.info(
                SurvivalVerdict.describe(
                    duration = formatDuration(backgroundEndedAt - backgroundStartedForVerdict),
                    ticks = ticks,
                    firstTickDelayMs = firstDelay,
                    pumpDispatches = pumpDispatchesForVerdict,
                    frames = frames,
                    acks = acks,
                )
            )
        }
    }

    private var backgroundStartedForVerdict = 0L
    private var backgroundEndedAt = 0L

    /**
     * Records the background window and, on return, writes the milestone-4
     * verdict to the log. This is the line to read after leaving the app in the
     * background for 30 minutes.
     *
     * Also the switch for the heartbeat pump: the injected layer's own timer
     * cannot run while the app is backgrounded, so the heartbeat is driven from
     * here for exactly as long as the app is away.
     */
    fun onAppForegroundChanged(foreground: Boolean) {
        appIsForeground = foreground
        if (foreground) {
            // Tier2 交还：后台接管持有的配对必须先释放，页面自己的重连才可能
            // 成功；重连后 runtime 已死，由卡死看门狗的僵尸档走刷新恢复。
            if (Tier2Probe.isRunning()) {
                Tier2Probe.stop("回前台交还")
                Diagnostics.log("warn", "Tier2: 前台交还完成，页面将由重连+僵尸看门狗恢复")
            }
            stopHeartbeatPump()
            val startedAt = backgroundStartedAt
            val endedAt = SystemClock.elapsedRealtime()
            backgroundStartedAt = 0L
            if (startedAt <= 0) return
            if (endedAt - startedAt < VERDICT_MIN_BACKGROUND_MS) return
            backgroundStartedForVerdict = startedAt
            backgroundEndedAt = endedAt
            pumpDispatchesForVerdict = pumpDispatches
            // Resolved by the first fresh liveness report, so the numbers are
            // measured after the renderer resumed rather than cached before it
            // froze.
            verdictPending = true
            requestLivenessReport()
        } else {
            startHeartbeatPump()
            lastLivenessAt = SystemClock.elapsedRealtime()
            backgroundStartedAt = SystemClock.elapsedRealtime()
            val snapshot = liveness
            backgroundFrameBase = snapshot?.inboundFrames ?: 0
            backgroundAckBase = snapshot?.pairAcks ?: 0
            backgroundTickBase = snapshot?.backgroundTicks ?: 0
        }
    }

    // ------------------------------------------------------------ heartbeat pump
    //
    // The injected layer's own interval cannot be relied on while the app is
    // backgrounded: the renderer throttles a hidden page's timers, so the tick
    // stops and the desktop stops hearing from us. Driving the same tick from
    // here keeps the pairing warm.
    //
    // Open question, measured but not yet explained: the page's own relay client
    // reconnects on its own while backgrounded. Its ack watchdog is 30s and
    // `applyPairStatus` re-arms it on ANY `pair_status_ack` (no id correlation),
    // so the pump's probe *should* pin it — but a screen-off window still showed
    // the page calling `close()` every ~95–120s (`code=1005 clean=true`, from the
    // page's own bundle), while the pump was demonstrably ticking and acks were
    // arriving every ~14s. Do not assume the probe covers it.
    //
    // Two properties are load-bearing and easy to undo by simplifying:
    //
    //  * Both hops are asynchronous (see `mainHandler`): the pump's own timer AND
    //    the JS evaluation it triggers, since either sync hop is starved by the
    //    Choreographer's sync barrier while the window is invisible.
    //  * None of this helps if the platform gives the process no execution at
    //    all: with ColorOS's default battery policy the app is frozen/killed
    //    while backgrounded (o-kill/o-stop at importance=FOREGROUND_SERVICE, and
    //    a 15s dispatch still not delivered after 10 minutes). The per-app
    //    "allow full background behaviour" switch is what makes the pump real.

    /**
     * How often the pump drives one heartbeat tick.
     *
     * Ten seconds, matching the page's own `heartbeatIntervalMs` — the pump is
     * standing in for a timer the renderer no longer runs while hidden, so it has
     * to keep at least that cadence. Fifteen was not enough: the page's ack
     * watchdog is 30s, re-armed on every ack, and the field log showed single
     * cycles where our probe's ack did not land (windows with `链路 ack 0`). Two
     * of those in a row is a 30s ack gap, which is exactly what makes the watchdog
     * fire — and its callback closes the socket, which is the reconnect the user
     * sees. Cheap to be early: one more `evaluateJavascript` per 30s.
     */
    private const val PUMP_INTERVAL_MS = 10_000L

    /** Every Nth dispatch is logged; the first one always is. */
    private const val PUMP_LOG_EVERY = 20

    @Volatile
    private var appIsForeground = true

    private var pumpDispatches = 0
    private var pumpDispatchesForVerdict = 0

    private val pumpRunnable = object : Runnable {
        override fun run() {
            if (!appIsForeground) {
                if (jsEvaluator != null) {
                    pumpDispatches += 1
                    evaluateJs("window.__zcodeShellHeartbeat && window.__zcodeShellHeartbeat();")
                    if (pumpDispatches == 1 || pumpDispatches % PUMP_LOG_EVERY == 0) {
                        Diagnostics.log("debug", "后台心跳泵 #$pumpDispatches 次发令")
                    }
                }
                // 原生巡检：渲染器冻结时注入层的 liveness 会整段停摆，这里仍能判死并接管。
                checkTier1Silence()
                mainHandler.postDelayed(this, PUMP_INTERVAL_MS)
            }
        }
    }

    private fun startHeartbeatPump() {
        mainHandler.removeCallbacks(pumpRunnable)
        pumpDispatches = 0
        mainHandler.postDelayed(pumpRunnable, PUMP_INTERVAL_MS)
        Diagnostics.log(
            "debug",
            "后台心跳泵已启动（每 ${PUMP_INTERVAL_MS / 1000}s 驱动一次注入层心跳）",
        )
    }

    private fun stopHeartbeatPump() {
        mainHandler.removeCallbacks(pumpRunnable)
        if (pumpDispatches > 0) {
            Diagnostics.log("debug", "后台心跳泵已停止，本次共发出 $pumpDispatches 次")
        }
    }

    /**
     * Asks the injected layer to report its counters right now. [onLiveness] is
     * what actually writes the verdict, so this call is fire-and-forget.
     */
    fun requestLivenessReport() {
        evaluateJs("window.__zcodeShellReportLiveness && window.__zcodeShellReportLiveness();")
    }

    /**
     * Only one Activity owns the WebView, so it registers a tiny evaluator here
     * instead of the runtime holding a reference to a view (which would leak the
     * Activity across a configuration change).
     */
    @Volatile
    private var jsEvaluator: ((String) -> Unit)? = null

    fun setJsEvaluator(evaluator: ((String) -> Unit)?) {
        jsEvaluator = evaluator
    }

    /** Tier2 原生直连 relay 的凭证（注入层移交，仅内存，不落日志）。 */
    @Volatile
    private var relayCreds: RelayCreds? = null

    /**
     * Tier1 静默超过该时长即视为判死，Tier2 原生接管配对与任务事件。
     * 25s：用户要求"30 秒内必须接管"；正常链路的 liveness/心跳节拍是 10s，
     * 25s 容得下两次迟到，配合 5s 一跳的看门狗最坏 30s 内动手。
     */
    private const val TIER2_TAKEOVER_SILENCE_MS = 25_000L

    /**
     * 最后一次收到注入层 liveness 报告的墙钟时刻。
     *
     * 关键：接管判据不能只看 JS 上报的 lastInboundAgoMs——渲染器被冻结时
     * 连报告本身都停了（2026-09-13 真机：进程 FGS 存活、渲染器静默 30 分钟，
     * 注入层零上报，接管因此永远不会触发）。这里用"原生多久没收到任何
     * liveness"作为主判据，JS 的自述只作补充。
     */
    @Volatile
    private var lastLivenessAt = 0L

    /**
     * 用户是否已经离开（接管的前置条件）：应用在后台，**或屏幕已熄灭**。
     *
     * 屏幕熄灭这一条是 2026-09-13 真机补上的：应用名义上还在前台时原生泵不
     * 启动，而熄屏会让渲染器定时器整体挂起——注入层连"我停了"都报不出来，
     * 流体云随之定格（日志停在熄屏那一刻）。后台与熄屏必须同等对待。
     */
    private fun userIsAway(): Boolean {
        if (!appIsForeground) {
            return true
        }
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power == null) {
            return false
        }
        return !power.isInteractive
    }

    /**
     * 原生侧巡检 Tier1 静默（常驻看门狗，不依赖注入层上报）。
     * 判据：用户已离开 + 静默超过阈值 + 最后已知配对为 matched（桌面在线）。
     */
    private fun checkTier1Silence() {
        if (lastLivenessAt <= 0L) {
            return
        }
        if (!userIsAway()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val silenceByReport = now - lastLivenessAt
        var silenceByLink = Long.MAX_VALUE
        val snapshot = liveness
        if (snapshot != null && snapshot.lastInboundAtElapsed > 0) {
            silenceByLink = now - snapshot.lastInboundAtElapsed
        }
        var silence = silenceByReport
        if (silenceByLink < silence) {
            silence = silenceByLink
        }
        maybeTakeOverInBackground(silence)
    }

    /** Tier1 静默看门狗：每 15s 一跳，前台/后台/熄屏都在岗。 */
    private val tier1Watchdog = object : Runnable {
        override fun run() {
            try {
                checkTier1Silence()
            } catch (e: Exception) {
                Diagnostics.log("warn", "Tier1 看门狗异常: ${e.message}")
            }
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    /**
     * 诊断指令 tier1_silence_test：把 liveness 时间戳强制拨旧，验证"渲染器静默
     * → 原生判死 → Tier2 接管"这条路径（真机无法自然制造渲染器冻结）。
     */
    fun forceTier1SilenceCheckForTest() {
        lastLivenessAt = SystemClock.elapsedRealtime() - (TIER2_TAKEOVER_SILENCE_MS + 5_000L)
        Diagnostics.log("warn", "Tier1 静默测试：强制判死并立即巡检")
        checkTier1Silence()
    }

    fun startTier1Watchdog() {
        mainHandler.removeCallbacks(tier1Watchdog)
        mainHandler.postDelayed(tier1Watchdog, 5_000L)
    }

    /**
     * Tier2 后台接管触发（2026-09-13 拍板的形态）：应用在后台且 Tier1 的入站
     * 流静默超过阈值（renderer 冻结/链路死亡，实况窗会断）→ 原生直连 relay
     * 接管配对。KICK 语义已定案为配对互斥：接管会踢掉页面连接，所以前台绝不
     * 做这件事；回前台由 [onAppForegroundChanged] 交还。M3（原生任务事件解码）
     * 落地前，接管只保连接与配对，不产出通知数据。
     */
    private fun maybeTakeOverInBackground(inboundAgoMs: Long) {
        if (!userIsAway()) return
        if (inboundAgoMs in 0 until TIER2_TAKEOVER_SILENCE_MS) return
        if (Tier2Probe.isRunning()) return
        // 桌面活着才接管：最后一次 pair ack 非 matched（桌面休眠/离线）时，
        // 静默是"没有可监控的东西"而非"我们瞎了"——接管只会占坑挡页面恢复。
        val snapshot = liveness
        if (snapshot == null || !snapshot.paired) {
            Diagnostics.log("debug", "Tier2: 静默但桌面非在线（pair_status 非 matched），不接管")
            return
        }
        val c = relayCreds ?: return
        Tier2Probe.start(c, durationMs = 0L)
        Diagnostics.log(
            "warn",
            "Tier2: 用户已离开且 Tier1 判死（静默 ${inboundAgoMs / 1000}s），原生接管配对与任务事件" +
                "（回前台/亮屏自动交还）",
        )
    }

    /** 诊断指令 tier2_test：原生直连探针跑一轮（默认 60s 自动关闭）。 */
    fun startTier2Probe(durationMs: Long = 60_000L) {
        val c = relayCreds
        if (c == null) {
            Diagnostics.log("warn", "Tier2: 凭证未就绪（页面还没移交 relaycreds），稍后重试")
            return
        }
        Tier2Probe.start(c, durationMs)
    }

    fun stopTier2Probe() {
        Tier2Probe.stop("手动停止")
    }

    /**
     * 诊断指令 tier2_takeover：接管语义（桥覆盖 + 会话事件）跑固定时长后自动交还。
     * 诊断命令会伴随页面重载，凭证由页面重新移交——等它到达再启动（最多 5 次）。
     */
    fun startTier2TakeoverForTest(autoStopMs: Long = 90_000L, attempt: Int = 1) {
        if (relayCreds == null) {
            Diagnostics.log("info", "Tier2: 等待凭证重发（第 $attempt 次，页面重载中）")
            if (attempt > 5) {
                Diagnostics.log("warn", "Tier2: 凭证始终未到，放弃启动接管验证")
                return
            }
            mainHandler.postDelayed({
                startTier2TakeoverForTest(autoStopMs, attempt + 1)
            }, 2_000L)
            return
        }
        Tier2Probe.startTakeoverForTest(autoStopMs)
    }

    fun evaluateJs(script: String) {
        val evaluator = jsEvaluator
        if (evaluator == null) {
            Diagnostics.log("debug", "尚无 WebView，跳过脚本执行")
            return
        }
        mainHandler.post {
            try {
                evaluator(script)
            } catch (e: Exception) {
                Diagnostics.log("warn", "执行注入脚本失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------- page state -> bar
    //
    // The main screen has no app bar, so the strip behind the status bar must
    // carry the remote page's top-surface colour. The injected layer reports the
    // state and theme by NAME (see inject.js section 6); only the Activity can
    // paint, and it is not always alive, so the report is dropped when it is not.

    /** (page state token, theme token) -> the Activity that paints the strip. */
    @Volatile
    private var pageStateListener: ((String, String) -> Unit)? = null

    fun setPageStateListener(listener: ((String, String) -> Unit)?) {
        pageStateListener = listener
    }

    private fun onPageState(state: String, theme: String) {
        if (state.isEmpty()) return
        try {
            pageStateListener?.invoke(state, theme)
        } catch (e: Exception) {
            // A listener that throws must not break the WebView's JS bridge.
            Diagnostics.log("warn", "应用页面状态失败: ${e.message}")
        }
    }

    /** One-line readout for the settings screen. */
    fun livenessSummary(): String {
        val snapshot = liveness ?: return "尚未收到注入层数据（网页可能还没加载完）"
        val ageMs = lastInboundAgeMs()
        val ageText = when {
            ageMs == null -> "从未收到帧"
            ageMs < 5000 -> "刚刚"
            else -> "${formatDuration(ageMs)}前"
        }
        val link = when {
            snapshot.socketState != 1 && snapshot.socketState != -1 -> "socket 未连接"
            ageMs != null && ageMs > STALE_READOUT_MS -> "链路可能已断"
            snapshot.paired -> "已配对"
            else -> "未配对"
        }
        return "最近收帧 $ageText · 累计 ${snapshot.inboundFrames} 帧 · " +
            "配对确认 ${snapshot.pairAcks} 次 · 重连 ${snapshot.socketsClosed} 次 · $link"
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "$hours 小时 $minutes 分"
            minutes > 0 -> "$minutes 分 $seconds 秒"
            else -> "$seconds 秒"
        }
    }

    /** Shortest background window that produces a meaningful verdict. */
    private const val VERDICT_MIN_BACKGROUND_MS = 60_000L

    /** A quiet link for longer than this is called out in the readout. */
    private const val STALE_READOUT_MS = 120_000L

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        notifier = Notifier(appContext)
        notifier.ensureChannels()
        startTier1Watchdog()
    }

    fun prefs(): Prefs = prefs

    fun notifier(): Notifier = notifier

    // ------------------------------------------------------------ bridge input

    /**
     * Entry point for everything the injected script reports. Never throws: a
     * malformed message must not break the WebView's JS bridge.
     */
    fun onBridgeMessage(json: String) {
        try {
            val root = JSONObject(json)
            when (root.optString("event")) {
                "sessions" -> onSessions(root.optJSONObject("data"))
                "status" -> onStatus(root.optJSONObject("data"))
                "liveness" -> root.optJSONObject("data")?.let { onLiveness(it) }
                "diag" -> {
                    val data = root.optJSONObject("data") ?: return
                    Diagnostics.log(data.optString("level", "info"), data.optString("message", ""))
                }
                "pagelog" -> {
                    // The page's own log sink (window.zcode.log). In the
                    // production build this is the ONLY outlet the page's
                    // lifecycle events have — console is short-circuited — so
                    // before this sink existed the page's account of "I broke /
                    // I'm retrying" was silently dropped (docs/05 audit).
                    val data = root.optJSONObject("data") ?: return
                    val args = data.optJSONArray("args")
                    val parts = ArrayList<String>(args?.length() ?: 0)
                    for (i in 0 until (args?.length() ?: 0)) {
                        val part = args!!.optString(i)
                        if (part.isNotEmpty()) parts.add(part)
                    }
                    Diagnostics.log(
                        data.optString("level", "info"),
                        "页面: " + parts.joinToString(" ").take(600),
                    )
                }
                "pagevitals" -> {
                    // DOM 体征快照（卡死看门狗布防/撤防/放弃时的现场）。
                    val data = root.optJSONObject("data") ?: return
                    Diagnostics.log(
                        "debug",
                        "页面体征(${data.optString("why")}): ${data.optJSONObject("vitals")}",
                    )
                }
                "relaycreds" -> {
                    // Tier2（原生直连 relay）凭证：注入层一次性移交，仅驻内存。
                    // 任何日志路径都不得打印这些字段（sid/hash/mid 红线）。
                    if (relayCreds != null) return
                    val data = root.optJSONObject("data") ?: return
                    val wsUrl = data.optString("url")
                    val deviceSid = data.optString("deviceSid")
                    val passHash = data.optString("passHash")
                    if (wsUrl.isEmpty() || deviceSid.isEmpty() || passHash.isEmpty()) {
                        Diagnostics.log("warn", "Tier2: relaycreds 字段不全，忽略")
                        return
                    }
                    relayCreds = RelayCreds(
                        wsUrl = wsUrl,
                        deviceSid = deviceSid,
                        passHash = passHash,
                        deviceMid = data.optString("deviceMid").ifBlank { null },
                    )
                    // Tier2 接管期间的任务事件直接进原生 TaskStore/通知链路
                    // （M3c）：与注入层 post('sessions') 的更新形状同构。
                    Tier2Probe.sessionsSink = { update -> acceptNativeSessions(update) }
                    Diagnostics.log("info", "Tier2: relay 凭证已接收（仅内存）")
                }
                "pagestate" -> {
                    // Which visual state the page is in, and which theme it
                    // resolved for itself. Names only — the colour table lives in
                    // core/PageBarColor.kt and the Activity applies it.
                    val data = root.optJSONObject("data") ?: return
                    onPageState(data.optString("state"), data.optString("theme"))
                }
                "ready" -> {
                    val data = root.optJSONObject("data") ?: return
                    onPageReloaded()
                    injectedReady = true
                    Diagnostics.log(
                        "info",
                        "注入脚本已就绪 (subscribeAll=${data.optBoolean("subscribeAll", true)})",
                    )
                }
                else -> Unit
            }
        } catch (e: Exception) {
            Diagnostics.log("warn", "无法解析注入脚本消息: ${e.message}")
        }
    }

    /** Tier2 原生任务事件入口（M3c）：更新形状与注入层 post('sessions') 同构。 */
    fun acceptNativeSessions(update: JSONObject) {
        onSessions(update)
    }

    private fun onSessions(data: JSONObject?) {
        if (data == null) return
        val key = data.optString("key")
        if (key.isEmpty()) return
        val array: JSONArray = data.optJSONArray("sessions") ?: JSONArray()
        val tasks = ArrayList<TaskSnapshot>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val sessionId = item.optString("sessionId")
            if (sessionId.isEmpty()) continue
            tasks.add(
                TaskSnapshot(
                    sessionId = sessionId,
                    title = item.optString("title"),
                    phase = item.optString("phase"),
                    preview = item.optString("preview"),
                    pendingInteractionId = item.optString("pendingInteractionId"),
                    lastActivityAt = item.optLong("lastActivityAt"),
                    hasBackgroundWork = item.optBoolean("hasBackgroundWork"),
                )
            )
        }
        logPhaseHistogram(key, tasks)
        val update = store.applyWorkspace(
            key = key,
            title = data.optString("title"),
            path = data.optString("workspacePath"),
            identity = data.optString("workspaceIdentity"),
            source = data.optString("source"),
            tasks = tasks,
        )
        applyUpdate(update)
    }

    /** Last phase histogram logged per workspace, so the line is written on change only. */
    private val lastPhaseHistogram = HashMap<String, String>()

    /**
     * What each workspace actually reports, phase by phase.
     *
     * This exists because of a real question the phone raised on 2026-09-12: the
     * remote page showed a task as 运行中 while the shell had no running-task
     * notification for it. Whether that is a phase we do not classify as running,
     * a task the sessions-index carries outside its `sessions` array, or a frame
     * we never see at all, is not answerable from the outside — `RUNNING_PHASES`
     * is a guess about someone else's protocol, and this line is what turns it
     * into something checkable. Deduped per workspace so it stays a change log.
     */
    private fun logPhaseHistogram(key: String, tasks: List<TaskSnapshot>) {
        val counts = LinkedHashMap<String, Int>()
        for (task in tasks) {
            val phase = task.phase.ifEmpty { "(空)" }
            counts[phase] = (counts[phase] ?: 0) + 1
        }
        val line = tasks.size.toString() + " 个任务 · " +
            counts.entries.joinToString(" / ") { "${it.key}×${it.value}" }
        if (lastPhaseHistogram[key] == line) return
        lastPhaseHistogram[key] = line
        Diagnostics.log("debug", "工作区相位 $key：$line")
    }

    private fun onStatus(data: JSONObject?) {
        if (data == null) return
        val active = data.optBoolean("active", false)
        val bridges = data.optInt("bridges", 0)
        val passive = data.optInt("passive", 0)
        val reason = data.optString("reason")
        val summary = "订阅状态 active=$active bridges=$bridges passive=$passive"
        if (active != reportedActive) {
            reportedActive = active
            Diagnostics.info(if (reason.isEmpty()) summary else "$summary ($reason)")
        } else if (reason.isNotEmpty()) {
            Diagnostics.log("debug", "$summary ($reason)")
        }
    }

    private fun applyUpdate(update: TaskStore.Update) {
        // Completions and attention go out immediately: they are the reason the
        // app exists, and a delay would be user-visible.
        for (event in update.completed) {
            notifier.notifyCompleted(event)
            Diagnostics.info("任务完成: ${event.task.displayTitle}")
        }
        for (event in update.attention) {
            notifier.notifyAttention(event)
            Diagnostics.info("任务等待确认: ${event.task.displayTitle}")
        }
        // The ongoing list also changes on every preview delta (several times a
        // second). A completion or attention event, by contrast, changes the
        // list in a way the user is actively watching for — publish those at
        // once and let only the chatter be throttled.
        val urgent = update.completed.isNotEmpty() || update.attention.isNotEmpty()
        enqueueOngoing(update, throttled = !urgent)
    }

    /**
     * Coalesces ongoing-notification updates.
     *
     * Removals are accumulated (union) while the running list is replaced by the
     * newest — a trailing publish must not lose the cancellation that an
     * intermediate update requested, which is the bug this shape exists to
     * prevent.
     */
    private fun enqueueOngoing(update: TaskStore.Update, throttled: Boolean) {
        synchronized(lock) {
            pendingRemovedIds.addAll(update.removedIds)
            latestRunning = update.running
            val elapsed = System.currentTimeMillis() - lastPublishAt
            pendingFlush?.let { mainHandler.removeCallbacks(it) }
            pendingFlush = null
            if (throttled && elapsed < ONGOING_THROTTLE_MS) {
                val runnable = Runnable { flushOngoing() }
                pendingFlush = runnable
                mainHandler.postDelayed(runnable, ONGOING_THROTTLE_MS - elapsed)
                return
            }
        }
        flushOngoing()
    }

    private fun flushOngoing() {
        // Returning a pair keeps the critical section from having to assign
        // declarations that are read outside it.
        val snapshot = synchronized(lock) {
            pendingFlush = null
            val running = latestRunning
            val removed = pendingRemovedIds.toList()
            pendingRemovedIds.clear()
            lastPublishAt = System.currentTimeMillis()
            running to removed
        }
        notifier.syncRunningTasks(
            TaskStore.Update(
                running = snapshot.first,
                removedIds = snapshot.second,
                completed = emptyList(),
                attention = emptyList(),
            )
        )
        updateServiceNotification(snapshot.first)
    }

    /** Keeps the foreground service notification in step with the task list. */
    fun updateServiceNotification(running: List<TaskStore.RunningNotification> = currentRunning()) {
        if (!serviceRunning) return
        val text = if (running.isEmpty()) {
            appContext.getString(R.string.keepalive_text_idle)
        } else {
            Notifier.runningBody(running)
        }
        notifier.postServiceNotification(notifier.buildServiceNotification(running.size, text))
    }

    private fun currentRunning(): List<TaskStore.RunningNotification> =
        store.workspaces().flatMap { workspace ->
            workspace.running.map { task ->
                val status = if (task.isWaitingForUser) TaskStatus.WAITING else TaskStatus.RUNNING
                TaskStore.RunningNotification(
                    id = com.zcode.remote.core.NotifyState.notificationIdFor(workspace.key, task.sessionId),
                    workspaceKey = workspace.key,
                    workspaceTitle = workspace.title,
                    task = task,
                    status = status,
                    body = com.zcode.remote.core.NotifyState.formatBody(task.preview, workspace.title),
                )
            }
        }

    // --------------------------------------------------------------- lifecycle

    /**
     * Starts the foreground service. Must be called while the app is in the
     * foreground: Android 12+ refuses a background start.
     */
    fun ensureServiceRunning(context: Context) {
        if (serviceRunning) return
        val intent = Intent(context, KeepAliveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            serviceRunning = true
            Diagnostics.info("后台保活服务已启动")
        } catch (e: Exception) {
            Diagnostics.log("warn", "启动保活服务失败: ${e.message}")
        }
    }

    fun onServiceCreated() {
        serviceRunning = true
    }

    fun onServiceDestroyed() {
        serviceRunning = false
        Diagnostics.log("warn", "保活服务被系统销毁")
    }

    fun isServiceRunning(): Boolean = serviceRunning

    /** Called when the page (re)loads: the injected layer starts from scratch. */
    fun onPageStarted() {
        injectedReady = false
        reportedActive = false
    }

    /** Called when a fresh page has finished loading and re-subscribed. */
    fun onPageReloaded() {
        val update = store.reset()
        notifier.syncRunningTasks(update)
        updateServiceNotification(emptyList())
    }

    fun isInjectedReady(): Boolean = injectedReady
}
