package com.zcode.remote.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
     * Removes the transient completion cards when their window is up.
     *
     * Asynchronous on API 28+, for the same reason ShellRuntime's handler is: an
     * ordinary message posted to the main looper is not delivered while the
     * window is invisible (the Choreographer's sync barrier starves it), and a
     * task finishing while the phone sits in a pocket is the *normal* case here.
     */
    private val mainHandler: Handler =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Handler.createAsync(Looper.getMainLooper())
        } else {
            Handler(Looper.getMainLooper())
        }

    /** Ids of the completion cards currently showing, so cleanup does not race. */
    private val completionCards: MutableSet<Int> = ConcurrentHashMap.newKeySet()

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
        sweepStaleCompletionCards()
    }

    /**
     * Removes a 已完成 card left behind by a process that died inside its window.
     *
     * The card is promoted, so it must be `ongoing` — and an ongoing notification
     * cannot be swiped away by the user. `setTimeoutAfter` covers the normal case
     * (the system enforces it even if this process is gone), but if the process is
     * killed before the system's timer fires, the card would sit in the shade
     * until the user force-stopped the app. This sweep is the belt to that
     * brace, and it needs no persisted state: the ids we own are derivable, and
     * anything in that range which this process is *not* currently showing can
     * only be a leftover.
     */
    private fun sweepStaleCompletionCards() {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val active = try {
            platform.activeNotifications
        } catch (e: Exception) {
            Diagnostics.log("debug", "读取活动通知失败: ${e.message}")
            return
        }
        val base = NotifyState.COMPLETION_CARD_BASE
        val end = base + NotifyState.COMPLETION_CARD_RANGE
        for (notification in active) {
            val id = notification.id
            if (id in base until end && !completionCards.contains(id)) {
                Diagnostics.info("清理上次进程遗留的完成卡片 (id=$id)")
                manager.cancel(id)
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
        for (id in update.removedIds) {
            manager.cancel(id)
        }
        // Live Updates / 流体云: only a couple of cards, chosen deliberately — see
        // PromotionPolicy. The group summary further down is intentionally NOT
        // promoted: Android refuses to promote a summary, and it would duplicate
        // what the per-task cards already say.
        val promoted = PromotionPolicy.choose(update.running)
        if (update.running.isNotEmpty() && lastPromotedCount != promoted.size) {
            lastPromotedCount = promoted.size
            Diagnostics.info(LiveUpdate.describeEligibility(context, channelImportanceMin = false))
        }
        if (update.running.isEmpty()) {
            manager.cancel(ID_GROUP_SUMMARY)
            return
        }
        for (item in update.running) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_LOCATE_TASK
                putExtra(MainActivity.EXTRA_SESSION_ID, item.task.sessionId)
                putExtra(MainActivity.EXTRA_TASK_TITLE, item.title)
                putExtra(MainActivity.EXTRA_WORKSPACE_KEY, item.workspaceKey)
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
                // BigTextStyle is one of the four styles Android will promote, so
                // no ProgressStyle is needed to reach the fluid cloud. (A
                // percentage bar would mean nothing for a coding task anyway.)
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
     * Two notifications go out, deliberately:
     *
     *  1. this one — the durable record in the 任务完成 channel, dismissible, with
     *     the task name and the last progress;
     *  2. [postCompletionCard] — a *promoted* card in the running-tasks channel
     *     whose status word is 已完成 (D15). That is what ColorOS's 流体云 pops
     *     out of the top strip and expands when a task finishes, which is the
     *     whole point of the shell being installed.
     */
    fun notifyCompleted(event: CompletionEvent) {
        if (!notificationsEnabled()) return
        val task = event.task
        val titleRes = if (event.failed) {
            R.string.notification_title_failed
        } else {
            R.string.notification_title_completed
        }
        val preview = task.preview.trim()
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
        postCompletionCard(event)
    }

    /**
     * The transient 已完成 card (D15).
     *
     * Cannot be a *state change* of the live card: a promoted notification must
     * be `ongoing`, and cancelling it is what makes the card collapse. So the
     * live card is cancelled by the ordinary running-task sync and this one takes
     * its place for [COMPLETION_CARD_MS], carrying the same task name with the
     * status word flipped to 已完成 — same content as the running card, only the
     * marker changes, which is what makes it read as "this task just finished"
     * rather than as a new, unrelated notification.
     *
     * Bounded twice on purpose: `setTimeoutAfter` is enforced by the system, so
     * the card still goes away if this process is killed during the window, and
     * the local handler removes it without waiting for the system.
     */
    private fun postCompletionCard(event: CompletionEvent) {
        val task = event.task
        val id = NotifyState.completionCardIdFor(event.workspaceKey, task.sessionId)
        // The same body the live card had. The fallback is the workspace's own
        // name (not the task title, which is already in the title row): a
        // completion card with no progress to show still says which workspace it
        // came from.
        val body = NotifyState.formatBody(task.preview, workspaceNameOf(event.workspaceKey))
        val pending = taskIntent(event.workspaceKey, task.sessionId, task.displayTitle, index = id)
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_zcode)
            .setContentTitle(NotifyState.formatTitle(TaskStatus.COMPLETED.label, task.displayTitle))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setTimeoutAfter(COMPLETION_CARD_MS)
        LiveUpdate.requestPromotion(builder, TaskStatus.COMPLETED.label)
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            Diagnostics.log("warn", "发送完成卡片失败: ${e.message}")
            return
        }
        completionCards.add(id)
        mainHandler.postDelayed({
            if (completionCards.remove(id)) {
                manager.cancel(id)
            }
        }, COMPLETION_CARD_MS + CARD_CLEANUP_SLACK_MS)
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
        completionCards.clear()
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
            putExtra(MainActivity.EXTRA_WORKSPACE_KEY, workspaceKey)
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
     * The last path segment of a workspace key — "default", "Mimo", … Used as the
     * body fallback on a card that has no progress text, where the full path
     * would not fit and the task title is already in the title row.
     */
    private fun workspaceNameOf(key: String): String {
        val name = key.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return name.ifEmpty { key }
    }

    /** Ids for the transient (completion / attention) notifications. */
    private var transientId = ID_TRANSIENT_BASE

    private fun nextId(): Int {
        transientId += 1
        if (transientId > ID_TRANSIENT_BASE + 100_000) {
            transientId = ID_TRANSIENT_BASE + 1
        }
        return transientId
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
         * How long the 已完成 card stays up. Long enough to be noticed and read
         * after the phone buzzes, short enough not to look like a stuck
         * notification on a task that is over.
         */
        private const val COMPLETION_CARD_MS = 15_000L

        /** Grace before the local cleanup, so it never races the system's own timeout. */
        private const val CARD_CLEANUP_SLACK_MS = 1_000L

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
