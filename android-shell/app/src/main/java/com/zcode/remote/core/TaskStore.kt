package com.zcode.remote.core

/**
 * Holds the current task list of every subscribed workspace and turns stream
 * updates into the exact set of notification changes the shell must apply.
 *
 * This is the layer between "raw protocol frames" and "notifications", and it is
 * intentionally free of Android types: the notification *decisions* (which task
 * is running, which just finished, what the body text says, which ids must be
 * cancelled) are unit tested, while [com.zcode.remote.notify.Notifier] only
 * renders them.
 */
class TaskStore {

    /** One workspace's last known state. */
    data class Workspace(
        val key: String,
        val title: String,
        /**
         * 来源三件套 `path` / `identity` / `source` —— **要留一起留、要删一起删**，
         * 绝不接受"三缺一"：它们是同一条协议快照的三个侧面，只删其中两条会留下一条
         * 没有同伴的半截身份，等真要用到时还得回去翻协议补。审计时别按"有没有读"逐个判。
         *
         * `source` 是**来源枚举**，三个取值各有出处：`"active"`（主动订阅，
         * `zcode-protocol.js` 里 `_emitSessions(..., 'active')` 那一支与 `RelayBridge`
         * 的 `onSessionsWire`）、`"passive"`（被动跟随，`zcode-protocol.js` 的 `_passive`
         * 分支）、`"controller"`（[applyLiveTasks] 为在跑任务凭空补出的工作区）。
         *
         * 诚实口径：**今天没有任何消费读点**——`source` 一次都没被读，`path` / `identity`
         * 则各只有**一处自读**，即 [applyWorkspace] 里"非空才覆盖"那句（拿上一份快照兜住
         * 这一帧缺字段，属于自保，不是消费）。所以它们看起来像死字段，其实是一条 **wire
         * 契约**：`source` 这个键两侧都有测试钉着（`RelayBridgeTest` 的
         * `snapshot builds an update sorted by last activity`、`tools/protocol.test.js` 的
         * `active mode opens one bridge per workspace and streams sessions`）。
         */
        val path: String,
        val identity: String,
        val source: String,
        val tasks: List<TaskSnapshot>,
    )

    /** A running task as the ongoing notification must render it (D8/D9). */
    data class RunningNotification(
        val id: Int,
        val workspaceKey: String,
        val workspaceTitle: String,
        val task: TaskSnapshot,
        val status: TaskStatus,
        val body: String,
        val activityAt: Long = task.lastActivityAt,
    ) {
        /**
         * The card's title row: `状态 · 任务名` (D15 — the status word is a prefix
         * of the title, not of the progress line, so the live progress keeps the
         * whole body of the card).
         */
        val title: String get() = NotifyState.formatTitle(status.label, task.displayTitle)

        /**
         * The task name as the *page* spells it, for the notification-tap locator.
         *
         * Kept separate from [title] on purpose, and it is the reason this property
         * exists at all: the locator matches on text found in the page, and the page
         * never renders our 状态 prefix. Handing it [title] made "tap the
         * notification to jump to the task" fail silently — found on the device on
         * 2026-09-12, not by any test.
         */
        val locateTitle: String get() = task.displayTitle
    }

    /** Everything that changed because of one update. */
    data class Update(
        val running: List<RunningNotification>,
        val removedIds: List<Int>,
        val completed: List<CompletionEvent>,
        val attention: List<AttentionEvent>,
        /**
         * Wall-clock ms at which [flushDueCompletions] must be called again, or 0
         * when nothing is waiting out an observation window. Without it a task that
         * ends and then goes quiet would never be announced: no further frames means
         * no further updates (see [NotifyState.nextCompletionDeadlineMs]).
         */
        val nextFlushAtMs: Long = 0L,
    )

    private val notifyState = NotifyState()
    private val workspaces = LinkedHashMap<String, Workspace>()
    private var previousRunningIds: Set<Int> = emptySet()

    /**
     * 4c 防降级（2026-09-18 · Q12/Q13/Q14/Q15）：**陈旧读数**与它的「可信窗口」。
     *
     * 背景：注入层跟随页面自己那条 `sessions-index` 订阅（被动路径）。一旦漏掉一个增量帧，
     * 那个工作区的读数就**不可信**了——被动路径在下一张整窗快照到来之前**一帧都不上报**
     * （`zcode-protocol.js` 的 `SessionsIndexState` 有意让 `seq` 停在缺口上），所以我们
     * 手里最后一份可信读数是**缺口之前**的。它可能"旧"（任务其实已结束），也可能"少"
     * （漏掉的正是"有新任务开始"）。用户的痛点是后者：壳以为没在跑 ⟹ 提前接管闸门关 ⟹
     * 后台卡片不更新（`ShellRuntime.maybeStartNativeCarrier` 的 `runningRefs.isEmpty()`）。
     *
     * 四个状态，**别把它们混成一个**：
     *   * [staleSince] —— **陈旧的事实**（工作区键 → 检出时刻）。随注入层的「仅标记帧」
     *     置位（[applyWorkspace] 的 `stale`/`staleOnly`），**只有下一次成功应用整窗快照**
     *     （普通帧落地）能清除，与注入层 `SessionsIndexState.stale` 同寿命。它是事实，
     *     **不随时间去伪**：陈旧就是陈旧。
     *   * [staleReadings] —— 该工作区**最近一次可信的在跑读数**，防降级要"顶回去"的那份。
     *     窗口内只增不减（见 [guardStaleDowngrade]）。
     *   * [backgrounded] —— **用户是否已离开**这个**状态**（由 [setBackgrounded] 同步，
     *     与接管闸门同源）。它是"要不要保护"的开关，不是"陈旧"这件事本身。
     *   * [trustDeadlineMs] —— **可信窗口**的截止时刻。锚点是**用户离开**（Q15：要不要接管
     *     是在离开后头十几秒判的；若从"检出"起算，用户先看十几分钟再退后台，保护就已经
     *     过期 ⟹ 几乎永远覆盖不到判断点）。长度 [STALE_TRUST_WINDOW_MS]（Q14 = 约 2 分钟）。
     *     过期即记一行日志、回到常规判定；回前台**提前收**。
     *
     * 两条保护**只在可信窗口内**生效：卡片/运行态（[guardStaleDowngrade]）与接管闸门
     * （[staleGateHold]）。
     */
    private val staleSince = HashMap<String, Long>()
    private val staleReadings = HashMap<String, List<TaskSnapshot>>()
    private var backgrounded = false
    private var trustDeadlineMs = 0L

