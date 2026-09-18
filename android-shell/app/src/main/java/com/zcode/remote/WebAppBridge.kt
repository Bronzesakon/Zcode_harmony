package com.zcode.remote

import android.webkit.JavascriptInterface
import com.zcode.remote.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only bridge the page can call. Exposed as `window.ZCodeShell`.
 *
 * Kept to two methods on purpose: everything the injected script needs to tell
 * the shell arrives through [postMessage] as JSON, and the only thing it needs
 * to ask is [config]. Methods run on the WebView's JavaBridge thread, so
 * anything they touch must be thread-safe (see ShellRuntime and Diagnostics).
 *
 * Security note: `addJavascriptInterface` exposes these methods to whatever the
 * WebView has loaded, which is why the shell refuses to navigate anywhere
 * outside *.z.ai (see MainActivity.shouldOverrideUrlLoading).
 */
class WebAppBridge(private val prefs: Prefs) {

    @JavascriptInterface
    fun postMessage(json: String) {
        ShellRuntime.onBridgeMessage(json)
    }

    /**
     * Synchronous by design: the injected script asks once at document-start,
     * before it has any async plumbing of its own.
     *
     * 除被动旁观开关之外还给出**每个已知工作区的在跑会话**（工作区 → 会话 id 列表）。原因是顺序：
     * `subscribeConversationV4` 必须**先于** `subscribeSessionsIndexV4` 发出，而那一刻
     * 注入层手里还没有本轮索引（会话清单随快照帧、在 ack 之后才到），JS 堆又随页面
     * 重载清空。原生进程活过页面重载，TaskStore 里一直有上一轮的运行集——由它给种子，
     * 这个鸡生蛋问题就不存在。
     *
     * 注意**空数组也要给**：这样注入层那句「原生运行集种子：N 个工作区」里 N=0 就明确
     * 等于"接线断了"，而不是"没有在跑的任务"——2026-09-15 第一次实测就是因为只给非空项，
     * 这两种情况在日志里长得一模一样。
     */
    @JavascriptInterface
    fun config(): String {
        return try {
            val sessions = JSONObject()
            for (workspace in ShellRuntime.store.workspaces()) {
                if (!sessions.has(workspace.key)) {
                    sessions.put(workspace.key, JSONArray())
                }
            }
            for ((key, sessionId) in ShellRuntime.store.runningTaskRefs()) {
                val list = sessions.optJSONArray(key) ?: JSONArray().also { sessions.put(key, it) }
                list.put(sessionId)
            }
            JSONObject()
                .put("passiveObserve", prefs.passiveObserve)
                .put("runningSessions", sessions)
                .toString()
        } catch (e: Exception) {
            // 兜底要与真实默认值一致：写错会让注入层在没有配置时反而去开订阅
            // ——2026-09-15 的教训（那时兜底里还带着 subscribeAll）。
            "{\"passiveObserve\":true}"
        }
    }
}
