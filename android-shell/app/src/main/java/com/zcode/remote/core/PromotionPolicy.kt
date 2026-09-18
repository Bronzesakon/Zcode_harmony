package com.zcode.remote.core

/**
 * Decides which running tasks claim the (few) promoted-notification slots.
 *
 * Android 16 Live Updates — which ColorOS 16 renders as 流体云 — are meant for
 * the one or two activities a user is actively tracking, not for every
 * background task an app happens to have. Promoting everything would clutter the
 * status bar and risks the system demoting the whole app's promotions, so the
 * shell promotes a small, deliberate subset:
 *
 *   1. tasks **waiting for the user** first (they are the actionable ones —
 *      需要处理), most recently active first;
 *   2. then the most recently active running task.
 *
 * Everything else keeps its ordinary ongoing notification in the shade; only the
 * promoted few get the fluid-cloud card. Kept Android-free and pure so it can be
 * unit tested on the JVM.
 */
object PromotionPolicy {

    /** How many task cards may be promoted at once. */
    const val MAX_PROMOTED = 2

    /**
     * A card whose task has finished and is being kept on purpose (2026-09-17):
     * the *same* notification, status word flipped to 已完成, left up for the user
     * to dismiss instead of vanishing.
     */
    data class Finished(val id: Int, val completedAt: Long)

    /**
     * @return the notification ids that should request promotion, out of
     *   [running] (the full running set from [TaskStore]) plus any [finished] cards
     *   still being held.
     *
     * A finished card competes for the same slots but always **loses** to a live
     * one: the strip is for activity that is happening, and a task that finished
     * ten minutes ago must never keep a running task off it. Losing a slot does not
     * delete it — it drops back to an ordinary dismissible notification
     * (see `Notifier.syncRunningTasks`).
     */
    fun choose(
        running: List<TaskStore.RunningNotification>,
        finished: List<Finished> = emptyList(),
        max: Int = MAX_PROMOTED,
    ): Set<Int> {
        if (max <= 0) return emptySet()
        val live = running
            .sortedWith(
                compareByDescending<TaskStore.RunningNotification> { it.status == TaskStatus.WAITING }
                    .thenByDescending { it.activityAt }
            )
            .map { it.id }
        val done = finished.sortedByDescending { it.completedAt }.map { it.id }
        return (live + done).take(max).toCollection(LinkedHashSet())
    }
}