    /**
     * 正处于"判完成前的观察窗"里的任务（见 [NotifyState.COMPLETION_HOLD_MS]）。
     *
     * 它们在相位上已经是终态，但**必须继续算作运行中**：卡片、活进展、订阅目标都按这个
     * 口径走。少了它，一轮结束到下一轮开始的 0.5s 缝就会撤卡（真机 2026-09-17 22:51:50）。
     */
    private val heldRunning = HashSet<LivePreviewKey>()

    /**
     * sessions-index 给的**持久态**任务表（每个工作区一份，`applyWorkspace` 写）。
     * 与运行态分开存，是因为两者的更新来源完全不同：SI 只在轮次边界变，
     * controller 流才是"此刻在跑"。
     */
    private val persistedTasks = HashMap<String, List<TaskSnapshot>>()

    /**
     * 运行态覆盖层（`controller/tasks-index` 的 `liveStatus`，键为工作区+会话）。
     *
     * 真机 2026-09-14 定案：SI 的 `phase` 在接管后把 store 覆盖成"全部完成"，
     * 而桌面端其实一直在推 controller 流——我们从没订过它，于是
     * `runningTaskRefs()` 变空、活进展被丢、流体云卡片冻死。
     */
    private val livePhases = HashMap<LivePreviewKey, String>()
    private val liveTitles = HashMap<LivePreviewKey, String>()

    /**
     * 每条源**最近一次报到**的时刻（2026-09-18）。
     *
     * 为什么需要：运行态有三条源，各自的盲区不同（见 [phaseOverlay] 的注释）。原来三者是
     * **固定优先级**（controller > 会话流 > SI），真机 2026-09-18 09:29:36 因此卡死一次：
     * 桌面端暂停任务，SI 当场报了 `completedInterrupted`，而两条"活跃"覆盖层都还停在
     * `running`（controller 那份是页面被顶掉之前的最后一份快照，会话流那份是暂停前一帧）
     * ——新消息被旧消息盖住，卡片永远显示"运行中"。
     * 有了时刻表，"谁后到谁算数"，三条源就变成了**互为冗余**而不是互相遮挡。
     */
    private val livePhasesAt = HashMap<LivePreviewKey, Long>()
    private val conversationPhasesAt = HashMap<LivePreviewKey, Long>()
    private val siPhasesAt = HashMap<LivePreviewKey, Long>()

    /**
     * 会话流推出的运行态（`turnHeader.state`）。
     *
     * 它是三条源**之一，不是"兜底"**：与 controller 覆盖层、页面自己的 controller 流平级，
     * 按"**谁后到谁算数**"参与判定（时刻表见 [conversationPhasesAt] / [livePhasesAt]），
     * 底下再垫 sessions-index 的持久态。
     * 它的盲区：按会话生效，只覆盖承载已经订阅的那些工作区——"任务正在**别的工作区**里跑"
     * 它天生看不见，那一格由页面自己的 controller 流补（见 [livePhases] 的注释）。
     * 为什么需要它：真机上 `subscribeControllerV4` 会超时（那条流看来由桌面端
     * 窗口进程提供，而原生接管正好把页面顶掉），只靠 SI 的持久态，卡片会冻死。
     */
    private val conversationPhases = HashMap<LivePreviewKey, String>()

    /**
     * M4：原生实拉的"对话详情"文本，按 sessionId 覆盖会话索引里的 preview。
     *
     * 为什么需要它：会话索引的 preview 语义是"最后一条消息的开头"，只在**轮次
     * 边界**才变，长轮次里它天然滞后几十分钟（2026-09-13 真机：流体云停在上一轮
     * 的开头，而任务正在做一大堆事）。而桌面端对远端的推送又是稀疏的（页面侧
     * 逐 10s 统计证实：拿到快照后整段只有心跳帧）。所以跟手只能靠原生主动拉
     * 尾窗，把最新一行（流式正文 / 正在跑的工具）喂到这里。
     *
     * 生命周期：接管期间由 [applyLivePreview] 写入，回前台交还时由
     * [clearLivePreviews] 清空（那时页面重新供数，以会话索引为准）。
     */
    private data class LivePreviewKey(val workspaceKey: String, val sessionId: String)

    private val livePreviews = HashMap<LivePreviewKey, String>()
    private val livePreviewAt = HashMap<LivePreviewKey, Long>()

    @Synchronized
    fun workspaces(): List<Workspace> = workspaces.values.toList()

