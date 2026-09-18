package com.zcode.remote.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zcode.remote.MainActivity
import com.zcode.remote.R
import com.zcode.remote.core.AttentionEvent
import com.zcode.remote.core.CompletionEvent
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.NotifyState
import com.zcode.remote.core.PromotionPolicy
import com.zcode.remote.core.TaskStatus
import com.zcode.remote.core.TaskStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * All notification rendering.
 *
 * Three channels, decided up front because Android will not let an app change a
 * channel's importance after creation (§5.6):
 *
 *   running_tasks  IMPORTANCE_LOW      silent; one ongoing notification per
 *                                      running task, plus a group summary
 *   task_attention IMPORTANCE_DEFAULT  audible; a task is waiting for the user
 *   task_completed IMPORTANCE_DEFAULT  audible; a task just finished (D10)
 *
 * The ongoing notifications live in `running_tasks` rather than being tied to a
 * fourth "service" channel: the foreground service's own notification IS
 * notification id [ID_SERVICE], posted in the same channel, so the notification
 * shade shows one coherent group.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * The 「已完成」cards currently being kept: **same notification id as the live
     * card they came from** (2026-09-17, replacing D15's separate-id 15s card).
     *
     * 旧形态是一张另开 id、15s 后自动撤的卡：任务结束那一拍运行卡被撤、新卡顶上，
     * 0.5s 后新轮开始又建运行卡 ⇒ 卡片闪、可听提示白响、同一任务两张卡去抢两个提升位
     * （真机 2026-09-17 22:51:50，取证 `docs/18` §3.10 ⑤）。现在结束＝**同一条记录改状态**，
     * 一直留到用户清掉（用户 2026-09-17 拍板）。
     */
    private data class FinishedCard(
        val workspaceKey: String,
        val sessionId: String,
        val locateTitle: String,
        val title: String,
        val body: String,
        /** 收尾状态词（已完成／已中断／已失败）——标题与芯片文案都用它。 */
        val status: TaskStatus,
        val completedAt: Long,
        /** 是否正占着提升位（决定 ongoing：被提升的平台要求必须是 ongoing）。 */
        val promoted: Boolean = false,
        /** 这一版内容有没有真的发出去过（新建时 false，发完置 true）。 */
        val posted: Boolean = false,
    )

    private val finishedCards = ConcurrentHashMap<Int, FinishedCard>()

    /** True when the user has not granted POST_NOTIFICATIONS (API 33+). */
    fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    fun ensureChannels() {
        val running = NotificationChannel(
            CHANNEL_RUNNING,
            context.getString(R.string.channel_running_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_running_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val attention = NotificationChannel(
            CHANNEL_ATTENTION,
            context.getString(R.string.channel_attention_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_attention_desc)
        }
        val completed = NotificationChannel(
            CHANNEL_COMPLETED,
            context.getString(R.string.channel_completed_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_completed_desc)
        }
        // The foreground service needs its own low-importance channel.
        val keepAlive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            context.getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(running, attention, completed, keepAlive))
        adoptOrphanFinishedCards()
    }

    /**
     * Takes over 已完成 cards left behind by a process that died after posting them.
     *
     * Why this is needed at all: a promoted card must be `ongoing`, and an ongoing
     * notification cannot be swiped away — so a card we can no longer reason about
     * (its slot accounting died with the process) could otherwise hold a promotion
     * slot forever with no way out. Re-posting it **without** the promotion solves
     * both: it stays exactly as visible as before, but it becomes an ordinary
     * dismissible notification until this process's own bookkeeping catches up.
     *
     * The marker extra is what makes the distinction possible: live cards share the
     * same id range now, so the old "ids above 900001" sweep could not tell them
     * apart (and would have cancelled a running task's card).
     */
    private fun adoptOrphanFinishedCards() {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val active = try {
            platform.activeNotifications
        } catch (e: Exception) {
            Diagnostics.log("debug", "读取活动通知失败: ${e.message}")
            return
        }
        for (record in active) {
            val id = record.id
            if (finishedCards.containsKey(id)) continue
            val extras = record.notification.extras ?: continue
            if (!extras.getBoolean(EXTRA_FINISHED_CARD)) continue
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            Diagnostics.info("认领上次进程遗留的完成卡 (id=$id)：降级为可滑除的通知")
            val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_zcode)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .addAction(0, context.getString(R.string.card_dismiss), dismissIntent(id))
                .addExtras(finishedCardExtras())
            try {
                manager.notify(id, builder.build())
            } catch (e: SecurityException) {
                Diagnostics.log("warn", "认领完成卡失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ service state

    /** The foreground service notification (also the idle "connected" state). */
    fun buildServiceNotification(runningCount: Int, text: String): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_SERVICE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (runningCount > 0) {
            context.getString(R.string.notification_summary_running, runningCount)
        } else {
            context.getString(R.string.keepalive_title)
        }
        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun postServiceNotification(notification: android.app.Notification) {
        if (!notificationsEnabled()) return
        try {
            manager.notify(ID_SERVICE, notification)
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "更新常驻通知失败: ${e.message}")
        }
    }

    // -------------------------------------------------------- ongoing per task

    /**
     * Replaces the whole set of ongoing task notifications with [running],
     * cancelling the ids in [removedIds]. The reference client throttles these
     * updates; the coordinator does the same so a fast preview stream does not
     * wake the notification shade hundreds of times per minute.
     */
    fun syncRunningTasks(update: TaskStore.Update) {
        if (!notificationsEnabled()) return
        val now = System.currentTimeMillis()
        // 1) 结束：**同一条记录改状态**。不撤、不另开 id —— 用户看到的是卡片原地变字
        //    （"只刷新现存卡片，不重建"，2026-09-17 拍板）。
        for (event in update.completed) {
            val id = NotifyState.notificationIdFor(event.workspaceKey, event.task.sessionId)
            // 收尾状态词按**终态相位**分辨：正常完成 / 用户中断 / 失败（2026-09-18）。
            // 曾经一律写「已完成」——用户在桌面端点"中断"，卡片却宣布"已完成"。
            val status = NotifyState.finishedStatusOf(event.task.phase)
            finishedCards[id] = FinishedCard(
                workspaceKey = event.workspaceKey,
                sessionId = event.task.sessionId,
                locateTitle = event.task.displayTitle,
                title = NotifyState.formatTitle(status.label, event.task.displayTitle),
                body = NotifyState.formatBody(event.finalPreview, workspaceNameOf(event.workspaceKey)),
                status = status,
                completedAt = now,
            )
            Diagnostics.info(
                "完成卡原地改状态 id=$id（${status.label} · ${event.task.displayTitle} · " +
                    "相位 ${event.task.phase}）——不撤卡、不换 id",
            )
        }
        val cancelled = ArrayList<Int>()
        for (id in update.removedIds) {
            // 走进完成态的那张卡不撤：它不是"走了"，是"改了状态"。
            if (finishedCards.containsKey(id)) continue
            manager.cancel(id)
            cancelled.add(id)
        }
        if (cancelled.isNotEmpty()) {
            Diagnostics.info(
                "卡片撤回 id=${cancelled.joinToString(" ")}（撤回后运行集 ${update.running.size} 个）",
            )
        }
        // 2) 新一轮落在同一张卡上：完成态就此结束，下面的 notify 把它覆盖回运行中。
        for (item in update.running) finishedCards.remove(item.id)

        // Live Updates / 流体云: only a couple of cards, chosen deliberately — see
        // PromotionPolicy. 已完成的卡也参与抢位，但永远排在在跑的任务之后。
        // The group summary further down is intentionally NOT
        // promoted: Android refuses to promote a summary, and it would duplicate
        // what the per-task cards already say.
        val promoted = PromotionPolicy.choose(
            running = update.running,
            finished = finishedCards.map { PromotionPolicy.Finished(it.key, it.value.completedAt) },
        )
        if (promoted != lastPromotedIds) {
            lastPromotedIds = promoted
            val cards = promoted.joinToString(" ") { "#" + it }
            val runningIds = update.running.joinToString(" ") { "#" + it.id }
            Diagnostics.info("提升集合变化: [$cards]（运行 ${update.running.size} 个：$runningIds）")
        }
        if ((update.running.isNotEmpty() || finishedCards.isNotEmpty()) &&
            lastPromotedCount != promoted.size
        ) {
            lastPromotedCount = promoted.size
            val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = platform?.getNotificationChannel(CHANNEL_RUNNING)
            val atOrBelowMin = channel?.importance?.let { it <= NotificationManager.IMPORTANCE_MIN } ?: false
            Diagnostics.info(LiveUpdate.describeEligibility(context, channelImportanceMin = atOrBelowMin))
        }
        if (update.running.isEmpty() && finishedCards.isEmpty()) {
            manager.cancel(ID_GROUP_SUMMARY)
            return
        }
        for (item in update.running) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_LOCATE_TASK
                putExtra(MainActivity.EXTRA_SESSION_ID, item.task.sessionId)
                // `locateTitle`, NOT `item.title`: the latter now carries the
                // 状态 prefix for display, and the locator matches this string
                // against text in the page — which never contains the prefix.
                // Shipping the decorated title here silently broke "tap the
                // notification to jump to the task" (caught on device
                // 2026-09-12: `未能在页面上定位任务: 运行中 · …`).
                putExtra(MainActivity.EXTRA_TASK_TITLE, item.locateTitle)
            }
            val pending = PendingIntent.getActivity(
                context,
                item.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_zcode)
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setWhen(item.activityAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
                .setContentIntent(pending)
                .setGroup(GROUP_RUNNING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
            // The status chip reuses D9's two status words: 运行中 / 等待确认.
            if (item.id in promoted) {
                LiveUpdate.requestPromotion(builder, item.status.label)
            }
            try {
                manager.notify(item.id, builder.build())
            } catch (e: SecurityException) {
                Diagnostics.log("warn", "更新任务通知失败: ${e.message}")
            }
        }
        // 3) 已完成卡：只在"提升状态变了"或"还没发过"时重发一次——那正是它降级成
        //    可滑除通知、或重新回到芯片位的时刻；其余时候一个字都不用动。
        for ((id, card) in finishedCards) {
            val isPromoted = id in promoted
            if (card.posted && card.promoted == isPromoted) continue
            val updated = card.copy(promoted = isPromoted, posted = true)
            finishedCards[id] = updated
            postFinishedCard(id, updated)
        }
        // Group summary keeps a long task list collapsed (§11.2).
        val summary = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(context.getString(R.string.notification_group_running))
            .setContentText(context.getString(R.string.notification_summary_running, update.running.size))
            .setGroup(GROUP_RUNNING)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            manager.notify(ID_GROUP_SUMMARY, summary)
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "更新群组摘要失败: ${e.message}")
        }
    }

    // -------------------------------------------------------------- completion

    /**
     * D10: audible, dismissible, one per completion.
     *
     * This is the *durable* record in the 任务完成 channel — audible, dismissible,
     * carrying the task name and the last progress. The card is deliberately **not**
     * posted from here any more: the live card for the same task changes state in
     * [syncRunningTasks] (same notification id, no cancel/repost), because that is
     * what lets the user see "this task finished" on the card they were already
     * watching instead of watching it blink out and a new one appear.
     */
    fun notifyCompleted(event: CompletionEvent) {
        if (!notificationsEnabled()) return
        val task = event.task
        val titleRes = if (event.failed) {
            R.string.notification_title_failed
        } else {
            R.string.notification_title_completed
        }
        val preview = event.finalPreview.trim()
        val text = if (preview.isEmpty()) task.displayTitle else "${task.displayTitle}\n$preview"
        val id = nextId()
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(taskIntent(event.workspaceKey, task.sessionId, task.displayTitle, index = id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "发送完成通知失败: ${e.message}")
        }
    }

    /**
     * Renders one 已完成 card — always the **same notification id** its live card
     * used, so the platform updates the record the user is already looking at.
     *
     * `ongoing` follows the promotion, because the platform requires a promoted
     * notification to be ongoing **and** an ongoing notification cannot be swiped
     * away. That is the whole reason the card carries a 「知道了」 action. When a
     * running task needs the promotion slot, this card is demoted instead
     * (not promoted, not ongoing): it stays in the shade as an ordinary
     * dismissible notification until the user swipes it, which is what
     * "留到用户清掉" means on this platform.
     */
    private fun postFinishedCard(id: Int, card: FinishedCard) {
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(card.title)
            .setContentText(card.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.body))
            .setContentIntent(taskIntent(card.workspaceKey, card.sessionId, card.locateTitle, index = id))
            .setOngoing(card.promoted)
            .setAutoCancel(!card.promoted)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(0, context.getString(R.string.card_dismiss), dismissIntent(id))
            .addExtras(finishedCardExtras())
        if (card.promoted) LiveUpdate.requestPromotion(builder, card.status.label)
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "更新完成卡失败: ${e.message}")
        }
    }

    /** The user tapped 「知道了」 (or swiped a demoted card): that card is over. */
    fun dismissFinishedCard(id: Int) {
        finishedCards.remove(id)
        manager.cancel(id)
        Diagnostics.info("完成卡已清除 (id=$id)")
    }

    /** Marks a notification as one of ours in the finished state (see the adoption sweep). */
    private fun finishedCardExtras(): android.os.Bundle = android.os.Bundle().apply {
        putBoolean(EXTRA_FINISHED_CARD, true)
    }

    private fun dismissIntent(id: Int): PendingIntent {
        val intent = Intent(context, CardDismissReceiver::class.java).apply {
            action = CardDismissReceiver.ACTION_DISMISS
            putExtra(CardDismissReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // --------------------------------------------------------------- attention

    /**
     * A pending interaction already updated its ongoing notification in place
     * (the status word becomes 等待确认). This adds an audible, dismissible
     * reminder on top, because a channel's importance cannot be changed on the
     * fly and swapping an existing notification between channels is not
     * reliable (§11.2).
     */
    fun notifyAttention(event: AttentionEvent) {
        if (!notificationsEnabled()) return
        val task = event.task
        val preview = task.preview.trim()
        val text = if (preview.isEmpty()) task.displayTitle else "${task.displayTitle}\n$preview"
        val id = nextId()
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(context.getString(R.string.notification_title_attention))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(taskIntent(event.workspaceKey, task.sessionId, task.displayTitle, index = id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "发送待确认通知失败: ${e.message}")
        }
    }

    fun clearAll() {
        manager.cancelAll()
        finishedCards.clear()
        Diagnostics.info("已清除全部通知")
    }

    // ------------------------------------------------------------------ private

    private fun taskIntent(
        workspaceKey: String,
        sessionId: String,
        title: String,
        index: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_LOCATE_TASK
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(MainActivity.EXTRA_TASK_TITLE, title)
        }
        return PendingIntent.getActivity(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * How many cards were promoted last time. Only used so the fluid-cloud
     * eligibility note is logged when that number changes instead of on every
     * preview delta.
     */
    private var lastPromotedCount = -1

    /**
     * 上一次的提升集合（诊断用，2026-09-16）：真机报"流体云卡片消失又重建"，
     * 而通知本身没有被撤回——那只能是**提升集合变化**导致这张卡这一拍没被提升
     * （ColorOS 会因此收回卡片，下一拍再提升就"重建"）。这行日志是唯一能看见它的地方。
     */
    private var lastPromotedIds: Set<Int> = emptySet()

    /**
     * The last path segment of a workspace key — "default", "Mimo", … Used as the
     * body fallback on a card that has no progress text, where the full path
     * would not fit and the task title is already in the title row.
     */
    private fun workspaceNameOf(key: String): String {
        val name = key.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return name.ifEmpty { key }
    }

    /** Ids for the transient (completion / attention) notifications. */
    private val transientId = AtomicInteger(ID_TRANSIENT_BASE)

    private fun nextId(): Int {
        while (true) {
            val current = transientId.get()
            val next = if (current >= ID_TRANSIENT_BASE + 100_000) {
                ID_TRANSIENT_BASE + 1
            } else {
                current + 1
            }
            if (transientId.compareAndSet(current, next)) return next
        }
    }

    companion object {
        const val CHANNEL_RUNNING = "running_tasks"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_COMPLETED = "task_completed"
        const val CHANNEL_KEEPALIVE = "keepalive"

        const val GROUP_RUNNING = "running_tasks"

        /** The foreground service notification / idle state. */
        const val ID_SERVICE = 1

        /** The "N 个任务运行中" group summary. */
        const val ID_GROUP_SUMMARY = 2

        /**
         * Marks a notification as one of ours *in the finished state*, so a card
         * left behind by a dead process can be told apart from a live one on the
         * next start (both live in the same id range — see the adoption sweep).
         */
        private const val EXTRA_FINISHED_CARD = "zcode.finishedCard"

        private const val ID_TRANSIENT_BASE = 2_000_000

        private const val REQUEST_SERVICE = 10

        /**
         * Describes a running task for the service notification body. Uses
         * [TaskStore.RunningNotification.title], which already carries the status
         * prefix — the body must not repeat it.
         */
        fun runningBody(tasks: List<TaskStore.RunningNotification>): String {
            if (tasks.isEmpty()) return ""
            val lines = tasks.take(5).map { "• ${it.title} — ${it.body}" }
            val text = lines.joinToString("\n")
            return if (text.length > 600) text.take(597) + "…" else text
        }
    }
}
