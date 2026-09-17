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
     * D7「订阅所有工作区」开关**已删除**（2026-09-17）。
     *
     * 它让注入层在**页面自己那条 socket** 上给每个工作区开桥 + 订索引，2026-09-15 真机
     * A/B 定罪：与页面自己的订阅争用 → 页面卡"工作中"+转圈、内容全程不来。那既是纯重复，
     * 也违反只读壳契约（前台一帧都不写页面 socket）。功能由 Tier2（原生自开 socket）+
     * 发现链承载，所以整条（pref / 设置页 / bridge 字段 / 注入层主动开桥）一起删掉。
     *
     * 旧键 `subscribe_all_workspaces` 不再读写：留一条已死的 SharedPreferences 对壳无害，
     * 删除它在真机上反而是"多一次写"。**永远不要**把这条链接回来。
     */

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
        private const val KEY_WEBVIEW_DEBUG = "webview_debugging"
        private const val KEY_PASSIVE_OBSERVE = "passive_observe"
        private const val KEY_NOTIF_ASKED = "notification_permission_requested"
    }
}
