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
    ) {
        val running: List<TaskSnapshot> get() = tasks.filter { it.phase in NotifyState.RUNNING_PHASES }
    }

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
    )

    private val notifyState = NotifyState()
    private val workspaces = LinkedHashMap<String, Workspace>()
    private var previousRunningIds: Set<Int> = emptySet()

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
        get() = workspaces.values.any { it.running.isNotEmpty() }

    /** 正在跑的任务（工作区键、会话 id）——原生拉取对话详情的清单。 */
    @Synchronized
    fun runningTaskRefs(): List<Pair<String, String>> =
        workspaces.values.flatMap { ws -> ws.running.map { ws.key to it.sessionId } }

    /**
     * 覆盖某任务的"活进展"文案并重建通知。文本没变时返回空 Update（不打扰系统）。
     */
    @Synchronized
    fun applyLivePreview(workspaceKey: String, sessionId: String, text: String): Update {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val key = LivePreviewKey(workspaceKey, sessionId)
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
    ): Update {
        val previous = workspaces[key]
        persistedTasks[key] = tasks
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
        return recompute()
    }

    /**
     * controller 流（运行态）整表落地：`liveStatus` 归一到壳侧 phase 词汇后覆盖
     * SI 的 `phase`，并给"正在跑但 SI 里还没有"的工作区补出任务条目。
     *
     * 整表替换（不是增量合并）：controller 快照本身就是全量，缺省即删除。
     */
    @Synchronized
    fun applyLiveTasks(tasks: List<ControllerTasksState.LiveTask>): Update {
        livePhases.clear()
        liveTitles.clear()
        for (task in tasks) {
            val key = LivePreviewKey(task.workspaceKey, task.sessionId)
            livePhases[key] = task.phase
            liveTitles[key] = task.title
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
        return recompute()
    }

    /** 合成一个工作区的任务表：SI 持久态 + controller 运行态覆盖。 */
    private fun effectiveTasks(key: String, persisted: List<TaskSnapshot>): List<TaskSnapshot> {
        if (livePhases.isEmpty()) return persisted
        val merged = ArrayList<TaskSnapshot>(persisted.size + 4)
        val seen = HashSet<String>(persisted.size)
        for (task in persisted) {
            seen.add(task.sessionId)
            val live = livePhases[LivePreviewKey(key, task.sessionId)]
            merged.add(if (live == null) task else task.copy(phase = live))
        }
        for ((liveKey, phase) in livePhases) {
            if (liveKey.workspaceKey != key || liveKey.sessionId in seen) continue
            merged.add(
                TaskSnapshot(
                    sessionId = liveKey.sessionId,
                    title = liveTitles[liveKey].orEmpty(),
                    phase = phase,
                    preview = "",
                    pendingInteractionId = "",
                    lastActivityAt = 0L,
                    hasBackgroundWork = false,
                )
            )
        }
        return merged
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
    private fun recompute(): Update {
        val completed = ArrayList<CompletionEvent>()
        val attention = ArrayList<AttentionEvent>()
        for (workspace in workspaces.values) {
            val notify = notifyState.apply(workspace.key, workspace.tasks)
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
        forgetStaleLivePreviews()
        return buildUpdate(NotifyUpdate(emptyList(), completed, attention))
    }

    /**
     * 任务离开运行集就不该再留着活进展：否则同一 sessionId 下次再跑起来时，
     * 旧进度会先顶掉会话索引给的新文案（M4 的活进展只对"正在跑"有意义）。
     */
    private fun forgetStaleLivePreviews() {
        if (livePreviews.isEmpty()) return
        val running = HashSet<LivePreviewKey>()
        for (workspace in workspaces.values) {
            for (task in workspace.running) running.add(LivePreviewKey(workspace.key, task.sessionId))
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
        livePhases.keys.removeIf { it.workspaceKey == key }
        liveTitles.keys.removeIf { it.workspaceKey == key }
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
        notifyState.reset()
        livePreviews.clear()
        livePreviewAt.clear()
        val removed = previousRunningIds.toList()
        previousRunningIds = emptySet()
        return Update(running = emptyList(), removedIds = removed, completed = emptyList(), attention = emptyList())
    }

    private fun buildUpdate(notify: NotifyUpdate): Update {
        val running = ArrayList<RunningNotification>()
        for (workspace in workspaces.values) {
            for (task in workspace.running) {
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
        val nowIds = running.mapTo(HashSet()) { it.id }
        val removed = previousRunningIds.filterNot { it in nowIds }
        previousRunningIds = nowIds
        return Update(
            running = running,
            removedIds = removed,
            completed = notify.completed,
            attention = notify.attention,
        )
    }
}
