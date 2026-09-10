package com.zcode.remote.core

/**
 * One task as reported by the sessions-index stream.
 *
 * Deliberately a plain data class with no Android dependencies: everything that
 * decides *whether* to notify lives in [NotifyState] so it can be unit tested on
 * the JVM (see src/test/java/com/zcode/remote/core/NotifyStateTest.kt).
 */
data class TaskSnapshot(
    val sessionId: String,
    val title: String,
    val phase: String,
    val preview: String,
    val pendingInteractionId: String,
    val lastActivityAt: Long,
    val hasBackgroundWork: Boolean,
) {
    /** The reference client falls back to the id when a task has no title yet. */
    val displayTitle: String get() = title.ifEmpty { sessionId }

    val isWaitingForUser: Boolean get() = pendingInteractionId.isNotEmpty()
}

/** The two status words the ongoing notification is allowed to show (D9). */
enum class TaskStatus(val label: String) {
    RUNNING("运行中"),
    WAITING("等待确认"),
}

/**
 * A task that just finished (D10). [failed] only affects the wording, not
 * whether the user is told.
 */
data class CompletionEvent(
    val workspaceKey: String,
    val task: TaskSnapshot,
    val failed: Boolean,
)

/** A task that needs the user's attention (permission / input request). */
data class AttentionEvent(
    val workspaceKey: String,
    val task: TaskSnapshot,
    val interactionId: String,
)

/**
 * The result of folding one workspace's sessions-index snapshot into the
 * state: what is running now, what just finished, what needs attention.
 */
data class NotifyUpdate(
    val running: List<TaskSnapshot>,
    val completed: List<CompletionEvent>,
    val attention: List<AttentionEvent>,
) {
    val hasRunning: Boolean get() = running.isNotEmpty()
}

/**
 * Pure derivation of notification state from sessions-index snapshots.
 *
 * Ported from the reference client's `computeNotifyUpdate()`, and kept
 * behaviour-identical on the two points that matter:
 *
 *  1. "Completed" is a *transition*, previous tick's phase was running and this
 *     tick's is terminal. A state snapshot alone cannot tell you that, so the
 *     phase table must be carried across ticks. It doubles as the de-dupe: the
 *     transition fires exactly once, and re-running a task fires again on its
 *     next completion.
 *  2. An interaction is announced once per interactionId, never repeatedly
 *     while it stays pending.
 *
 * State is kept per workspace because session ids are only unique within one,
 * and because a workspace can disappear from the stream entirely.
 */
class NotifyState {

    /** workspaceKey -> (sessionId -> phase) from the previous tick. */
    private val previousPhases = HashMap<String, MutableMap<String, String>>()

    /** interactionIds already announced, globally. */
    private val notifiedInteractions = HashSet<String>()

    /**
     * Folds a fresh view of one workspace in. [tasks] must be the workspace's
     * *complete* task list — removals are detected by absence, exactly as the
     * reference implementation does.
     */
    fun apply(workspaceKey: String, tasks: List<TaskSnapshot>): NotifyUpdate {
        val previous = previousPhases[workspaceKey].orEmpty()
        val nowPhases = HashMap<String, String>(tasks.size)
        val byId = HashMap<String, TaskSnapshot>(tasks.size)
        for (task in tasks) {
            nowPhases[task.sessionId] = task.phase
            byId[task.sessionId] = task
        }

        val running = ArrayList<TaskSnapshot>()
        val attention = ArrayList<AttentionEvent>()
        for (task in tasks) {
            if (task.phase in RUNNING_PHASES) {
                running.add(task)
            }
            val interactionId = task.pendingInteractionId
            if (interactionId.isNotEmpty() && notifiedInteractions.add(interactionId)) {
                attention.add(AttentionEvent(workspaceKey, task, interactionId))
            }
        }

        val completed = ArrayList<CompletionEvent>()
        for ((sessionId, wasPhase) in previous) {
            if (wasPhase !in RUNNING_PHASES) continue
            val now = nowPhases[sessionId] ?: continue
            if (now !in TERMINAL_PHASES) continue
            val task = byId.getValue(sessionId)
            completed.add(CompletionEvent(workspaceKey, task, failed = now in FAILED_PHASES))
        }

        // A task that vanished from the list must not fire a completion later
        // when it comes back with a terminal phase.
        previousPhases[workspaceKey] = nowPhases

        return NotifyUpdate(running = running, completed = completed, attention = attention)
    }

    /** Drops a workspace's history, e.g. when it stops being subscribed. */
    fun forget(workspaceKey: String) {
        previousPhases.remove(workspaceKey)
    }

    fun reset() {
        previousPhases.clear()
        notifiedInteractions.clear()
    }

    /**
     * Which status word the ongoing notification shows for a task (D9). Only
     * two values exist on purpose: a third state was considered and rejected.
     */
    fun statusOf(task: TaskSnapshot): TaskStatus =
        if (task.isWaitingForUser) TaskStatus.WAITING else TaskStatus.RUNNING

    companion object {
        val RUNNING_PHASES = setOf("running", "prewarming")

        val TERMINAL_PHASES = setOf(
            "completed",
            "completedSuccess",
            "completedInterrupted",
            "cancelled",
            "failed",
            "error",
        )

        /** Terminal phases that read better as a failure than a success. */
        val FAILED_PHASES = setOf("failed", "error", "cancelled", "completedInterrupted")

        fun isTerminal(phase: String): Boolean = phase in TERMINAL_PHASES

        fun isRunning(phase: String): Boolean = phase in RUNNING_PHASES

        /**
         * The ongoing notification body: `状态 · 最新进展` (D8). The preview is
         * dropped when empty rather than leaving a dangling separator.
         */
        fun formatBody(status: TaskStatus, preview: String): String {
            val trimmed = preview.trim()
            return if (trimmed.isEmpty()) status.label else "${status.label} · $trimmed"
        }

        /** Notification id for a live task, stable within a run (D8/§5.6). */
        fun notificationIdFor(workspaceKey: String, sessionId: String): Int {
            val hash = 31 * workspaceKey.hashCode() + sessionId.hashCode()
            return ONGOING_ID_BASE + (hash and 0x7FFFFFFF) % ONGOING_ID_RANGE
        }

        const val ONGOING_ID_BASE = 100_000
        const val ONGOING_ID_RANGE = 800_000
    }
}
