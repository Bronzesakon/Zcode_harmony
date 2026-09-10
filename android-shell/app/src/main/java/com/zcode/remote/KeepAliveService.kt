package com.zcode.remote

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.notify.Notifier

/**
 * The foreground service whose only job is to keep the process — and therefore
 * the WebView holding the relay socket — alive while the app is backgrounded
 * (§5.5).
 *
 * Type `specialUse` rather than `dataSync`: dataSync is capped at 6 hours per
 * 24 on Android 15, which would silently kill the connection. `specialUse`
 * requires the matching permission *and* the PROPERTY_SPECIAL_USE_FGS_SUBTYPE
 * property in the manifest — omitting the property makes startForeground throw
 * on Android 14+.
 *
 * Note what this service does NOT do: it does not own the WebView, and it does
 * not stop itself when the task is removed from recents. Both are deliberate —
 * the socket only survives as long as the process does.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ShellRuntime.init(this)
        ShellRuntime.notifier().ensureChannels()
        ShellRuntime.onServiceCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ShellRuntime.notifier()
            .buildServiceNotification(0, getString(R.string.keepalive_text_idle))
        try {
            ServiceCompat.startForeground(
                this,
                Notifier.ID_SERVICE,
                notification,
                foregroundServiceType(),
            )
            ShellRuntime.updateServiceNotification()
        } catch (e: Exception) {
            // Android 14+ throws when the manifest lacks the specialUse
            // property or the permission. Log it loudly rather than crashing
            // the app: notifications still work without the service.
            Diagnostics.log("error", "startForeground 失败: ${e.message}")
        }
        return START_STICKY
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away normally tears everything down. We keep the
        // service (and the connection) alive; the user can stop it from the
        // notification or by force-stopping. Documented in the README.
        Diagnostics.info("任务从最近任务中移除，保活服务继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ShellRuntime.onServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
