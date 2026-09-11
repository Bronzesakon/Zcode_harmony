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

    /** Workspaces currently subscribed, for the diagnostics screen. */
    fun workspaces(): List<Workspace> = workspaces.values.toList()

    fun workspaceCount(): Int = workspaces.size

    val hasRunningTasks: Boolean
        get() = workspaces.values.any { it.running.isNotEmpty() }

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
                        body = NotifyState.formatBody(task.preview, workspace.title),
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
