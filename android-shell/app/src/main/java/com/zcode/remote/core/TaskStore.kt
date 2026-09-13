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
    private val livePreviews = HashMap<String, String>()

    /** Workspaces currently subscribed, for the diagnostics screen. */
    fun workspaces(): List<Workspace> = workspaces.values.toList()

    fun workspaceCount(): Int = workspaces.size

    val hasRunningTasks: Boolean
        get() = workspaces.values.any { it.running.isNotEmpty() }

    /** 正在跑的任务（工作区键、会话 id）——原生拉取对话详情的清单。 */
    fun runningTaskRefs(): List<Pair<String, String>> =
        workspaces.values.flatMap { ws -> ws.running.map { ws.key to it.sessionId } }

    /**
     * 覆盖某任务的"活进展"文案并重建通知。文本没变时返回空 Update（不打扰系统）。
     */
    fun applyLivePreview(sessionId: String, text: String): Update {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || livePreviews[sessionId] == trimmed) {
            return Update(emptyList(), emptyList(), emptyList(), emptyList())
        }
        livePreviews[sessionId] = trimmed
        return buildUpdate(NotifyUpdate(running = emptyList(), completed = emptyList(), attention = emptyList()))
    }

    /** 交还前台：活进展不再是数据源，交给页面自己的会话索引。 */
    fun clearLivePreviews() {
        livePreviews.clear()
    }

    /**
     * Folds a fresh sessions-index snapshot of one workspace in and reports the
     * notification changes.
     *
     * [tasks] replaces the workspace's previous list entirely — the protocol
     * sends a full snapshot followed by deltas, and the JS side always hands us
     * a complete list, so absence means removal.
     */
    fun applyWorkspace(
        key: String,
        title: String,
        path: String,
        identity: String,
        source: String,
        tasks: List<TaskSnapshot>,
    ): Update {
        val previous = workspaces[key]
        // A changed title/scope must not lose the task list; a genuinely new
        // task list must not inherit stale phases, which NotifyState handles
        // per key anyway.
        workspaces[key] = Workspace(
            key = key,
            title = title.ifEmpty { previous?.title ?: key },
            path = path.ifEmpty { previous?.path ?: "" },
            identity = identity.ifEmpty { previous?.identity ?: "" },
            source = source,
            tasks = tasks,
        )
        val notify = notifyState.apply(key, tasks)
        return buildUpdate(notify)
    }

    /** Drops a workspace entirely, e.g. when its bridge is gone for good. */
    fun removeWorkspace(key: String): Update {
        workspaces.remove(key)
        notifyState.forget(key)
        return buildUpdate(NotifyUpdate(running = emptyList(), completed = emptyList(), attention = emptyList()))
    }

    fun reset(): Update {
        workspaces.clear()
        notifyState.reset()
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
                            livePreviews[task.sessionId] ?: task.preview,
                            workspace.title,
                        ),
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
