package com.zcode.remote.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.zcode.remote.core.Diagnostics

/**
 * Android 16 Live Updates, which ColorOS 16 surfaces as **流体云**.
 *
 * Route chosen: the platform path, not OPPO's 泛在服务 card framework. OPPO's own
 * cards need an enterprise account, a white-listed invitation and an authorisation
 * code bound to the package name and signing SHA-1, whereas ColorOS 16 consumes
 * standard Android 16 Live Updates directly. The capability check and the UX rules
 * live in `ColorOS_docs/06-Android原生Live Updates（双兼容路径）/` (local-only archive).
 *
 * ### Why the extras are written by hand instead of calling the API
 *
 * `Notification.Builder.setRequestPromotedOngoing()` and `setShortCriticalText()`
 * are API 36 additions, and the androidx wrappers for them only exist in
 * androidx.core **1.17.0** — whose published AAR declares `minCompileSdk=36` and
 * `minAndroidGradlePluginVersion=8.9.1`. Adopting it would force Gradle, AGP and
 * compileSdk up in the same change, for a feature whose entire platform-side
 * effect is two entries in the notification's extras bundle. From androidx's own
 * source:
 *
 * ```
 * public Builder setRequestPromotedOngoing(boolean requestPromotedOngoing) {
 *     getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, requestPromotedOngoing);
 *     return this;
 * }
 * ```
 *
 * with `EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"` and
 * `EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"` — the same constants
 * the platform reads back in `Notification.getShortCriticalText()`.
 *
 * So the extras are written directly, using those literal keys. They are inert
 * below API 36. **When the toolchain is next bumped to compileSdk 36, replace
 * [requestPromotion] with the androidx calls and delete nothing else.**
 *
 * ### The promotion conditions (all of them are required)
 *
 * Standard style (BigTextStyle qualifies) · `POST_PROMOTED_NOTIFICATIONS`
 * declared in the manifest · promotion requested · ongoing · has a content title ·
 * no custom content view · not a group summary · not colorized · channel
 * importance above `IMPORTANCE_MIN`. [describeEligibility] reports them for the
 * diagnostics screen, because on a real device that is the only way to tell
 * whether the fluid cloud should have shown something.
 */
object LiveUpdate {

    /** Live Updates were introduced in Android 16. */
    const val MIN_API = 36

    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /**
     * True when the OS would accept a promoted notification at all: Android 16+
     * and the user has not switched the app's promoted notifications off in
     * Settings (`NotificationManager.canPostPromotedNotifications`, API 36,
     * reached reflectively because the compile SDK is still 35).
     */
    fun canPromote(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) return false
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val method = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            method.invoke(manager) as? Boolean ?: false
        } catch (e: Exception) {
            Diagnostics.log("warn", "查询流体云可用性失败: ${e.message}")
            false
        }
    }

    /**
     * Marks [builder] as requesting promotion, with [shortCriticalText] as the
     * status-bar chip (`运行中` / `等待确认`). No-op below API 36.
     */
    fun requestPromotion(builder: NotificationCompat.Builder, shortCriticalText: String?) {
        if (Build.VERSION.SDK_INT < MIN_API) return
        val extras = Bundle()
        extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        if (!shortCriticalText.isNullOrEmpty()) {
            extras.putString(EXTRA_SHORT_CRITICAL_TEXT, shortCriticalText)
        }
        builder.addExtras(extras)
    }

    /** Human-readable status of every promotion condition, for diagnostics. */
    fun describeEligibility(context: Context, channelImportanceMin: Boolean): String {
        if (Build.VERSION.SDK_INT < MIN_API) {
            return "流体云: 本机为 API ${Build.VERSION.SDK_INT}，需 Android 16(API 36) 及以上"
        }
        val allowed = canPromote(context)
        return buildString {
            append("流体云: ")
            append(if (allowed) "可用" else "系统已关闭本应用的推广通知")
            append(" · 样式=BigText ✓ · ongoing ✓ · 有标题 ✓ · 无自定义视图 ✓ · 非组摘要 ✓")
            append(" · 未着色 ✓")
            append(" · 渠道重要性>MIN ")
            append(if (channelImportanceMin) "✗" else "✓")
        }
    }
}
