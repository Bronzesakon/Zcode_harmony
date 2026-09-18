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
     * 会话流推出的运行态（`turnHeader.state`）——controller 流拿不到时的兜底。
     *
     * 优先级：controller 覆盖 > 会话流运行态 > sessions-index 持久态。
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

    @get:Synchronized
    val hasRunningTasks: Boolean
        get() = workspaces.values.any { runningIn(it).isNotEmpty() }

    /** 正在跑的任务（工作区键、会话 id）——原生拉取对话详情的清单。 */
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
        return buildUpdate(NotifyUpdate(running = emptyList(), completed = emptyList(), attention = emptyList()))
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
     */
    @Synchronized
    fun applyWorkspace(
        key: String,
        title: String,
        path: String,
        identity: String,
        source: String,
        tasks: List<TaskSnapshot>,
        nowMs: Long = System.currentTimeMillis(),
    ): Update {
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
            tasks = effectiveTasks(key, tasks),
        )
        return recompute(nowMs)
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
                tasks = effectiveTasks(workspace.key, persistedTasks[workspace.key].orEmpty()),
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
                tasks = effectiveTasks(key, emptyList()),
            )
        }
        return recompute(nowMs)
    }

    /** 合成一个工作区的任务表：SI 持久态 + controller/会话流运行态覆盖。 */
    private fun effectiveTasks(key: String, persisted: List<TaskSnapshot>): List<TaskSnapshot> {
        if (livePhases.isEmpty() && conversationPhases.isEmpty()) return persisted
        val merged = ArrayList<TaskSnapshot>(persisted.size + 4)
        val seen = HashSet<String>(persisted.size)
        for (task in persisted) {
            seen.add(task.sessionId)
            val overlay = phaseOverlay(key, task.sessionId)
            merged.add(if (overlay == null) task else task.copy(phase = overlay))
        }
        for (task in livePhases.keys + conversationPhases.keys) {
            if (task.workspaceKey != key || task.sessionId in seen) continue
            merged.add(
                TaskSnapshot(
                    sessionId = task.sessionId,
                    title = liveTitles[task].orEmpty(),
                    phase = phaseOverlay(key, task.sessionId).orEmpty(),
                    preview = "",
                    pendingInteractionId = "",
                    lastActivityAt = 0L,
                    hasBackgroundWork = false,
                )
            )
        }
        return merged
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
        if (siAt >= liveAt && siAt >= convAt) return null
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
        val phase = if (running) "running" else "completedSuccess"
        // 时刻先盖：**相位没变也是一次"这条源还在说话"的报到**。少了这一句，一条仍在
        // 流动的会话流会被一份后来到达、但内容更旧的持久态报告盖掉。
        conversationPhasesAt[id] = nowMs
        if (conversationPhases[id] == phase) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        conversationPhases[id] = phase
        for (workspace in workspaces.values.toList()) {
            workspaces[workspace.key] = workspace.copy(
                tasks = effectiveTasks(workspace.key, persistedTasks[workspace.key].orEmpty()),
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
            NotifyUpdate(emptyList(), completed, attention),
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

    /** Drops a workspace entirely, e.g. when its bridge is gone for good. */
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
        return buildUpdate(NotifyUpdate(running = emptyList(), completed = emptyList(), attention = emptyList()))
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
}
