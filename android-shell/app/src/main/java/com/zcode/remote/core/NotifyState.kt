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

/**
 * The status words the shell can show.
 *
 * [RUNNING] / [WAITING] are the two a *live* task is allowed to carry (D9 — a
 * third live state was considered and rejected). [COMPLETED] is not a third live
 * state: it exists only for the transient card that pops when a task finishes
 * (D15). [statusOf] never returns it, which is what keeps the D9 rule intact.
 */
enum class TaskStatus(val label: String) {
    RUNNING("运行中"),
    WAITING("等待确认"),
    COMPLETED("已完成"),
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
     * two values exist on purpose, and [TaskStatus.COMPLETED] is deliberately not
     * one of them — it belongs to the transient completion card, not to a live
     * task.
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
         * The notification title: `状态 · 任务名`.
         *
         * The status word is a **prefix of the title row**, not of the progress
         * line (D15). Two reasons, both about what the fluid cloud card shows:
         * the card's title row is the one line the user reads at a glance, and
         * keeping the status there leaves the whole body to the live progress —
         * which is the part that has to have room to breathe.
         *
         * The title is forced onto one line because the body below it is the
         * live conversation: a two-line title steals lines from it. Note this is
         * the *only* place the title is flattened — [TaskSnapshot.displayTitle]
         * stays verbatim, because the notification-tap locator matches it against
         * text in the page and must not tolerate a rewritten string.
         */
        fun formatTitle(label: String, title: String): String =
            if (title.isEmpty()) label else "$label · ${singleLine(title)}"

        /**
         * The notification body: the latest progress, with the workspace name as
         * the fallback when a task has not produced a preview yet. No status word
         * here any more — it moved to the title (see [formatTitle]).
         */
        fun formatBody(preview: String, fallback: String): String {
            val trimmed = preview.trim()
            return if (trimmed.isEmpty()) singleLine(fallback) else singleLine(trimmed)
        }

        /**
         * Collapses every run of whitespace — newlines included — into one space.
         * A notification title is drawn on a single line by the platform, so an
         * embedded newline would otherwise be shown as a hard break in some
         * renderings (and would silently eat a body line in the fluid cloud card).
         */
        fun singleLine(text: String): String = text.replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("\\s+")

        /** Notification id for a live task, stable within a run (D8/§5.6). */
        fun notificationIdFor(workspaceKey: String, sessionId: String): Int {
            val hash = 31 * workspaceKey.hashCode() + sessionId.hashCode()
            return ONGOING_ID_BASE + (hash and 0x7FFFFFFF) % ONGOING_ID_RANGE
        }

        /**
         * Notification id of the transient card posted when a task finishes.
         *
         * A separate id on purpose: the live card for the same task is being
         * cancelled in the same update, and reusing the id would race the two
         * (whichever landed last would win). The two ranges do not overlap, so a
         * completion card can never be confused with — or cancel — a live one.
         */
        fun completionCardIdFor(workspaceKey: String, sessionId: String): Int {
            val hash = 31 * workspaceKey.hashCode() + sessionId.hashCode()
            return COMPLETION_CARD_BASE + (hash and 0x7FFFFFFF) % COMPLETION_CARD_RANGE
        }

        const val ONGOING_ID_BASE = 100_000
        const val ONGOING_ID_RANGE = 800_000

        const val COMPLETION_CARD_BASE = 900_001
        const val COMPLETION_CARD_RANGE = 99_000
    }
}