    @Synchronized
    fun workspaceCount(): Int = workspaces.size

    /**
     * 正在跑的任务（工作区键、会话 id）——原生拉取对话详情的清单。
     *
     * 2026-09-18 审计：旁边原本还有个 `hasRunningTasks: Boolean`，内容只是这一份的
     * `.any { isNotEmpty() }`。同一件事给两个出口，迟早有一天两边口径分叉，遂删；
     * 要问"有没有在跑"请用 `runningTaskRefs().isNotEmpty()`。
     */
    @Synchronized
    fun runningTaskRefs(): List<Pair<String, String>> =
        workspaces.values.flatMap { ws -> runningIn(ws).map { ws.key to it.sessionId } }

    /**
     * 运行中＝相位是 `running`/`prewarming`，**或**该任务正处于判完成前的观察窗里。
     *
     * 第二项是 2026-09-17 加的：agent 一轮结束、下一轮 0.4–0.55s 后开始，相位会在那一瞬间
     * 变成终态。观察窗把它留在这里，卡片/正文/订阅才不会跟着抖（`docs/18` §3.10 ⑤）。
     */
    private fun runningIn(workspace: Workspace): List<TaskSnapshot> = workspace.tasks.filter {
        it.phase in NotifyState.RUNNING_PHASES || LivePreviewKey(workspace.key, it.sessionId) in heldRunning
    }

