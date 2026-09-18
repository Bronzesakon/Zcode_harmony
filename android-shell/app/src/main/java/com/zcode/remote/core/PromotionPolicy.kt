package com.zcode.remote.core

/**
 * Decides which task cards ask the system for a fluid-cloud (Live Updates) slot.
 *
 * 排序规则（2026-09-17／18 定）：
 *   1. **等待确认**的任务优先（它们是要用户动手的那一类）；
 *   2. 其次按最近活动时间；
 *   3. 被留住的**已完成**卡排在所有在跑的任务之后，且只取最新 [MAX_FINISHED_PROMOTED] 张。
 *
 * **没有"总共几张"的上限**（2026-09-18 用户否决硬上限）。这里曾经是 `MAX_PROMOTED = 2`，
 * 依据两条：官方的 UX 话术（实时更新是给"用户正在跟的一两个活动"用的）与我们自己笔记里的
 * **事实性错误**——"ColorOS 只并发提升 2 张流体云卡"。后者被用户现场推翻：三个任务同时跑时
 * 手机**三张卡同时正常显示**，`dumpsys notification` 里是**三条 `PROMOTED_ONGOING`**，
 * 而当时我们只请求了 2 个 ⇒ **系统自己也会多提**。本地官方资料（`ColorOS_docs/06-…`）里
 * 也**没有任何"每应用最多几张"的数字**。
 *
 * 结论：2 是我们自己拍的门，不是平台的墙；一个执行不了的上限只会让"日志说 2 个、现场 3 个"，
 * 还会把在跑的任务挡在芯片位外。在跑的任务有几个就提升几个——它天然被"最多覆盖 5 个工作区"
 * 和"用户实际跑几个任务"约束住。真正会无限增长的只有"留到用户清掉"的完成卡，
 * 所以界限只加在它们身上（超出者降级成普通可滑除通知，记录不丢）。
 *
 * 其余任务保留常驻通知（`running_tasks` 渠道），只是没有芯片卡。
 * Kept Android-free and pure so it can be unit tested on the JVM.
 */
object PromotionPolicy {

    /**
     * How many **finished** cards may keep a slot.
     *
     * 它们会一直留到用户清掉，不设限会越积越多、最后把在跑的任务挤出芯片位。
     * 超出的那些不是消失，而是降级成普通可滑除通知（见 `Notifier.syncRunningTasks`）。
     */
    const val MAX_FINISHED_PROMOTED = 3

    /**
     * A card whose task has finished and is being kept on purpose (2026-09-17):
     * the *same* notification, status word flipped to 已完成, left up for the user
     * to dismiss instead of vanishing.
     */
    data class Finished(val id: Int, val completedAt: Long)

    /**
     * @return every running task's id (ordered), plus the newest [maxFinished]
     *   finished cards.
     *
     * A finished card always **loses** to a live one: the strip is for activity
     * that is happening, and a task that finished ten minutes ago must never keep
     * a running task off it. Losing a slot does not delete it — it drops back to an
     * ordinary dismissible notification (see `Notifier.syncRunningTasks`).
     */
    fun choose(
        running: List<TaskStore.RunningNotification>,
        finished: List<Finished> = emptyList(),
        maxFinished: Int = MAX_FINISHED_PROMOTED,
    ): Set<Int> {
        val live = running
            .sortedWith(
                compareByDescending<TaskStore.RunningNotification> { it.status == TaskStatus.WAITING }
                    .thenByDescending { it.activityAt }
            )
            .map { it.id }
        val done = if (maxFinished <= 0) {
            emptyList()
        } else {
            finished.sortedByDescending { it.completedAt }.take(maxFinished).map { it.id }
        }
        return (live + done).toCollection(LinkedHashSet())
    }
}
