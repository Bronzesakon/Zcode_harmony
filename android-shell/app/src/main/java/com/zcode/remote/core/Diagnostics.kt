package com.zcode.remote.core

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics.
 *
 * This exists because the shell cannot be built or debugged on a development
 * machine (migration doc, D13): the only way to find out whether the WebSocket
 * hook, the visibility hijack or the workspace subscription work on a real device
 * is to have the injected script report back and then read it in the app.
 *
 * Every line goes to three places at once:
 *   * logcat, for anyone who does have adb
 *   * a ring buffer, shown immediately on the settings screen
 *   * [ShellLog]'s file, which survives the process being killed — the only
 *     place the 30-minute background test can be read back from afterwards
 *
 * Credentials must never land here, so [log] runs every message through
 * [redact].
 */
object Diagnostics {

    private const val CAPACITY = 400

    private val entries: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var listener: (() -> Unit)? = null

    fun setListener(callback: (() -> Unit)?) {
        listener = callback
    }

    fun log(level: String, message: String) {
        val safe = redact(message)
        entries.add("${timeFormat.format(Date())}  ${level.uppercase(Locale.US)}  $safe")
        synchronized(entries) {
            while (entries.size > CAPACITY) {
                entries.removeAt(0)
            }
        }
        ShellLog.append(level, safe)
        listener?.invoke()
    }

    fun info(message: String) = log("info", message)

    fun snapshot(): List<String> = synchronized(entries) { ArrayList(entries) }

    fun clear() {
        synchronized(entries) { entries.clear() }
        ShellLog.clearFile()
        listener?.invoke()
    }

    fun asText(): String = snapshot().joinToString("\n")

    /**
     * Strips anything that looks like a credential-bearing query string.
     * The remote URL carries `sid`/`hash`/`mid`; those must never be persisted
     * into logs or shown on screen.
     */
    fun redact(message: String): String {
        val remote = message.indexOf("/remote")
        if (remote < 0) return message
        val query = message.indexOf('?', remote)
        if (query < 0) return message
        var end = message.length
        for (i in query until message.length) {
            val c = message[i]
            if (c == ' ' || c == '\n' || c == '\t') {
                end = i
                break
            }
        }
        return message.substring(0, query) + "?<redacted>" + message.substring(end)
    }
}
