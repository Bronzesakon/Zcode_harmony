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

    /**
     * 档 0 观测（2026-09-19）：**退后台那一刻的视图快照**，JSON 字符串（`{at, snap}`）。
     *
     * **为什么必须落盘**（真机 22:32 实测踩到）：这个快照原本只存内存，而档 0 要抓的
     * 假死形态（S3）恰恰就是「**进程被杀** + 回前台」——进程一死，内存里的"离开时在不在
     * 对话里"随之消失，判据只剩一行「未采到快照」，**观测不到它要观测的东西**。
     * 所以它必须跨进程存活。真机 22:31:45 离开（chat=true / 会话 sess_527fb3e6）→
     * 22:32:30 进程被杀 → 22:32:44 回前台，就是靠这条才判得出来。
     *
     * 内容与敏感度：只有视图布尔量、行数与**会话 id**（`sess_…`，与日志里已有的
     * `会话运行态：在跑 · sess_…` 同级；**不是** URL 里那份 sid/hash/mid 凭证）。
     * 本文件已被 data_extraction_rules 排除在云备份与设备迁移之外。
     *
     * 生命周期：退后台写入 → 回前台判定时消费并清空。判据太旧（>2h）时不判、只记一行。
     */
    var viewAtLeave: String?
        get() = prefs.getString(KEY_VIEW_AT_LEAVE, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove(KEY_VIEW_AT_LEAVE) else putString(KEY_VIEW_AT_LEAVE, value)
            }.apply()
        }

    /**
     * 档 3（2026-09-19）：**对话恢复的跨进程记账**，JSON 字符串 `{count, at}`。
     *
     * **为什么必须落盘**（真机 16:28 实测踩到，是本轮第二个"内存态在最需要的场景下失效"）：
     * 注入层原本用 `sessionStorage` 计数 + 内存里的 `lastResumeHealAt` 做限流，
     * 而档 3 要治的 S3 恰恰是「**进程被杀** + 回前台」——进程一死，**两道限流同时归零**，
     * 于是每次冷启动都能再重载一次，连续触发会变成"每次回前台都闪一次"。
     * 真机现场：第二次 S3 仍打「第 1/2 次」（本该是第 2/2 次被计数，或直接被限流拦下）。
     *
     * 语义：`count` = 连续自动恢复次数（**配对成功即清零**，与注入层同口径）；
     * `at` = 上次恢复的时刻（ms），供"距上次不足 5 分钟就跳过"的闸门使用。
     *
     * 内容与敏感度：只有两个数字，无任何标识信息。
     */
    var conversationRecovery: String?
        get() = prefs.getString(KEY_CONVERSATION_RECOVERY, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) {
                    remove(KEY_CONVERSATION_RECOVERY)
                } else {
                    putString(KEY_CONVERSATION_RECOVERY, value)
                }
            }.apply()
        }

    companion object {
        private const val NAME = "zcode_remote"
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_WEBVIEW_DEBUG = "webview_debugging"
        private const val KEY_PASSIVE_OBSERVE = "passive_observe"
        private const val KEY_NOTIF_ASKED = "notification_permission_requested"
        private const val KEY_VIEW_AT_LEAVE = "view_at_leave"
        private const val KEY_CONVERSATION_RECOVERY = "conversation_recovery"
    }
}
