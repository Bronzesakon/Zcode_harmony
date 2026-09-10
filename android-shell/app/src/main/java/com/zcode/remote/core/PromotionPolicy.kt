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
     * @return the notification ids that should request promotion, out of
     *   [running] (which is the full running set from [TaskStore]).
     */
    fun choose(running: List<TaskStore.RunningNotification>, max: Int = MAX_PROMOTED): Set<Int> {
        if (running.isEmpty() || max <= 0) return emptySet()
        return running
            .sortedWith(
                compareByDescending<TaskStore.RunningNotification> { it.status == TaskStatus.WAITING }
                    .thenByDescending { it.task.lastActivityAt }
            )
            .take(max)
            .mapTo(LinkedHashSet()) { it.id }
    }
}