    /**
     * 覆盖某任务的"活进展"文案并重建通知。文本没变时返回空 Update（不打扰系统）。
     */
    @Synchronized
    fun applyLivePreview(
        workspaceKey: String,
        sessionId: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Update {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val key = LivePreviewKey(workspaceKey, sessionId)
        // 正文在流 ⇒ 这条会话**此刻活着**：给会话流那份运行态续期（见 [livePhasesAt]）。
        // 没有这一句，"正跑着但相位从没变过"的会话会被一份新到的持久态报告判成已结束。
        if (conversationPhases[key] == "running") conversationPhasesAt[key] = nowMs
        if (livePreviews[key] == trimmed) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        livePreviews[key] = trimmed
        livePreviewAt[key] = System.currentTimeMillis()
        return buildUpdate(NotifyUpdate(completed = emptyList(), attention = emptyList()))
    }

    /** 交还前台：活进展不再是数据源，交给页面自己的会话索引。 */
    @Synchronized
    fun clearLivePreviews() {
        livePreviews.clear()
        livePreviewAt.clear()
    }

    /**
     * Folds a fresh sessions-index snapshot of one workspace in and reports the
     * notification changes.
     *
     * [tasks] replaces the workspace's previous list entirely — the protocol
     * sends a full snapshot followed by deltas, and the JS side always hands us
     * a complete list, so absence means removal.
     *
     * 4c：多两个形参，默认值让**老调用点（含全部既有单测）语义不变**——缺字段就是
     * "不陈旧、可落地"，与"字段缺失时原生按不陈旧处理"那条向后兼容口径一致。
     *
     * @param stale 本帧自报"该工作区读数已陈旧"（注入层 `SessionsIndexState.stale`）。
     * @param staleOnly 注入层声明的帧形状：**仅标记帧**，`tasks` 只是冻结的旧读数，
     *   不是新读数（见 [staleSince]）。
     */
    @Synchronized
    fun applyWorkspace(
        key: String,
        title: String,
        path: String,
        identity: String,
        source: String,
        tasks: List<TaskSnapshot>,
        stale: Boolean = false,
        staleOnly: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Update {
        // 4c：**陈旧的读数永不落地。**
        //
        // 两个字段任一为真都走这一支：`stale` 是"读数不可信"这个事实，`staleOnly` 是
        // 注入层显式声明的帧形状（`RemoteClient._emitSessionsStale`）。宁可多护一层——
        // 只要漏掉这个分支，那一帧的冻结旧列表就会把该工作区的 SI 时刻戳整表重盖
        // （下面 `siPhasesAt` 那一段），让旧 SI 反过来压过 controller / 会话流覆盖层，
        // **自己制造一次本规则要防的降级**。
        if (stale || staleOnly) {
            markStale(key, nowMs)
            // 返回"什么都没变、但时限仍然诚实"的更新。**不能**返回四个空列表的 Update：
            // `ShellRuntime.applyUpdate` 会用 `nextFlushAtMs=0` 取消掉待办的完成回灌
            // （`scheduleCompletionFlush` 在"空更新不发布"那条早退**之前**执行）。
            return recompute(nowMs)
        }
        // 普通整表帧 = 缺口自愈（注入层只在成功应用过一帧之后才发它）⟹ 陈旧事实与
        // 可信窗口一并解除（Q15 第 5 条）。
        clearStale(key)
        val previous = workspaces[key]
        persistedTasks[key] = tasks
        // 这份快照是"此刻的 SI"：整表重新盖时间戳（旧会话的戳一并清掉，免得留下幽灵）。
        siPhasesAt.keys.removeIf { it.workspaceKey == key }
        for (task in tasks) siPhasesAt[LivePreviewKey(key, task.sessionId)] = nowMs
        // A changed title/scope must not lose the task list; a genuinely new
        // task list must not inherit stale phases, which NotifyState handles
        // per key anyway.
        workspaces[key] = Workspace(
            key = key,
            title = title.ifEmpty { previous?.title ?: key },
            path = path.ifEmpty { previous?.path ?: "" },
            identity = identity.ifEmpty { previous?.identity ?: "" },
            source = source,
            tasks = effectiveTasks(key, tasks, nowMs),
        )
        return recompute(nowMs)
    }

    // ---------------------------------------------------------------- 4c 防降级
    //
    // 这一段就是"陈旧读数"的全部对外面：一个**状态**（用户是否已离开）+ 两个**查询**（闸门）。
    // 规则与状态见 [staleSince] 的注释；两条保护见 [guardStaleDowngrade] 与 [staleGateHold]。

    /**
     * **用户是否已离开**的状态同步（可信窗口的锚点，Q15 + 用户追加裁定）。
     *
     * 调用方是壳：`ShellRuntime` 把**闸门自己那条判据** [ShellRuntime.userIsAway]
     * （应用在后台**或**屏幕已熄灭）的结果同步进来——三处调用点（生命周期回调 / 5s 看门狗 /
     * 闸门入口）全部取自同一个函数，所以 store 的"已离开"与闸门的"已离开"**永远同源**，
     * 不会出现"窗口武装了但闸门根本不在判定"的错位。TaskStore 自己**不猜**前台状态。
     *
     * ⚠️ 为什么是**状态**而不是事件（用户 2026-09-18 追加裁定）：两种先后顺序都要覆盖——
     *   ① 离开时已有陈旧记录 ⟹ 状态**变为"已离开"**那一拍武装（下面的 `leftNow` 分支）；
     *   ② **离开之后才检出缺口** ⟹ 那一刻已在"已离开"态，由 [markStale] 当场武装
     *      （只做事件式打点的话，这一半**完全没有保护**）。
     *
     * 解除：
     *   * 状态变回**未离开**（回前台/亮屏）⟹ **立即清掉窗口**（提前收；再次离开会重新武装）；
     *   * 整窗快照到达 ⟹ 陈旧事实与窗口一并清（见 [clearStale]）。
     *
     * 重复同步**不会续期**：同一次"已离开"里反复调用（看门狗每 5s 一次）一律不重新武装，
     * 否则窗口永远不会过期，Q12/Q14 的"带保质期"就名存实亡。
     */
    @Synchronized
    fun setBackgrounded(backgrounded: Boolean, nowMs: Long = System.currentTimeMillis()) {
        sweepTrustWindow(nowMs)
        if (!backgrounded) {
            val hadWindow = trustDeadlineMs > 0L
            val wasAway = this.backgrounded
            trustDeadlineMs = 0L
            this.backgrounded = false
            if (wasAway && hadWindow) {
                Diagnostics.log(
                    "info",
                    "陈旧读数可信窗口：回到前台，提前收掉保护" +
                        "（仍有 ${staleSince.size} 个陈旧工作区（${staleWorkspaceText()}）等整窗快照自愈）",
                )
            }
            return
        }
        val leftNow = !this.backgrounded
        this.backgrounded = true
        // 只认**离开那一拍**：同一次"已离开"里的重复同步绝不续期（否则窗口永不过期）。
        if (!leftNow) return
        // 这一拍没有陈旧读数就没有可保护的东西；"离开之后才检出"那一半由 [markStale] 接手。
        if (staleSince.isEmpty()) return
        armTrustWindow(nowMs, "退后台")
    }

    /**
     * 武装可信窗口：[nowMs] 起 [STALE_TRUST_WINDOW_MS] 内，该工作区集合受两条保护。
     *
     * 两处调用（都是"离开"侧的事实）：[setBackgrounded] 的离开那一拍、[markStale] 的
     * "检出时已在后台"。原因串进日志，真机上分得清是哪一半救的场。
     */
    private fun armTrustWindow(nowMs: Long, reason: String) {
        trustDeadlineMs = nowMs + STALE_TRUST_WINDOW_MS
        Diagnostics.log(
            "info",
            "陈旧读数可信窗口：$reason —— ${staleSince.size} 个陈旧工作区" +
                "（${staleWorkspaceText()}），此后 ${STALE_TRUST_WINDOW_MS / 1000}s 内" +
                "保护它们的运行读数与接管闸门",
        )
    }

    /**
     * 提前接管闸门的查询（Q13 第二句）：**当前是否存在处于可信窗口内的陈旧工作区**。
     *
     * 闸门原来的判据是"有没有在跑任务"（`ShellRuntime.maybeStartNativeCarrier` 的
     * `runningRefs.isEmpty()`）。陈旧工作区恰好能让这个判据失真——缺口吞掉的正是
     * "有新任务开始"这一条。所以窗口内**不许仅凭"没有在跑任务"关闸**。
     *
     * 报告 §4.5 的复核结论：**这条（第二句）才是真正对准用户痛点的保护**——见
     * `BATCH4-REPORT-4c-phaseA2.md`。
     */
    @Synchronized
    fun hasStaleWorkspaceInTrustWindow(nowMs: Long = System.currentTimeMillis()): Boolean =
        staleGateHold(nowMs).holds

    /**
     * [hasStaleWorkspaceInTrustWindow] 的详细版：判定 + 供日志用的工作区键与剩余时间。
     *
     * 纯查询：唯一的副作用是"可信窗口到期那一拍"顺手记一行日志（见 [sweepTrustWindow]），
     * 且**每过期一次只记一行**。
     */
    @Synchronized
    fun staleGateHold(nowMs: Long = System.currentTimeMillis()): StaleGateHold {
        sweepTrustWindow(nowMs)
        val holds = trustDeadlineMs > 0L && staleSince.isNotEmpty()
        return StaleGateHold(
            holds = holds,
            workspaceKeys = if (holds) staleSince.keys.toList() else emptyList(),
            remainingMs = if (trustDeadlineMs > 0L) (trustDeadlineMs - nowMs).coerceAtLeast(0L) else 0L,
        )
    }

    /** [staleGateHold] 的结果：闸门是否因陈旧而放行 + 日志要用的细节。 */
    data class StaleGateHold(
        val holds: Boolean,
        val workspaceKeys: List<String>,
        val remainingMs: Long,
    )

    /**
     * 可信窗口**过期**的那一拍：丢弃保护、**记一行日志**（真机回归判据，grep `陈旧读数`），
     * 然后回到常规判定。
     *
     * 幂等：过期之后 [trustDeadlineMs] 归零，不会再记第二行；缺口没自愈（[staleSince]
     * 仍有内容）也不会让它复活——只有下一次"退后台"才会重新武装，这正是 Q15 的锚点。
     */
    private fun sweepTrustWindow(nowMs: Long) {
        val deadline = trustDeadlineMs
        if (deadline == 0L || nowMs <= deadline) return
        trustDeadlineMs = 0L
        Diagnostics.log(
            "warn",
            "陈旧读数的可信窗口已过期（${STALE_TRUST_WINDOW_MS / 1000}s，锚点=退后台）：" +
                "丢弃保护、回到常规判定；仍有 ${staleSince.size} 个陈旧工作区" +
                "（${staleWorkspaceText()}）等下一次整窗快照自愈",
        )
        // 保护撤掉之后，被顶回去的在跑相位必须**立刻**回到常规判定：不然那张被保护的表
        // 会一直躺在 [workspaces] 里，直到下一次 applyLiveTasks 才被重算（真机上可能很久）。
        // 只碰陈旧工作区，别的工作区一行不动。
        for (key in staleSince.keys.toList()) {
            val workspace = workspaces[key] ?: continue
            val tasks = effectiveTasks(key, persistedTasks[key].orEmpty(), nowMs)
            if (tasks != workspace.tasks) workspaces[key] = workspace.copy(tasks = tasks)
        }
    }

    /** 日志用的工作区清单（` | ` 分隔；空则 `-`）。 */
    private fun staleWorkspaceText(): String =
        if (staleSince.isEmpty()) "-" else staleSince.keys.joinToString(" | ")

    /**
     * 记下"该工作区读数陈旧"这个**事实**，并把当前在跑读数留作"最近一次可信读数"。
     *
     * 只在第一次置位时记日志（注入层也只在跃迁那一拍发「仅标记帧」，两边的去重口径相同）；
     * 重复标记**不覆盖**检出时刻——"自何时起陈旧"是事实，不该被后来的标记刷旧。
     *
     * 顺带承担 Q15 追加裁定的**第二臂**：检出这一刻若用户**已经离开**，当场武装可信窗口
     * （"离开那一拍"没有陈旧记录可武装，所以这一半只能在这里补）。
     */
    private fun markStale(key: String, nowMs: Long) {
        if (staleSince.containsKey(key)) return
        staleSince[key] = nowMs
        val workspace = workspaces[key]
        val running = workspace?.tasks?.filter { it.phase in NotifyState.RUNNING_PHASES }.orEmpty()
        rememberStaleReading(key, running)
        Diagnostics.log(
            "warn",
            "陈旧读数标记：$key 的会话清单漏过增量帧，此后该工作区的读数不可信" +
                "（等下一次整窗快照自愈）",
        )
        // Q15 追加裁定那一半：**检出这一刻用户已经离开** ⟹ 当场武装。
        // 少了这一句，"退后台之后才检出缺口"的情形**完全没有保护**（离开那一拍还没有
        // 陈旧记录，没有东西可武装，而离开事件也不会再来一次）。
        if (backgrounded) {
            armTrustWindow(nowMs, "检出缺口时已在后台")
        }
    }

    /**
     * 普通整表帧落地 = 缺口自愈（Q15 第 5 条）：陈旧事实与可信窗口一并清除。
     *
     * 最后一个陈旧工作区自愈时把窗口也收掉，免得闸门靠一个空窗口继续敞开。
     */
    private fun clearStale(key: String) {
        if (staleSince.remove(key) == null) return
        staleReadings.remove(key)
        if (staleSince.isEmpty()) trustDeadlineMs = 0L
        Diagnostics.log("info", "陈旧读数已自愈：$key 收到整窗快照，标记与可信窗口一并解除")
    }

    /** 该工作区此刻是否受保护：有陈旧**事实** **且** 可信窗口仍在。 */
    private fun isStaleProtected(key: String, nowMs: Long): Boolean {
        sweepTrustWindow(nowMs)
        return trustDeadlineMs > 0L && staleSince.containsKey(key)
    }

    /**
     * 4c 防降级第一条（Q13）：可信窗口内，陈旧工作区的**在跑读数只增不减**。
     *
     * "只增不减"就是这条保护的全部内容，两个方向各有出处：
     *   * **不减**（防降级）：缺口之前那份可信读数里在跑的任务，不许被抹空、也不许被翻成
     *     终态。真机 2026-09-14 的事故就是它——SI 的 `phase` 把 store 覆盖成"全部完成"，
     *     `runningTaskRefs()` 变空、活进展被丢、流体云卡片冻死。
     *   * **可增**（不挡好消息）：窗口内新报到的在跑任务照收。挡住它们等于把用户痛点的
     *     反面再造一遍——陈旧期间原生唯一可能拿到的新消息，正是"有新任务在跑"。
     *
     * 相位顶回去时沿用**当前**条目的其它字段（标题/正文/时刻都可能是新的），只把 `phase`
     * 换回可信读数的那一个；整条被抹掉的会话则整条补回（那时只有可信读数那一份可用）。
     */
    private fun guardStaleDowngrade(
        key: String,
        merged: List<TaskSnapshot>,
        nowMs: Long,
    ): List<TaskSnapshot> {
        if (!isStaleProtected(key, nowMs)) return merged
        val anchor = rememberStaleReading(
            key,
            merged.filter { it.phase in NotifyState.RUNNING_PHASES },
        )
        if (anchor.isEmpty()) return merged
        val present = HashSet<String>(merged.size)
        val out = ArrayList<TaskSnapshot>(merged.size + anchor.size)
        for (task in merged) {
            present.add(task.sessionId)
            val trusted = anchor.firstOrNull { it.sessionId == task.sessionId }
            out.add(
                if (trusted != null && task.phase !in NotifyState.RUNNING_PHASES) {
                    task.copy(phase = trusted.phase)
                } else {
                    task
                }
            )
        }
        for (task in anchor) if (task.sessionId !in present) out.add(task)
        return out
    }

    /**
     * 把窗口内看到的在跑任务并进"最近一次可信读数"（**只增不减**），返回当前锚。
     *
     * 为什么要滚动更新而不是只在置位那一拍拍一张快照：窗口内新开始的任务同样需要保护
     * （它下一次被"抹掉"时，只有这里还留着它）。并集只会随窗口内出现过的会话增长，
     * 有界（窗口 ≤2 分钟）。
     */
    private fun rememberStaleReading(
        key: String,
        runningNow: List<TaskSnapshot>,
    ): List<TaskSnapshot> {
        val previous = staleReadings[key].orEmpty()
        if (runningNow.isEmpty()) return previous
        val byId = LinkedHashMap<String, TaskSnapshot>()
        for (task in previous) byId[task.sessionId] = task
        for (task in runningNow) byId[task.sessionId] = task
        val merged = byId.values.toList()
        staleReadings[key] = merged
        return merged
    }

    /**
     * controller 流（运行态）整表落地：`liveStatus` 归一到壳侧 phase 词汇后覆盖
     * SI 的 `phase`，并给"正在跑但 SI 里还没有"的工作区补出任务条目。
     *
     * 整表替换（不是增量合并）：controller 快照本身就是全量，缺省即删除。
     */
    @Synchronized
    fun applyLiveTasks(
        tasks: List<ControllerTasksState.LiveTask>,
        nowMs: Long = System.currentTimeMillis(),
    ): Update {
        livePhases.clear()
        liveTitles.clear()
        livePhasesAt.clear()
        for (task in tasks) {
            val key = LivePreviewKey(task.workspaceKey, task.sessionId)
            livePhases[key] = task.phase
            liveTitles[key] = task.title
            livePhasesAt[key] = nowMs
        }
        for (workspace in workspaces.values.toList()) {
            workspaces[workspace.key] = workspace.copy(
                tasks = effectiveTasks(workspace.key, persistedTasks[workspace.key].orEmpty(), nowMs),
            )
        }
        // 只有"在跑/等待"的任务才值得为它凭空建一个工作区，否则光是索引里的
        // 历史会话就能把 store 撑满。
        val newcomers = LinkedHashMap<String, Boolean>()
        for (task in tasks) {
            if (task.phase in NotifyState.RUNNING_PHASES && task.workspaceKey !in workspaces) {
                newcomers[task.workspaceKey] = true
            }
        }
        for (key in newcomers.keys) {
            workspaces[key] = Workspace(
                key = key,
                title = workspaceTitleOf(key),
                path = "",
                identity = "",
                source = "controller",
                tasks = effectiveTasks(key, emptyList(), nowMs),
            )
        }
        return recompute(nowMs)
    }

    /**
     * 合成一个工作区的任务表：SI 持久态 + controller/会话流运行态覆盖。
     *
     * 4c：出口处还要过一道 [guardStaleDowngrade]（可信窗口内的陈旧工作区，在跑读数
     * 只增不减）。这是**唯一**的合成点，所以闸门读数、卡片、完成判定三条下游全都受它管。
     */
    private fun effectiveTasks(
        key: String,
        persisted: List<TaskSnapshot>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<TaskSnapshot> {
        val merged: List<TaskSnapshot> = if (livePhases.isEmpty() && conversationPhases.isEmpty()) {
            persisted
        } else {
            val out = ArrayList<TaskSnapshot>(persisted.size + 4)
            val seen = HashSet<String>(persisted.size)
            for (task in persisted) {
                seen.add(task.sessionId)
                val overlay = phaseOverlay(key, task.sessionId)
                out.add(if (overlay == null) task else task.copy(phase = overlay))
            }
            for (task in livePhases.keys + conversationPhases.keys) {
                if (task.workspaceKey != key || task.sessionId in seen) continue
                out.add(
                    TaskSnapshot(
                        sessionId = task.sessionId,
                        title = liveTitles[task].orEmpty(),
                        phase = phaseOverlay(key, task.sessionId).orEmpty(),
                        preview = "",
                        pendingInteractionId = "",
                        lastActivityAt = 0L,
                    )
                )
            }
            out
        }
        return guardStaleDowngrade(key, merged, nowMs)
    }

    /** 三级判定：**最新报到的那条源说了算**（见 [livePhasesAt]）；没有覆盖层就用 SI 的持久态。 */
    private fun phaseOverlay(key: String, sessionId: String): String? {
        val id = LivePreviewKey(key, sessionId)
        val live = livePhases[id]
        val conv = conversationPhases[id]
        val liveAt = if (live == null) -1L else livePhasesAt[id] ?: 0L
        val convAt = if (conv == null) -1L else conversationPhasesAt[id] ?: 0L
        val siAt = siPhasesAt[id] ?: -1L
        // SI 的相位在 base（persistedTasks）里，所以"返回 null"就是"用 SI"。
        if (liveAt < 0L && convAt < 0L) return null
        // **SI 只在严格更新时说了算**：同一毫秒内两条源都报到（单测里极常见，真机也可能）
        // 时让位给更具体的活跃源——覆盖层存在的理由正是"SI 的相位是持久态"。
        if (siAt > liveAt && siAt > convAt) return null
        return if (liveAt >= convAt) live else conv
    }

    /**
     * 会话流推出的运行态落地（会话 id 维度，整表替换由调用方保证）。
     */
    @Synchronized
    fun applyConversationRunState(
        workspaceKey: String,
        sessionId: String,
        running: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Update {
        val id = LivePreviewKey(workspaceKey, sessionId)
        // 会话流只说"停了"，**不说"怎么停的"**：`turnHeader.state` 的结束既可能是正常完成，
        // 也可能是用户中断 / 失败。所以"停止"这一拍要**保留 SI 已经给出的终态相位**
        // （`completedInterrupted` / `error`…），否则中断会被写成「已完成」——
        // 真机 2026-09-18 用户当场指出：他点的"中断"，卡片却宣布"已完成"。
        val phase = if (running) {
            "running"
        } else {
            val siPhase = persistedTasks[workspaceKey]
                ?.firstOrNull { it.sessionId == sessionId }
                ?.phase
            if (siPhase != null && siPhase in NotifyState.TERMINAL_PHASES) siPhase else "completedSuccess"
        }
        // 时刻先盖：**相位没变也是一次"这条源还在说话"的报到**。少了这一句，一条仍在
        // 流动的会话流会被一份后来到达、但内容更旧的持久态报告盖掉。
        conversationPhasesAt[id] = nowMs
        if (conversationPhases[id] == phase) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        conversationPhases[id] = phase
        for (workspace in workspaces.values.toList()) {
            workspaces[workspace.key] = workspace.copy(
                tasks = effectiveTasks(workspace.key, persistedTasks[workspace.key].orEmpty(), nowMs),
            )
        }
        return recompute(nowMs)
    }

    /** controller 只给了路径时的兜底标题：取路径末段（与 SI 的 title 规则一致）。 */
    private fun workspaceTitleOf(key: String): String {
        val parts = key.split('/', '\\').filter { it.isNotEmpty() }
        return parts.lastOrNull() ?: key
    }

    /**
     * 从当前 [workspaces] 重新推一遍通知状态。
     *
     * 两个数据源（SI / controller）最终都汇到这里，所以完成卡片、运行卡片、
     * 关注请求都只按"最终相位的转移"判定——不会因为来源不同而重复或漏发。
     */
    private fun recompute(nowMs: Long = System.currentTimeMillis()): Update {
        val completed = ArrayList<CompletionEvent>()
        val attention = ArrayList<AttentionEvent>()
        val held = HashSet<LivePreviewKey>()
        for (workspace in workspaces.values) {
            val notify = notifyState.apply(workspace.key, workspace.tasks, nowMs)
            for (sessionId in notify.heldRunning) held.add(LivePreviewKey(workspace.key, sessionId))
            for (event in notify.completed) {
                completed.add(
                    event.copy(
                        finalPreview = livePreviews[LivePreviewKey(workspace.key, event.task.sessionId)]
                            ?: event.task.preview,
                    )
                )
            }
            attention.addAll(notify.attention)
        }
        heldRunning.clear()
        heldRunning.addAll(held)
        forgetStaleLivePreviews()
        return buildUpdate(
            NotifyUpdate(completed = completed, attention = attention),
            notifyState.nextCompletionDeadlineMs(),
        )
    }

    /**
     * 回灌一次"观察窗到期"：任务结束后不再有新帧，完成事件只能靠这一拍送出来
     * （调用方按 [Update.nextFlushAtMs] 定时调用）。
     */
    @Synchronized
    fun flushDueCompletions(nowMs: Long = System.currentTimeMillis()): Update = recompute(nowMs)

    /**
     * 任务离开运行集就不该再留着活进展：否则同一 sessionId 下次再跑起来时，
     * 旧进度会先顶掉会话索引给的新文案（M4 的活进展只对"正在跑"有意义）。
     */
    private fun forgetStaleLivePreviews() {
        if (livePreviews.isEmpty()) return
        val running = HashSet<LivePreviewKey>()
        for (workspace in workspaces.values) {
            for (task in runningIn(workspace)) running.add(LivePreviewKey(workspace.key, task.sessionId))
        }
        livePreviews.keys.retainAll(running)
        livePreviewAt.keys.retainAll(running)
    }

    /**
     * Drops a workspace entirely — its tasks, its phase history and every
     * override layer that carried its name.
     *
     * 2026-09-18 审计：**保留**，别因为"生产没人调"就删。它是"一把还没有探测器的工具"：
     * [workspaces] / [persistedTasks] / [NotifyState] / 各覆盖层与时间戳表、活进展，
     * 一次清干净，语义是完整的；生产侧今天确实**没有调用方**，唯一的调用者是单测
     * （`NotifyStateTest` 的 `removing a workspace cancels its running notifications`）。
     * 这不叫死接线——死接线是"信号有了、没人接"，这里差的是一个**判定工作区真的没了**的信号。
     *
     * ⚠️ **绝不可**把它接到"轮换回收桥"上。桥是每个工作区一条**数据管道**，轮换是调度事件，
     * **与任务是否完成无关**：在轮换那一拍按桥回收工作区，正在跑的任务会被整条抹掉、
     * 下一帧再长回来——用户看到的就是卡片**闪断**（消失又出现）。这和
     * [NotifyState.COMPLETION_HOLD_MS] 那个观察窗防的是同一类抖动，只是那一次缝是 0.5s，
     * 这一次会是整条管线的重建。
     *
     * 工作区级"彻底消失"今天只有一处兜底：网页重载时的全局 [reset]（整表清空，不会留残渣）。
     * 将来真要接探测器，信号必须是"这个工作区已从 sessions-index 里消失**且不再回来**"，
     * 而不是桥的生命周期。
     */
    @Synchronized
    fun removeWorkspace(key: String): Update {
        workspaces.remove(key)
        persistedTasks.remove(key)
        notifyState.forget(key)
        heldRunning.removeIf { it.workspaceKey == key }
        livePhasesAt.keys.removeIf { it.workspaceKey == key }
        conversationPhasesAt.keys.removeIf { it.workspaceKey == key }
        siPhasesAt.keys.removeIf { it.workspaceKey == key }
        livePhases.keys.removeIf { it.workspaceKey == key }
        liveTitles.keys.removeIf { it.workspaceKey == key }
        conversationPhases.keys.removeIf { it.workspaceKey == key }
        livePreviews.keys.removeIf { it.workspaceKey == key }
        livePreviewAt.keys.removeIf { it.workspaceKey == key }
        // 4c：工作区整条消失，陈旧事实与它那份可信读数也跟着走（别留着一个保护不了的标记）。
        staleSince.remove(key)
        staleReadings.remove(key)
        if (staleSince.isEmpty()) trustDeadlineMs = 0L
        return buildUpdate(NotifyUpdate(completed = emptyList(), attention = emptyList()))
    }

    @Synchronized
    fun reset(): Update {
        workspaces.clear()
        persistedTasks.clear()
        livePhases.clear()
        liveTitles.clear()
        conversationPhases.clear()
        notifyState.reset()
        heldRunning.clear()
        livePhasesAt.clear()
        conversationPhasesAt.clear()
        siPhasesAt.clear()
        livePreviews.clear()
        livePreviewAt.clear()
        staleSince.clear()
        staleReadings.clear()
        trustDeadlineMs = 0L
        // [backgrounded] **有意不在这里清**：它记的是"用户是否已离开"这个壳侧事实，
        // 与页面重载无关。留着它，重载后若在后台又检出缺口，第二臂照样能武装。
        val removed = previousRunningIds.toList()
        previousRunningIds = emptySet()
        return Update(running = emptyList(), removedIds = removed, completed = emptyList(), attention = emptyList())
    }

    private fun buildUpdate(notify: NotifyUpdate, nextFlushAtMs: Long = 0L): Update {
        val running = buildRunningList()
        val nowIds = running.mapTo(HashSet()) { it.id }
        val removed = previousRunningIds.filterNot { it in nowIds }
        previousRunningIds = nowIds
        return Update(
            running = running,
            removedIds = removed,
            completed = notify.completed,
            attention = notify.attention,
            nextFlushAtMs = nextFlushAtMs,
        )
    }

    /**
     * 运行卡片清单（与 [Update.running] 同一套口径）。
     *
     * 壳侧前台服务通知的那份文案也从这里取——曾经它自己又推了一遍，既漏了活进展，
     * 又和这里"谁算运行中"（观察窗）分叉，2026-09-17 合并成一个来源。
     */
    @Synchronized
    fun runningNotifications(): List<RunningNotification> = buildRunningList()

    private fun buildRunningList(): List<RunningNotification> {
        val running = ArrayList<RunningNotification>()
        for (workspace in workspaces.values) {
            for (task in runningIn(workspace)) {
                running.add(
                    RunningNotification(
                        id = NotifyState.notificationIdFor(workspace.key, task.sessionId),
                        workspaceKey = workspace.key,
                        workspaceTitle = workspace.title,
                        task = task,
                        status = notifyState.statusOf(task),
                        body = NotifyState.formatBody(
                            livePreviews[LivePreviewKey(workspace.key, task.sessionId)] ?: task.preview,
                            workspace.title,
                        ),
                        activityAt = livePreviewAt[LivePreviewKey(workspace.key, task.sessionId)]
                            ?: task.lastActivityAt,
                    )
                )
            }
        }
        return running
    }

    companion object {
        /**
         * 「可信窗口」长度：**2 分钟**（2026-09-18 · Q14/Q15）。
         *
         * 用户明确选最短档，理由是两向风险相反、取折中：窗口越长，"误接管把页面顶掉"
         * （`ShellRuntime.maybeStartNativeCarrier` 那条提前接管会 KICK 掉页面）的窗口越长；
         * 窗口越短，"隔得久就保护不到"越可能出现。**度量起点是"应用退到后台"那一刻**
         * （[onAppBackground]），不是"检出漏增量"那一刻——接管与否是在退后台后头十几秒
         * 判的，从检出起算几乎永远覆盖不到判断点。
         */
        const val STALE_TRUST_WINDOW_MS = 120_000L
    }
}
