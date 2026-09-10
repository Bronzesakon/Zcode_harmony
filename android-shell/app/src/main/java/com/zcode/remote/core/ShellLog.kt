package com.zcode.remote.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The shell's own log file, independent of logcat and of any development
 * machine.
 *
 * Why this exists: the shell's runtime behaviour can only be judged on a real
 * device, and the questions that matter are the ones that need *historical*
 * evidence — "was the relay still delivering frames during the 30 minutes it
 * spent in the background?", "did the injected script ever install?", "why did
 * the process die?" A live `adb logcat` session cannot answer those after the
 * fact, and a process that is killed by the OS takes its ring buffer with it. So
 * every diagnostic is also appended to a file that survives the process, and
 * that file can be shared straight out of the settings screen.
 *
 * Location: `Android/data/<package>/files/logs/` (app-specific external storage —
 * no permission required, removable via the settings screen). Falls back to
 * internal storage when no external volume is mounted.
 */
object ShellLog {

    private const val TAG = "ZCodeRemote"
    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "zcode-shell.log"
    private const val MAX_BYTES = 512L * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var logFile: File? = null
    private var initialized = false
    private val lock = Any()

    /** Absolute path of the current log file, for display/sharing. */
    fun currentFile(): File? = logFile

    /**
     * Called once from Application.onCreate. Also installs the crash handler, so
     * a fatal error still leaves a readable trace in the file.
     */
    fun init(context: Context, installCrashHandler: Boolean = true) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "无法创建日志目录: ${dir.absolutePath}")
            }
            logFile = File(dir, FILE_NAME)
            rotateIfNeeded()
            append(
                "info",
                "======== 进程启动 v${appVersion(context)} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT} " +
                    "· WebView ${webViewVersion(context)} ========",
                mirrorToLogcat = false,
            )
        }
        if (installCrashHandler) {
            installCrashHandler()
        }
    }

    fun append(level: String, message: String, mirrorToLogcat: Boolean = true) {
        val line = "${timeFormat.format(Date())}  ${level.uppercase(Locale.US)}  $message"
        if (mirrorToLogcat) {
            when (level) {
                "error" -> Log.e(TAG, line)
                "warn" -> Log.w(TAG, line)
                "debug" -> Log.d(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
        synchronized(lock) {
            val file = logFile ?: return
            try {
                if (file.length() > MAX_BYTES) {
                    rotate(file)
                }
                file.appendText(line + "\n")
            } catch (e: Exception) {
                // Never let logging break the app.
                Log.w(TAG, "写日志失败: ${e.message}")
            }
        }
    }

    fun markProcessExit(reason: String) {
        append("info", "======== 进程结束：$reason ========", mirrorToLogcat = false)
    }

    /** A shareable copy: the in-memory buffer plus the tail of the file. */
    fun exportText(context: Context): String {
        val builder = StringBuilder()
        builder.append("ZCode 远程 诊断日志\n")
        builder.append("导出时间：${fileStamp.format(Date())}\n")
        builder.append("版本：v${appVersion(context)} · ${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}\n")
        builder.append("\n---- 本次会话（内存缓冲）----\n")
        builder.append(Diagnostics.asText())
        builder.append("\n\n---- 日志文件（含历史会话）----\n")
        val file = logFile
        if (file != null && file.exists()) {
            builder.append(file.readText())
        } else {
            builder.append("<日志文件不可用>\n")
        }
        return builder.toString()
    }

    /** Writes [exportText] to a shareable file and returns it. */
    fun exportToFile(context: Context): File? {
        return try {
            val dir = File(context.cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "zcode-remote-log.txt")
            out.writeText(exportText(context))
            out
        } catch (e: Exception) {
            Log.w(TAG, "导出日志失败: ${e.message}")
            null
        }
    }

    fun clearFile() {
        synchronized(lock) {
            try {
                logFile?.writeText("")
                append("info", "日志已清空", mirrorToLogcat = false)
            } catch (e: Exception) {
                Log.w(TAG, "清空日志失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ private

    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.exists() && file.length() > MAX_BYTES) {
            rotate(file)
        }
    }

    private fun rotate(file: File) {
        try {
            val previous = File(file.parentFile, "$FILE_NAME.1")
            if (previous.exists()) previous.delete()
            file.renameTo(previous)
        } catch (e: Exception) {
            Log.w(TAG, "轮转日志失败: ${e.message}")
            file.delete()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = StringWriter()
                throwable.printStackTrace(PrintWriter(stack))
                append("error", "未捕获异常 (${thread.name}):\n$stack", mirrorToLogcat = false)
            } catch (e: Exception) {
                // ignore
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun webViewVersion(context: Context): String = try {
        android.webkit.WebSettings.getDefaultUserAgent(context).let { ua ->
            // The UA ends with "Chrome/<version> Mobile Safari/<version>".
            Regex("Chrome/([0-9.]+)").find(ua)?.groupValues?.getOrNull(1) ?: "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }
}
