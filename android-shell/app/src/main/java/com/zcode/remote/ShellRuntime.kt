package com.zcode.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.Prefs
import com.zcode.remote.core.SurvivalVerdict
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

    /** How often the pump drives one heartbeat tick; inside the desktop's 30s ack window. */
    private const val PUMP_INTERVAL_MS = 15_000L

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
                    body = com.zcode.remote.core.NotifyState.formatBody(status, task.preview),
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
