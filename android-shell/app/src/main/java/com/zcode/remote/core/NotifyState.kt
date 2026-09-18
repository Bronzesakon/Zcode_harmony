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
 * third live state was considered and rejected). The other three are **finished**
 * states, used only by the card that is kept after a task stops: [COMPLETED],
 * [INTERRUPTED] (the user stopped it) and [FAILED]. [statusOf] never returns any of
 * them, which is what keeps the D9 rule intact.
 *
 * 2026-09-18：这三个词之前是**一个**「已完成」——用户点了"中断"，卡片却宣布"已完成"。
 * 现在按终态相位分辨（[finishedStatusOf]）。
 */
enum class TaskStatus(val label: String) {
    RUNNING("运行中"),
    WAITING("等待确认"),
    COMPLETED("已完成"),

    /** 用户主动中断／取消（`completedInterrupted` / `cancelled`）。 */
    INTERRUPTED("已中断"),

    /** 失败（`failed` / `error`）。 */
    FAILED("已失败"),
}

/**
 * A task that just finished (D10). [failed] only affects the wording, not
 * whether the user is told.
 */
data class CompletionEvent(
    val workspaceKey: String,
    val task: TaskSnapshot,
    val failed: Boolean,
    val finalPreview: String = task.preview,
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
    /**
     * Sessions whose task has gone terminal but is still inside the completion
     * observation window (see [NotifyState.COMPLETION_HOLD_MS]). They must keep
     * counting as running, so the live card is never cancelled for a gap that
     * turns out to be a turn boundary.
     */
    val heldRunning: Set<String> = emptySet(),
)

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
     * Tasks observed stopped whose observation window has not elapsed yet:
     * workspaceKey -> (sessionId -> when the stop was first seen).
     *
     * 真机 2026-09-17 晚定案（取证 `docs/18` §3.10 ⑤）：agent 一轮结束、下一轮
     * **0.4–0.55s** 后重新开始，`turnHeader.state` 就是 `结束 → 在跑`。旧逻辑把"结束"那一拍
     * 当完成 ⇒ 撤运行卡 + 另发一张 15s「已完成」卡 + 0.5s 后又建运行卡：卡片闪一次、
     * 可听提示白响一声、同一任务两张卡去抢两个提升位。**"任务结束"必须持续停着才算**，
     * 所以这里加一个观察窗 [COMPLETION_HOLD_MS]：窗内又跑起来＝什么都没发生
     * （那张卡一秒都不用动，这正是"只刷新、不重建"的前提）。
     */
    private val pendingCompletions = HashMap<String, HashMap<String, Long>>()

    /**
     * Folds a fresh view of one workspace in. [tasks] must be the workspace's
     * *complete* task list — removals are detected by absence, exactly as the
     * reference implementation does.
     *
     * [nowMs] is passed in (rather than read here) so the observation window is
     * testable and so a caller that already has a clock does not read a second one.
     */
    fun apply(
        workspaceKey: String,
        tasks: List<TaskSnapshot>,
        nowMs: Long = System.currentTimeMillis(),
    ): NotifyUpdate {
        val previous = previousPhases[workspaceKey].orEmpty()
        val pending = pendingCompletions.getOrPut(workspaceKey) { HashMap() }
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
        val held = HashSet<String>()
        // 已经在观察窗里的任务先处理：它们**上一拍就已经是终态**了，不会再走下面那条
        // "running → terminal" 的转移，所以撤销与到期必须在这里独立判一次（漏了这一步，
        // 窗口会永远挂着——真机表现是完成卡永远不出现、定时器一直重排）。
        for ((sessionId, since) in pending.toMap()) {
            val now = nowPhases[sessionId]
            if (now == null || now in RUNNING_PHASES || now !in TERMINAL_PHASES) {
                pending.remove(sessionId)
                continue
            }
            if (nowMs - since < COMPLETION_HOLD_MS) {
                held.add(sessionId)
                continue
            }
            pending.remove(sessionId)
            completed.add(
                CompletionEvent(
                    workspaceKey,
                    byId.getValue(sessionId),
                    failed = now in FAILED_PHASES,
                )
            )
        }
        // 新的转移：这一拍才第一次看到"停了" ⇒ 开窗（不撤卡、不提醒，等下一拍验证）。
        for ((sessionId, wasPhase) in previous) {
            if (wasPhase !in RUNNING_PHASES) continue
            // A task that vanished from the list must not fire a completion later
            // when it comes back with a terminal phase; its pending goes with it.
            val now = nowPhases[sessionId]
            if (now == null) {
                pending.remove(sessionId)
                continue
            }
            if (now in RUNNING_PHASES || now !in TERMINAL_PHASES) continue
            if (pending.containsKey(sessionId)) continue
            pending[sessionId] = nowMs
            held.add(sessionId)
        }

        previousPhases[workspaceKey] = nowPhases

        return NotifyUpdate(
            running = running,
            completed = completed,
            attention = attention,
            heldRunning = held,
        )
    }

    /**
     * When the earliest observation window elapses (0 = nothing pending).
     *
     * The caller needs this to come back on time: a task that ends and then goes
     * quiet produces no further frames, so without a timer the completion would
     * never be announced until the next unrelated update.
     */
    fun nextCompletionDeadlineMs(): Long {
        var earliest = 0L
        for (workspace in pendingCompletions.values) {
            for (since in workspace.values) {
                val due = since + COMPLETION_HOLD_MS
                if (earliest == 0L || due < earliest) earliest = due
            }
        }
        return earliest
    }

    /** Drops a workspace's history, e.g. when it stops being subscribed. */
    fun forget(workspaceKey: String) {
        previousPhases.remove(workspaceKey)
        pendingCompletions.remove(workspaceKey)
    }

    fun reset() {
        previousPhases.clear()
        notifiedInteractions.clear()
        pendingCompletions.clear()
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

        /**
         * 终态相位 → 卡片上的收尾状态词（2026-09-18）。
         *
         * 为什么需要：完成卡曾经**一律**写「已完成」——用户在桌面端点"中断"，卡片却宣布
         * "已完成"（用户当场指出）。三种收尾要分清：正常完成 / **用户中断** / 失败。
         * 未知终态按"已完成"处理（宁可说完成，也不无端指控失败）。
         */
        fun finishedStatusOf(phase: String): TaskStatus = when (phase) {
            "completedInterrupted", "cancelled" -> TaskStatus.INTERRUPTED
            "failed", "error" -> TaskStatus.FAILED
            else -> TaskStatus.COMPLETED
        }

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
         * How long a task must stay stopped before it counts as finished.
         *
         * 真机（2026-09-17 22:43/22:51）测到的"轮次缝"是 **0.4–0.55s**：agent 一轮结束、
         * 下一轮立刻开始。3s 给这个量级留了一个数量级余量，又短到真正完成时那个
         * 「已完成」标记几乎立刻出现。窗口内的"停"不算结束，那张卡一秒都不用动。
         */
        const val COMPLETION_HOLD_MS = 3_000L

        const val ONGOING_ID_BASE = 100_000
        const val ONGOING_ID_RANGE = 800_000
    }
}
