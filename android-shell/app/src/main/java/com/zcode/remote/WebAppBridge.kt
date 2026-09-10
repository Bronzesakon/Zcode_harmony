package com.zcode.remote

import android.webkit.JavascriptInterface
import com.zcode.remote.core.Prefs
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
     */
    @JavascriptInterface
    fun config(): String {
        return try {
            JSONObject()
                .put("subscribeAll", prefs.subscribeAllWorkspaces)
                .toString()
        } catch (e: Exception) {
            "{\"subscribeAll\":true}"
        }
    }
}
