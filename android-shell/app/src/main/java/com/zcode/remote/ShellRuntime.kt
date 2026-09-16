package com.zcode.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.NotifyState
import com.zcode.remote.core.Prefs
import com.zcode.remote.core.RelayCreds
import com.zcode.remote.core.RelayWire
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

    /** Age of the newest inbound frame, or null if nothing ever arrived. */
    fun lastInboundAgeMs(): Long? {
        val snapshot = liveness ?: return null
        if (snapshot.lastInboundAtElapsed <= 0) return null
        return SystemClock.elapsedRealtime() - snapshot.lastInboundAtElapsed
    }

    private fun onLiveness(data: JSONObject) {
        val receivedAt = SystemClock.elapsedRealtime()
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
        // 这里**不再**用注入层自述的链路静默（lastInboundAgoMs）触发接管：
        // 桌面端安静不等于渲染器死了；接管只认"页面链路判死"（见 maybeStartNativeCarrier）。
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
            appIsForeground = true
            evaluateJs("window.__zcodeShellSetAppForeground && window.__zcodeShellSetAppForeground(true);")
            // 成功；重连后 runtime 已死，由卡死看门狗的僵尸档走刷新恢复。
            if (Tier2Probe.isRunning()) {
                Tier2Probe.stop("回前台交还")
                carrierHandedBack = true
                Diagnostics.log(
                    "warn",
                    "后台原生承载：回前台交还完成——页面可见性恢复后 Chromium 网络栈复活，" +
                        "由页面自己的 recoverConnection 重拨（本壳不重载页面）",
                )
            }
            clearCarrierState()
            stopLiveProgressPolling()
            if (carrierHandedBack) {
                // 只在这一条路径上安排兜底：8s 后页面若还没开线、也没有新入站帧，
                // 说明它已经掉进失败态（自己回不来），那时才重载一次（5 分钟限流）。
                carrierHandedBack = false
                evaluateJs(
                    "window.__zcodeShellAfterCarrierReturn && " +
                        "window.__zcodeShellAfterCarrierReturn(8000);",
                )
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
            appIsForeground = false
            evaluateJs("window.__zcodeShellSetAppForeground && window.__zcodeShellSetAppForeground(false);")
            startHeartbeatPump()
            backgroundStartedAt = SystemClock.elapsedRealtime()
            val snapshot = liveness
            backgroundFrameBase = snapshot?.inboundFrames ?: 0
            backgroundAckBase = snapshot?.pairAcks ?: 0
            backgroundTickBase = snapshot?.backgroundTicks ?: 0
            // 只上报一次 liveness：它是"渲染器是否已冻结"的判据。真正的接管由
            // pump 里的 maybeStartNativeCarrier 按"页面链路判死"触发（见其注释）。
            requestLivenessReport()
        }
    }

    // ------------------------------------------------- M4 活进展（流体云跟手）
    //
    // 桌面端对远端的推送是稀疏的（真机逐 10s 统计：页面拿到快照后整段只有心跳
    // 帧、入站字符数为 0），会话索引的 preview 又只在轮次边界变。所以在**壳自己
    // 持有连接**的后台时段，由原生订阅每个在跑任务的对话详情（已验证的取快照
    // 路径），把最新一行（流式正文 / 正在跑的工具）喂给通知与流体云；每隔
    // [LIVE_REANCHOR_EVERY_POLLS] 拍重挂一次订阅，逼桌面端再推一份快照——最坏
    // 也只慢一个重挂周期，不会回到"停在上一轮开头"那种几十分钟级的滞后。
    //
    // 不用 conversationRowsRangeV4 拉取：真机实测它**即便在订阅建立之后**仍然
    // 每次 20s 超时（桌面端根本不回这个包），是条死路。
    //
    // 前台时段不做：那时连接在页面手里（单控制端互斥），订不了也拉不了。
    private const val LIVE_PROGRESS_POLL_MS = 12_000L

    /** 每这么多拍重挂一次（12s × 2 ≈ 24s）。真机实测桌面端推完首屏快照后就不再推，
     *  所以刷新率完全由这个周期决定；pre.96 实测 48s 一拍时卡片肉眼可见地"半分钟
     *  不动"，24s 与"对话轮之间几秒一条正文"的节奏更接近，成本只是每 24s 一次
     *  resync（正常时它只是轻量应答，不再像旧版那样必然超时触发重开）。 */
    private const val LIVE_REANCHOR_EVERY_POLLS = 2

    @Volatile
    private var livePolling = false

    @Volatile
    private var livePolls = 0

    @Volatile
    private var livePollInFlight = false

    @Volatile
    private var liveEpoch = 0L

    private val liveProgressThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "zcode-live-progress").apply { isDaemon = true }
    }

    private val liveProgressPoller = object : Runnable {
        override fun run() {
            if (!userIsAway()) {
                return
            }
            if (!livePolling) {
                return
            }
            val refs = store.runningTaskRefs()
            livePolls += 1
            if (refs.isNotEmpty() && !livePollInFlight) {
                val reanchor = livePolls % LIVE_REANCHOR_EVERY_POLLS == 0
                val epoch = liveEpoch
                livePollInFlight = true
                liveProgressThread.execute {
                    try {
                        if (epoch != liveEpoch || !userIsAway() || !Tier2Probe.isRunning()) return@execute
                        if (reanchor) {
                            Tier2Probe.reanchorProgress()
                        }
                        for ((key, sessionId) in refs) {
                            if (epoch != liveEpoch) break
                            Tier2Probe.subscribeProgress(key, sessionId)
                        }
                    } finally {
                        livePollInFlight = false
                    }
                }
            }
            mainHandler.postDelayed(this, LIVE_PROGRESS_POLL_MS)
        }
    }

    private fun pushLivePreview(key: String, sessionId: String, text: String) {
        val epoch = liveEpoch
        mainHandler.post {
            if (epoch != liveEpoch || !userIsAway() || !livePolling) return@post
            val update = store.applyLivePreview(key, sessionId, text)
            if (update.running.isNotEmpty()) {
                Diagnostics.log(
                    "debug",
                    "活进展 $key：${text.replace('\n', ' ').take(60)}",
                )
                applyUpdate(update)
            }
        }
    }

    /**
     * 网页自带对话流送来的正文 → 覆盖该会话的活进展，让流体云 / 常驻通知跟手。
     *
     * 与 [pushLivePreview]（原生接管那条路）的三点不同：
     *   1. **不需要接管**——数据来自页面那条 socket，前台后台都在，也不涉及第二条
     *      连接，因此不会触发单控制端互斥（用户看到的 KICKED）；
     *   2. 不要求 `livePolling`，也不清 livePreviews（页面重新供数时以会话索引为准）；
     *   3. 工作区要用 sessionId 反查——对话流只给 `conversation/sess_…`。
     */
    private fun onConversationText(sessionId: String, text: String) {
        val key = store.runningTaskRefs().firstOrNull { it.second == sessionId }?.first
        if (key == null) {
            // 没有在跑的任务要显示这句正文，丢掉即可（任务列表仍走会话索引更新）。
            return
        }
        val head = RelayWire.progressHeadOf(text)
        if (head.isEmpty()) return
        val update = store.applyLivePreview(key, sessionId, head)
        if (update.running.isNotEmpty()) {
            Diagnostics.log("debug", "页面正文 $key：${head.take(60)}")
            applyUpdate(update)
        }
    }

    private fun startLiveProgressPolling() {
        if (livePolling) return
        livePolling = true
        liveEpoch += 1
        livePollInFlight = false
        val epoch = liveEpoch
        Tier2Probe.progressSink = { key, sessionId, text ->
            if (epoch == liveEpoch) pushLivePreview(key, sessionId, text)
        }
        // 运行态（controller/tasks-index）是后台期间"哪些任务在跑"的唯一正源：
        // 没有它，sessions-index 的持久态会把 store 覆盖成"全完成"，活进展无处可去。
        Tier2Probe.liveTaskSink = live@{ tasks ->
            if (epoch != liveEpoch || !userIsAway()) return@live
            mainHandler.post {
                if (epoch != liveEpoch || !userIsAway() || !livePolling) return@post
                val update = store.applyLiveTasks(tasks)
                val running = tasks.count { it.phase in NotifyState.RUNNING_PHASES }
                Diagnostics.log(
                    "debug",
                    "运行态：${tasks.size} 个任务（running=$running）",
                )
                applyUpdate(update)
            }
        }
        // 会话流自带的运行态（turnHeader.state）：controller 流拿不到时的兜底，
        // 也是真机上更常见的那条路。
        Tier2Probe.turnStateSink = turn@{ key, sessionId, running ->
            if (epoch != liveEpoch || !userIsAway()) return@turn
            mainHandler.post {
                if (epoch != liveEpoch || !userIsAway() || !livePolling) return@post
                val update = store.applyConversationRunState(key, sessionId, running)
                if (update.running.isNotEmpty() || update.removedIds.isNotEmpty()) {
                    Diagnostics.log(
                        "debug",
                        "会话运行态：${if (running) "在跑" else "结束"} · $sessionId",
                    )
                }
                applyUpdate(update)
            }
        }
        Tier2Probe.runningSessionsProvider = { key ->
            store.runningTaskRefs().filter { it.first == key }.map { it.second }
        }
        mainHandler.post(liveProgressPoller)
    }

    /** 交还前台：停止拉取/订阅，并把活进展清掉（此后以页面供的会话索引为准）。 */
    private fun stopLiveProgressPolling() {
        livePolling = false
        liveEpoch += 1
        Tier2Probe.progressSink = null
        Tier2Probe.liveTaskSink = null
        Tier2Probe.turnStateSink = null
        Tier2Probe.runningSessionsProvider = null
        mainHandler.removeCallbacks(liveProgressPoller)
        store.clearLivePreviews()
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

    @Volatile
    private var heartbeatPumpRunning = false

    private var lastAwayState = false

    private var pumpDispatches = 0
    private var pumpDispatchesForVerdict = 0

    private val pumpRunnable = object : Runnable {
        override fun run() {
            if (userIsAway()) {
                if (jsEvaluator != null) {
                    pumpDispatches += 1
                    evaluateJs("window.__zcodeShellHeartbeat && window.__zcodeShellHeartbeat();")
                    if (pumpDispatches == 1 || pumpDispatches % PUMP_LOG_EVERY == 0) {
                        Diagnostics.log("debug", "后台心跳泵 #$pumpDispatches 次发令")
                    }
                }
                // 页面链路判死 ⇒ 把连接交给原生（Chromium 的网络栈在后台会整体死掉，
                // 页面侧自救无效；见 `maybeStartNativeCarrier` 的取证）。
                maybeStartNativeCarrier()
                mainHandler.postDelayed(this, PUMP_INTERVAL_MS)
            } else {
                heartbeatPumpRunning = false
            }        }
    }

    private fun startHeartbeatPump() {
        if (heartbeatPumpRunning) return
        heartbeatPumpRunning = true
        mainHandler.removeCallbacks(pumpRunnable)
        pumpDispatches = 0
        mainHandler.postDelayed(pumpRunnable, PUMP_INTERVAL_MS)
        Diagnostics.log(
            "debug",
            "后台心跳泵已启动（每 ${PUMP_INTERVAL_MS / 1000}s 驱动一次注入层心跳）",
        )
    }

    private fun stopHeartbeatPump() {
        heartbeatPumpRunning = false
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

    // ------------------------------------------------- 后台失速自愈（僵尸连接）
    //
    // 现场（真机 2026-09-15 23:09–23:33，v129 只读壳，22 分钟不间断采样）：
    // 退后台约 60s 后入站帧与 `链路 ack` **同时**归零，而页面那条 socket 的
    // `readyState` 始终 1（OPEN）、`paired` 始终 true —— 远端不再回任何东西，
    // 也没有 close 事件，客户端视角就是一条僵尸连接。页面按自己的设计在 hidden
    // 时挂起（stopHeartbeat + 清看门狗），所以它永远不知道自己已经瞎了；而回前台
    // 时它自己的 `recoverConnection()` 一拨就恢复，证明"重拨"本身就是解药。
    //
    // 于是这里做的事只有一件：**在后台失速时推动页面走它自己的恢复路径**
    // （注入层的 `__zcodeShellNudgeRecover`：合成 `online` → 页面的生命周期
    // observer → recoverConnection；不生效才退回"关一次它自己的 socket"）。
    // 不接管连接、不重载页面、不动页面状态。
    //
    // 默认关闭：这是一条**写操作**，按「实现要点」14 的规矩，它必须先回答两句——
    //   ① 页面自己做不到吗？做不到（它没有入站帧就没有任何失败信号，看门狗又被
    //      自己的 suspend 清掉了）。
    //   ② 怎么知道它失败了？壳每 10s 仍在发顶层 `pair_status_query`，健康链路上
    //      它一定有 ack（真机健康窗 `链路 ack 1~5/10s`）；**连 ack 都没有**就说明
    //      链路已死，而不是"桌面端安静"。
    // 真机验收通过后再决定是否转正（`stallguard_on` 是 adb 开关）。

    // ------------------------------------------------- 后台承载（方案 B 落地）
    //
    // 真机定案（2026-09-16 00:00–00:05，v131）：
    //
    //   退后台约 60–70s 后，**Chromium 的网络栈整体停止工作**，而 App 的网络没事：
    //     · 页面那条 relay socket 停在 `readyState=1(OPEN)`、`paired=true`，
    //       双向零帧持续 22 分钟（`收帧 0 / 发帧 0 / 链路 ack 0`，而壳每 10s 仍在
    //       发顶层 pair_status_query——`探针 1` 却连 ack 都没有）；
    //     · 同一刻页面里新建的 `fetch` **76 秒既不成功也不失败**（挂住）；
    //     · 同一刻**原生 Java 侧**：`原生网络自检 裸TCP=ok 67ms · HTTPS=HTTP 200 276ms`；
    //     · 同一刻原生 WebSocket：1 秒内 `★配对成功（matched）` + 开桥 + 订阅索引。
    //   结论：**不是平台掐了 App 的网络，是 Chromium 的网络栈在后台死了**。
    //   于是页面侧任何自救（重拨 / 合成 online / 重载）都不可能成功——这也是此前
    //   注入层两轮"救后台"全部无效的原因；只有把连接的**承载**换成原生才行。
    //
    // 为什么不会 KICK 页面：判据是"入站帧静默 ≥35s"，此刻页面那条连接早已是僵尸
    // （服务端那侧也失效），而且它的网络栈是死的——**KICKED 帧根本送不到页面**，
    // 页面不会进终态。回前台时原生先交还（见 [onAppForegroundChanged]），页面自己
    // 的 `recoverConnection` 在可见性恢复、Chromium 网络栈复活后重拨，
    // 所以**回前台不需要重载页面**（这条要真机验收）。

    /**
     * 失速门槛：注入层自述"最早一帧入站是多久以前"超过它，就认定页面那条链路已死。
     *
     * 35s = 3 个泵周期 + 余量：健康的后台窗口里每 10s 都有入站帧（数据帧或我们
     * 探针的 ack），连续三窗一帧都没有，只可能是链路断了。
     */
    private const val STALL_SILENCE_MS = 35_000L

    /** 页面刚动过 socket（重拨中）后的静默期：见 [lastSocketMarkForStall]。 */
    private const val CARRIER_REQUIET_AFTER_SOCKET_MS = 20_000L

    /** 多工作区覆盖一次最多开几座桥（与 `Tier2Probe.DEFAULT_MAX_COVERAGE` 对齐）。 */
    private const val MULTI_WS_COVERAGE_CAP = 3

    /**
     * 后台承载总开关。
     *
     * **默认开启**（2026-09-16 01:20 恢复）。曾经在 00:52 临时改成 false，因为那时发现
     * "原生配对会让桌面端 4.3 秒后拆掉自己的 window host"。**那个问题已定位并修掉，
     * 而且不在原生配对本身**：真凶是原生桥对 `zcode-agent.onDynamicControllerFrame`
     * 的那次 `rpc:listen`——它让桌面端 host 进程当场 `uncaughtException` 并自毁
     * （桌面端日志：`[rpc:listen] … onDynamicControllerFrame FAIL` → `uncaughtException`
     * → `disposing host resources` → `unregistered host` + `host process exited with code 1`）。
     * 关掉那条流（`RelayBridge.controllerStreamEnabled = false`；运行态本来就有会话流
     * `turnHeader.state` 兜底）之后真机复验：原生配对 + 开桥，桌面端 host **稳定存活、
     * 无 uncaughtException**，桥也开在正确的工作区上。
     *
     * 配套两处（同轮真机定案）：① 只开**页面自己正在显示**的那个工作区的桥
     * （`pageWorkspaceKey`）——扫全部工作区会让桌面端每次新建再拆 host；
     * ② 配对后照页面顺序补 `mobile-diagnostic` / `mobile-view-state-update` /
     * `bootstrap-request` 三帧（见 `Tier2Probe.sendBootstrapThenCoverage`）。
     */
    @Volatile
    private var carrierEnabled = true

    /**
     * 页面当前显示的工作区/任务（由注入层从页面自己的 `workspace-bridge-open` 帧里取，见
     * `pagews` 事件）。后台原生承载**只**为它开桥——与页面自己的行为一致，也是真机定案：
     * 扫全部工作区会触发桌面端拆 host（docs/16 §8/§10）。
     */
    @Volatile
    private var pageWorkspaceKey = ""

    @Volatile
    private var pageWorkspaceTaskId = ""

    /**
     * 多工作区覆盖开关（adb：`multi_ws_on` / `multi_ws_off`），**默认关**。
     *
     * 打开后承载不再只开页面工作区一座桥，而是 **page 工作区 ∪ 有在跑任务的工作区**
     * （上限 [MULTI_WS_COVERAGE_CAP]）。为什么默认关：这是 `docs/17` 的下一个方向，
     * 桌面端能否同时服务多座桥**还没验证过**（旧证据被 host 之死污染），所以先做成开关，
     * 真机验证通过再谈改默认（`docs/17` §7.5 的原话）。
     */
    @Volatile
    private var multiWorkspaceCoverage = false

    /**
     * `coverage_ws:<k1>,<k2>` 诊断指令的显式覆盖清单：**优先于开关**，专供 E1 做干净 A/B
     * （不必真起第二个任务，也不必改开关默认值）。
     */
    @Volatile
    private var coverageOverride: List<String> = emptyList()

    private var carrierStarted = false

    /** 刚刚交还过（前台分支读一次并清掉）：只在这条路径上安排"页面没恢复才重载"的兜底。 */
    private var carrierHandedBack = false

    /**
     * 防误判用的 socket 生命周期观测：静默期内 socket 计数变了 ⇒ 页面在重拨，等它。
     *
     * 现场（2026-09-16 02:21，回前台那一下）：页面自己 `recoverConnection → reconnectNow`
     * 已经打出 `relay socket open (#2)`，但注入层的"入站帧年龄"还是旧值（`inboundAgo=622s`，
     * 因为新 socket 还没收到第一帧），承载于是误判"链路已死"并接管——**把刚恢复的页面 KICK 了**。
     * 20s 是"重拨 + 首帧到达"的经验窗口（真机那次 ~6s 就恢复了）。
     */
    private var lastSocketMarkForStall = -1L
    private var lastSocketChangeAt = 0L

    /** socket 生命周期的指纹：页面真的重拨了，它一定变。 */
    private fun nudgeMark(): Long {
        val snapshot = liveness ?: return -1L
        return snapshot.socketsOpened * 1_000_000L + snapshot.socketsClosed * 1_000L +
            (snapshot.inboundFrames % 1_000L)
    }

    /** 开关（adb：`carrier_on` / `carrier_off`）。 */
    fun setCarrierEnabled(enabled: Boolean) {
        carrierEnabled = enabled
        Diagnostics.log("warn", "后台原生承载：${if (enabled) "已开启" else "已关闭"}")
        if (!enabled && Tier2Probe.isRunning()) {
            Tier2Probe.stop("后台承载被关闭")
            stopLiveProgressPolling()
            releaseCarrierWakeLock()
            carrierStarted = false
        }
    }

    /**
     * 承载这一轮要开哪些工作区的桥（多工作区方向，见 `docs/17`）。
     *
     * 优先级：诊断覆盖清单 > 多工作区开关 > 只开页面工作区（返回空，交给 `Tier2Probe` 决定）。
     * 开关打开时＝ page 工作区 ∪ 有在跑任务的工作区，上限 [MULTI_WS_COVERAGE_CAP]。
     */
    private fun carrierCoverageTargets(): List<String> {
        if (coverageOverride.isNotEmpty()) return coverageOverride
        if (!multiWorkspaceCoverage) return emptyList()
        val out = LinkedHashSet<String>()
        if (pageWorkspaceKey.isNotEmpty()) out.add(pageWorkspaceKey)
        for ((key, _) in store.runningTaskRefs()) {
            if (out.size >= MULTI_WS_COVERAGE_CAP) break
            if (key.isNotEmpty()) out.add(key)
        }
        return out.take(MULTI_WS_COVERAGE_CAP)
    }

    /**
     * 后台原生承载：把连接交给原生。
     *
     * 只判"完全没有任何入站帧"——健康链路上壳的探针一定有 ack（真机健康窗
     * `链路 ack 1~5/10s`），连 ack 都没有就不是"桌面端安静"，是链路已死。
     */
    private fun maybeStartNativeCarrier() {
        if (!carrierEnabled) return
        val age = lastInboundAgeMs() ?: return
        val now = SystemClock.elapsedRealtime()

        // 防误判：页面**刚刚**开过新 socket（重拨中）时不要接管。
        //
        // 现场（2026-09-16 02:21，回前台那一下）：页面自己 `recoverConnection → reconnectNow`
        // 已经打出 `relay socket open (#2)`，但注入层的"入站帧年龄"还是旧值（`inboundAgo=622s`，
        // 因为新 socket 还没收到第一帧），承载于是误判"链路已死"并接管——**把刚恢复的页面 KICK 了**
        // （日志：`页面连接被顶掉（relay 返回 KICKED，应用在后台）`）。
        // 判据：socket 生命周期计数在静默期内变过 ⇒ 页面在自救，等它；要求"最近 20s 没动过 socket"。
        val socketMark = nudgeMark()
        if (socketMark != lastSocketMarkForStall) {
            lastSocketMarkForStall = socketMark
            lastSocketChangeAt = now
        }
        val sinceSocketChange = now - lastSocketChangeAt
        if (sinceSocketChange < CARRIER_REQUIET_AFTER_SOCKET_MS) {
            if (age >= STALL_SILENCE_MS) {
                Diagnostics.log(
                    "info",
                    "后台原生承载：页面 ${sinceSocketChange / 1000}s 前刚动过 socket（在重拨），本轮不接管",
                )
            }
            return
        }

        if (age < STALL_SILENCE_MS) {
            // 页面链路自己活着（或已恢复）⇒ 原生必须让位，绝不能两条连着。
            if (carrierStarted) {
                Diagnostics.log(
                    "warn",
                    "后台原生承载：页面链路已恢复（最新入站帧 ${age / 1000}s 前），原生交还",
                )
                Tier2Probe.stop("页面链路已恢复")
                stopLiveProgressPolling()
                releaseCarrierWakeLock()
                carrierStarted = false
            }
            return
        }
        if (carrierStarted || Tier2Probe.isRunning()) return
        val creds = relayCreds
        if (creds == null) {
            Diagnostics.log("warn", "后台原生承载：等待页面移交 relay 凭证")
            return
        }
        carrierStarted = true
        Diagnostics.log(
            "warn",
            "后台原生承载：入站帧静默 ${age / 1000}s（门槛 ${STALL_SILENCE_MS / 1000}s，" +
                "Chromium 网络栈已死）——原生接管 relay 连接并订阅在跑会话，推到流体云",
        )
        acquireCarrierWakeLock()
        Tier2Probe.start(
            creds,
            durationMs = 0L,
            onlyWorkspace = pageWorkspaceKey,
            onlyTaskId = pageWorkspaceTaskId,
            coverageWorkspaces = carrierCoverageTargets(),
        )
        startLiveProgressPolling()
    }

    // ------------------------------------------------- 承载期间的 wake lock
    //
    // 为什么需要：承载走通之后，瓶颈从"Chromium 网络栈在后台死掉"变成"**熄屏后 CPU 睡眠**"。
    // 前台服务（specialUse）只保证**进程**活着，不保证 **CPU 醒着**：CPU 一睡，原生的
    // 12s 心跳与上游帧读取都会停，桌面端约 60s 后就判死——那就等于在锁屏场景下白做。
    // docs/15 §7.1 的官方基线里正有这一条：`PARTIAL_WAKE_LOCK`「even after the user
    // presses the power button」。
    //
    // 纪律：**只在这两种条件同时成立时持有**——① 应用在后台或熄屏（`userIsAway()`）
    // 且 ② 原生承载在跑。前台一律不持有；交还/停止/失败路径都会释放（见
    // [releaseCarrierWakeLock] 与 `clearCarrierState`）。

    private var carrierWakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireCarrierWakeLock() {
        if (carrierWakeLock?.isHeld == true) return
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val lock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "zcode-remote:carrier",
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            carrierWakeLock = lock
            Diagnostics.log("info", "后台原生承载：已持有 PARTIAL_WAKE_LOCK（熄屏下心跳才能继续跑）")
        } catch (e: Exception) {
            // 权限没给 / 系统拒绝都不致命：承载照跑，只是熄屏后可能被 CPU 睡眠拖住。
            Diagnostics.log("warn", "申请 wake lock 失败（熄屏可能被挂起）: ${e.message}")
        }
    }

    private fun releaseCarrierWakeLock() {
        val lock = carrierWakeLock ?: return
        carrierWakeLock = null
        try {
            if (lock.isHeld) {
                lock.release()
                Diagnostics.log("info", "后台原生承载：已释放 wake lock")
            }
        } catch (e: Exception) {
            Diagnostics.log("warn", "释放 wake lock 失败: ${e.message}")
        }
    }

    /** 交还时清账（由 [onAppForegroundChanged] 的前台分支调用）。 */
    private fun clearCarrierState() {
        releaseCarrierWakeLock()
        carrierStarted = false
    }

    /**
     * 原生网络自检（**不经 Chromium**）：先裸 TCP 连 relay host 的 443，再发一次 HTTPS GET。
     *
     * 为什么要原生再做一遍：墙内的页面 `fetch` 76 秒既不成功也不失败（真机 2026-09-15
     * 23:54:43→23:55:59），那既可能是"平台把 App 的网络掐了"，也可能是"Chromium 的
     * 网络栈在后台停了"。裸 TCP 是纯 Java 侧的判据，两者一分就清楚：
     *   · 裸 TCP 也连不上 ⇒ 包被丢了，App 在后台整体没网（换谁承载连接都救不了）；
     *   · 裸 TCP 秒连、只有那条 relay 连接死 ⇒ 只是那条连接坏了，重拨即可。
     */
    fun nativeNetProbe(tag: String) {
        val raw = try {
            prefs.remoteUrl
        } catch (e: Exception) {
            null
        }
        val host = try {
            android.net.Uri.parse(raw.orEmpty()).host
        } catch (e: Exception) {
            null
        } ?: "zcode.z.ai"
        Thread {
            val tcpStart = SystemClock.elapsedRealtime()
            val tcp = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, 443), 5000)
                    "ok ${SystemClock.elapsedRealtime() - tcpStart}ms"
                }
            } catch (e: Exception) {
                "失败 ${SystemClock.elapsedRealtime() - tcpStart}ms ${e.javaClass.simpleName}: ${e.message}"
            }
            val httpStart = SystemClock.elapsedRealtime()
            val http = try {
                val conn = java.net.URL("https://$host/remote/v4?__zcprobe=${System.currentTimeMillis()}")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cache-Control", "no-store")
                val code = conn.responseCode
                conn.disconnect()
                "HTTP $code ${SystemClock.elapsedRealtime() - httpStart}ms"
            } catch (e: Exception) {
                "失败 ${SystemClock.elapsedRealtime() - httpStart}ms ${e.javaClass.simpleName}: ${e.message}"
            }
            Diagnostics.log("warn", "原生网络自检[$tag] host=$host 裸TCP=$tcp · HTTPS=$http")
        }.start()
    }

    /**
     * 推动页面走它自己的恢复路径。这是本版唯一的"写页面连接"动作，且只在
     * 后台失速（已判死）时使用。
     */
    fun nudgePageRecovery(reason: String, mode: String = "event") {
        if (jsEvaluator == null) {
            Diagnostics.log("warn", "恢复推动跳过（JS 求值通道未就绪）：$reason")
            return
        }
        evaluateJs(
            "window.__zcodeShellNudgeRecover && window.__zcodeShellNudgeRecover(" +
                JSONObject.quote(mode) + ");",
        )
    }

    /**
     * 背景可调用的原生命令（adb broadcast 走 [DiagReceiver]，不经 Activity——
     * `am start` 会把退到后台的应用拉回前台，正好毁掉要测的后台现场）。
     *
     * @return true 表示这条命令由原生侧处理完毕，调用方不必再转给注入层。
     */
    fun runNativeDiag(cmd: String): Boolean {
        // 带参指令走前缀匹配（下面的 `when` 只做精确匹配）。
        if (cmd.startsWith("coverage_ws:")) {
            val list = cmd.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            coverageOverride = list
            Diagnostics.log(
                "warn",
                "多工作区覆盖：诊断覆盖清单已设为 " +
                    (if (list.isEmpty()) "(空)" else list.joinToString()) + "（${list.size} 座）",
            )
            return true
        }
        if (cmd == "coverage_ws_clear") {
            coverageOverride = emptyList()
            Diagnostics.log("warn", "多工作区覆盖：已清空诊断覆盖清单")
            return true
        }
        when (cmd) {
            // Tier2 原生直连实验（保留原语义）。
            "tier2_test" -> {
                startTier2Probe(60_000L)
                return true
            }
            "tier2_stop" -> {
                stopTier2Probe()
                return true
            }
            "tier2_takeover" -> {
                startTier2TakeoverForTest(90_000L)
                return true
            }
            // 后台原生承载的开关与手动触发。
            "carrier_on" -> {
                setCarrierEnabled(true)
                return true
            }
            "carrier_off" -> {
                setCarrierEnabled(false)
                return true
            }
            // 多工作区覆盖（docs/17 的方向）：默认关，验证通过再谈改默认。
            "multi_ws_on" -> {
                multiWorkspaceCoverage = true
                Diagnostics.log(
                    "warn",
                    "多工作区覆盖：已开启（page ∪ 在跑任务，上限 $MULTI_WS_COVERAGE_CAP）",
                )
                return true
            }
            "multi_ws_off" -> {
                multiWorkspaceCoverage = false
                Diagnostics.log("warn", "多工作区覆盖：已关闭（只开页面工作区）")
                return true
            }
            "carrier_now" -> {
                Diagnostics.log("warn", "后台原生承载（adb 手动触发）")
                val creds = relayCreds
                if (creds == null) {
                    Diagnostics.log("warn", "后台原生承载：凭证未就绪")
                } else {
                    val age = lastInboundAgeMs()
                    val targets = carrierCoverageTargets()
                    Diagnostics.log(
                        "warn",
                        "后台原生承载：手动接管（页面入站帧 " +
                            (if (age == null) "未知" else "${age / 1000}s 前") + "，" +
                            (if (targets.isEmpty()) "覆盖=页面工作区" else "覆盖=${targets.joinToString()}") + "）",
                    )
                    carrierStarted = true
                    acquireCarrierWakeLock()
                    Tier2Probe.start(
                        creds,
                        durationMs = 0L,
                        onlyWorkspace = pageWorkspaceKey,
                        onlyTaskId = pageWorkspaceTaskId,
                        coverageWorkspaces = targets,
                    )
                    startLiveProgressPolling()
                }
                return true
            }
            "nudge_now" -> {
                Diagnostics.log("warn", "恢复推动（adb 手动触发）")
                nudgePageRecovery("adb", "event")
                return true
            }
            "nudge_close" -> {
                Diagnostics.log("warn", "恢复推动（adb 手动触发，close 档）")
                nudgePageRecovery("adb-close", "close")
                return true
            }
            "net_probe" -> {
                nativeNetProbe("adb")
                return true
            }
            // WebView 远程调试（CDP）开关：验收"造流"要用它——adb 的 input text 进不了
            // WebView 的输入框（真机实测两次），而 CDP 的 Input.insertText 是渲染器认的
            // 真实输入。这是**测试通道**，用完记得关（设置页里也有同一个开关）。
            "wvdebug_on" -> {
                try {
                    prefs.webViewDebugging = true
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                    Diagnostics.log(
                        "warn",
                        "WebView 远程调试已开启（CDP）——adb forward 后可用 tools/cdp.mjs 驱动页面",
                    )
                } catch (e: Exception) {
                    Diagnostics.log("warn", "开启 WebView 远程调试失败: ${e.message}")
                }
                return true
            }
            "wvdebug_off" -> {
                try {
                    prefs.webViewDebugging = false
                    Diagnostics.log("warn", "WebView 远程调试开关已关闭（重启应用后生效）")
                } catch (e: Exception) {
                    Diagnostics.log("warn", "关闭 WebView 远程调试失败: ${e.message}")
                }
                return true
            }
            "stall_state" -> {
                val age = lastInboundAgeMs()
                Diagnostics.log(
                    "info",
                    "后台承载状态：开关=$carrierEnabled 已接管=$carrierStarted " +
                        "Tier2在跑=${Tier2Probe.isRunning()} " +
                        "多工作区=$multiWorkspaceCoverage 覆盖=${carrierCoverageTargets().joinToString()} " +
                        "入站帧=${if (age == null) "未知" else "${age / 1000}s 前"}",
                )
                return true
            }
        }
        return false
    }

    /**
     * 把指令转给注入层（`__zcodeShellDiag`）。注入脚本要等页面 boot 完才就绪，
     * 所以沿用 MainActivity 的重试口径（最多 40 次 × 600ms）。
     */
    fun dispatchJsDiag(cmd: String, attempt: Int = 0) {
        if (jsEvaluator == null) {
            Diagnostics.log("warn", "诊断指令无处执行（JS 求值通道未就绪）：$cmd")
            return
        }
        if (!isInjectedReady()) {
            if (attempt >= 40) {
                Diagnostics.log("warn", "放弃诊断指令（注入脚本未就绪）：$cmd")
                return
            }
            mainHandler.postDelayed({ dispatchJsDiag(cmd, attempt + 1) }, 600L)
            return
        }
        evaluateJs(
            "window.__zcodeShellDiag && window.__zcodeShellDiag(" + JSONObject.quote(cmd) + ");",
        )
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

    /** Tier1 静默看门狗：每 5s 一跳，前台/后台/熄屏都在岗。 */
    private val tier1Watchdog = object : Runnable {
        override fun run() {
            try {
                val away = userIsAway()
                if (away && !lastAwayState) {
                    evaluateJs("window.__zcodeShellSetAppForeground && window.__zcodeShellSetAppForeground(false);")
                    startHeartbeatPump()
                    requestLivenessReport()
                } else if (!away && lastAwayState) {
                    evaluateJs("window.__zcodeShellSetAppForeground && window.__zcodeShellSetAppForeground(true);")
                    if (Tier2Probe.isRunning()) {
                        Tier2Probe.stop("亮屏/回前台交还")
                    }
                    // 亮屏（应用可能仍在后台）：承载停了就得把 wake lock 与状态一起清掉，
                    // 否则锁会一直 held 到下一次进前台（耗电）。
                    stopLiveProgressPolling()
                    releaseCarrierWakeLock()
                    carrierStarted = false
                    stopHeartbeatPump()
                }
                lastAwayState = away
            } catch (e: Exception) {
                Diagnostics.log("warn", "Tier1 看门狗异常: ${e.message}")
            }
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    fun startTier1Watchdog() {
        mainHandler.removeCallbacks(tier1Watchdog)
        mainHandler.postDelayed(tier1Watchdog, 5_000L)
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
        val c = relayCreds ?: run {
            Diagnostics.log("warn", "Tier2: 凭证未就绪，无法启动接管验证")
            return
        }
        Tier2Probe.startTakeoverForTest(c, autoStopMs)
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
                "convtext" -> {
                    // 网页自己那条对话流里的"最新一段 AI 正文"——注入层从**页面自带的**
                    // 订阅里解出来（见 zcode-protocol.js 的 _trackConversationText）。
                    // 它走的是页面那条 socket，所以前后台都能用，也不涉及第二条连接
                    // → 不会再触发单控制端互斥（KICKED）。会话索引的 preview 只在
                    // 轮次边界变，流式进度只能靠这条补上。
                    val data = root.optJSONObject("data") ?: return
                    val text = data.optString("text")
                    if (text.isEmpty()) return
                    val sessionId = data.optString("topic").substringAfterLast('/')
                    if (sessionId.isEmpty()) return
                    onConversationText(sessionId, text)
                }
                "pagews" -> {
                    // 页面**自己**正在显示的工作区（来自它自己的 workspace-bridge-open 帧）。
                    // 后台原生承载只该为这一个开桥：真机 2026-09-16 定案——扫全部工作区会让
                    // 桌面端把 host 收掉（docs/16 §8/§10）。这是凭证无关的键名，可以进日志。
                    val data = root.optJSONObject("data") ?: return
                    val key = data.optString("key")
                    if (key.isEmpty()) return
                    pageWorkspaceKey = key
                    pageWorkspaceTaskId = data.optString("taskId")
                    Diagnostics.log("info", "页面当前工作区（原生承载的唯一目标）：$key")
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
                    // Tier2（原生直连 relay）凭证：注入层移交，仅驻内存。
                    // 任何日志路径都不得打印这些字段（sid/hash/mid 红线）。
                    val data = root.optJSONObject("data") ?: return
                    val wsUrl = data.optString("url")
                    val deviceSid = data.optString("deviceSid")
                    val passHash = data.optString("passHash")
                    if (wsUrl.isEmpty() || deviceSid.isEmpty() || passHash.isEmpty()) {
                        Diagnostics.log("warn", "Tier2: relaycreds 字段不全，忽略")
                        return
                    }
                    if (Tier2Probe.isRunning()) {
                        Diagnostics.log("debug", "Tier2: 接管期间忽略页面凭证刷新")
                        return
                    }
                    relayCreds = RelayCreds(
                        wsUrl = wsUrl,
                        deviceSid = deviceSid,
                        passHash = passHash,
                        deviceMid = data.optString("deviceMid").ifBlank { null },
                    )
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
                    body = com.zcode.remote.core.NotifyState.formatBody(
                        task.preview,
                        workspace.title,
                    ),
                    activityAt = task.lastActivityAt,
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
