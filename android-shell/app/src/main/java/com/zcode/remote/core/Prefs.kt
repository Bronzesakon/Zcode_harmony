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
     * D7: subscribe to every workspace, not just the one the page shows.
     * Defaults to on; the switch exists so a user whose desktop dislikes the
     * extra bridges can fall back to the passive-only path without a rebuild.
     */
    var subscribeAllWorkspaces: Boolean
        get() = prefs.getBoolean(KEY_SUBSCRIBE_ALL, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCRIBE_ALL, value).apply()

    /** Enables WebView remote debugging in release builds (troubleshooting). */
    var webViewDebugging: Boolean
        get() = prefs.getBoolean(KEY_WEBVIEW_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_WEBVIEW_DEBUG, value).apply()

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
        private const val KEY_NOTIF_ASKED = "notification_permission_requested"
    }
}
