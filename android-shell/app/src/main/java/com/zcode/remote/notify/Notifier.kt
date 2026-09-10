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
import com.zcode.remote.core.TaskStore

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
            val notification = NotificationCompat.Builder(context, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_zcode)
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
                .setContentIntent(pending)
                .setGroup(GROUP_RUNNING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
            try {
                manager.notify(item.id, notification)
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

    /** D10: audible, dismissible, one per completion. */
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

        private const val ID_TRANSIENT_BASE = 2_000_000

        private const val REQUEST_SERVICE = 10

        /** Describes a running task for the service notification body. */
        fun runningBody(tasks: List<TaskStore.RunningNotification>): String {
            if (tasks.isEmpty()) return ""
            val lines = tasks.take(5).map { "• ${it.title} — ${it.body}" }
            val text = lines.joinToString("\n")
            return if (text.length > 600) text.take(597) + "…" else text
        }
    }
}
