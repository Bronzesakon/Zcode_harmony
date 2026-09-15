package com.zcode.remote.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted shell settings.
 *
 * Deliberately tiny, and deliberately NOT backed up: the stored URL contains
 * session credentials, and res/xml/data_extraction_rules.xml excludes this
 * preference file from both cloud backup and device transfer.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The remote-control URL, credentials and all. */
    var remoteUrl: String?
        get() = prefs.getString(KEY_REMOTE_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove(KEY_REMOTE_URL) else putString(KEY_REMOTE_URL, value)
            }.apply()
        }

    /**
     * D7: 主动订阅所有工作区（壳自己在页面的 socket 上开 bridge）。
     *
     * ⚠️ **默认已改为关（2026-09-15 真机 A/B 定案）。**
     *
     * 开着的时候壳会在**页面那条 socket** 上给 6–7 个工作区各开一座 bridge + 索引订阅，
     * 而**页面自己早就订着它们**（日志里的 `sessions-index/E:\Mimo` 就是页面订的）——既是
     * 纯重复（违反 README「实现要点」13 的不变式），又因为开桥在启动瞬发里抢先而必然发生。
     * 后果：用户点进对话后**页面只剩"工作中"+转圈、内容全程不来、连桌面端点暂停也不更新**。
     *
     * 用户实测（关掉本开关）：第 63→67 次、以及锁屏解锁全程第 72→75 次回复，**都顺利推进**。
     *
     * 开关留着：桌面端哪天解决了同一 socket 上的争用，可以再打开换回"全工作区通知"。
     */
    var subscribeAllWorkspaces: Boolean
        get() = prefs.getBoolean(KEY_SUBSCRIBE_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCRIBE_ALL, value).apply()

    /** Enables WebView remote debugging in release builds (troubleshooting). */
    var webViewDebugging: Boolean
        get() = prefs.getBoolean(KEY_WEBVIEW_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_WEBVIEW_DEBUG, value).apply()

    /**
     * **被动旁观总开关**（诊断用，默认开）。
     *
     * 关掉它 = 注入层只保留"零侵入"的三件事：滚动条归零 CSS、状态栏取色用的页面状态
     * 上报、页面日志汇（`window.zcode.log`）；**不再 hook WebSocket、不再逐帧观测、
     * 不发心跳探针**。
     *
     * 为什么需要它（2026-09-15 真机二分）：用户实测"关掉订阅所有工作区后能从 1 轮撑到
     * 4 轮（约 40 秒）才停"，特征指向**我们对每一帧的同步处理**（JSON.parse + base64
     * 分片重组全在页面主线程）拖慢了页面自己的处理与 ack，桌面端随后判降级停推、页面
     * 转为自动重连。这个开关用来把"是不是旁观本身拖死的"一次判定清楚：
     * 关掉后页面若长时间稳定 → 病根确在逐帧处理；若仍停 → 病根在别处（如可见性劫持）。
     *
     * 由 adb 诊断指令切换：`--es diag_cmd passive_off` / `passive_on`（都会重载页面）。
     */
    var passiveObserve: Boolean
        get() = prefs.getBoolean(KEY_PASSIVE_OBSERVE, true)
        set(value) = prefs.edit().putBoolean(KEY_PASSIVE_OBSERVE, value).apply()

    /**
     * Whether the runtime has ever finished a session-index handshake. Used to
     * avoid asking for the notification permission before there is anything to
     * notify about (see §5.6).
     */
    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    companion object {
        private const val NAME = "zcode_remote"
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_SUBSCRIBE_ALL = "subscribe_all_workspaces"
        private const val KEY_WEBVIEW_DEBUG = "webview_debugging"
        private const val KEY_PASSIVE_OBSERVE = "passive_observe"
        private const val KEY_NOTIF_ASKED = "notification_permission_requested"
    }
}
