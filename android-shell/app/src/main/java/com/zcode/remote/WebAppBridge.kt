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
            // 这个"给每个已知工作区塞空数组"的循环就是上面那条 2026-09-15 教训的**实现机制**，
            // 不是可以顺手删的冗余。今天这份载荷唯一的可达消费者是注入层那句计数日志
            // （对话候选那条路被 CONVERSATION_SUBSCRIBE_ENABLED=false 封死），而且它
            // **零测试覆盖**（tools/inject.test.js 的假 config 只给 passiveObserve）——
            // 删掉不会有任何门禁报警，只会让"接线断了"重新变得看不出来。
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
                // 注入层的前后台**初始值**：文档若在后台期间被重载（自愈/兜底重载都可能），
                // 没有这个字段它会自认前台——KICKED 被误判成"前台被顶→不自动干预"，自愈
                // 链路短路（2026-09-27 17:07 现场一环）。
                .put("foreground", ShellRuntime.isAppForeground())
                .put("runningSessions", sessions)
                .toString()
        } catch (e: Exception) {
            // 兜底要与真实默认值一致：写错会让注入层在没有配置时反而去开订阅
            // ——2026-09-15 的教训（那时兜底里还带着 subscribeAll）。
            "{\"passiveObserve\":true}"
        }
    }
}
