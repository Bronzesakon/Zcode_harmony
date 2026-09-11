package com.zcode.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.RemoteUrl
import com.zcode.remote.core.ShellLog
import com.zcode.remote.core.enableThemeEdgeToEdge
import com.zcode.remote.core.padForStatusBarAndIme
import com.zcode.remote.databinding.ActivitySettingsBinding

/**
 * Settings, and — more importantly — the only window into what the injected
 * script is doing on a real device.
 *
 * The migration doc notes that the shell cannot be built locally, so every
 * runtime question has to be answered from the phone itself. Two things here
 * serve that: the survival readout (inbound-frame counters, refreshed once per
 * second while the screen is open) and the log export, because the file survives
 * the process and can be shared without a computer.
 *
 * The screen is laid out in the MiuiX design language (see
 * res/values/miuix_styles.xml for the metric and colour sourcing): a small top
 * app bar with a back arrow, sections introduced by a small bold title, and one
 * 16dp-rounded card per section holding title + summary rows with a chevron on
 * the right.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableThemeEdgeToEdge()
        super.onCreate(savedInstanceState)
        ShellRuntime.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForStatusBarAndIme()
        // A native screen: the strip behind the status bar is the page's own
        // surface colour of this screen, and the icons follow the system theme.
        // (The main screen overrides both from what the page reports.)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !dark
        binding.root.setBackgroundColor(
            ContextCompat.getColor(this, R.color.miuix_surface)
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.currentUrl.text = ShellRuntime.prefs().remoteUrl
            ?.let { RemoteUrl.toDisplayString(it) }
            ?: getString(R.string.settings_current_url_none)

        binding.rowChangeUrl.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).setAction(ACTION_CHANGE_URL))
            finish()
        }
        binding.rowReloadWeb.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RELOAD))
            finish()
        }

        binding.rowNotificationPermission.setOnClickListener { openNotificationSettings() }
        binding.rowClearNotifications.setOnClickListener {
            ShellRuntime.notifier().clearAll()
            toast(getString(R.string.notifications_cleared))
        }

        binding.rowBattery.setOnClickListener { requestBatteryExemption() }

        val switch: MaterialSwitch = binding.switchSubscribeAll
        switch.isChecked = ShellRuntime.prefs().subscribeAllWorkspaces
        switch.setOnCheckedChangeListener { _, checked ->
            ShellRuntime.prefs().subscribeAllWorkspaces = checked
            Diagnostics.info(if (checked) "已开启订阅所有工作区" else "已关闭订阅所有工作区")
            updateSubscribeAllState()
            // Apply live so the user does not need to reload the page.
            evaluate(
                "window.__zcodeShellSetSubscribeAll && window.__zcodeShellSetSubscribeAll($checked);"
            )
        }
        // MiuiX toggles from anywhere on the row, not just the switch itself.
        binding.rowSubscribeAll.setOnClickListener { switch.toggle() }

        binding.rowDiagnostics.setOnClickListener { showDiagnostics() }
        binding.rowShareLog.setOnClickListener { shareLog() }
        binding.rowClearDiagnostics.setOnClickListener {
            Diagnostics.clear()
            toast(getString(R.string.settings_diagnostics_cleared))
        }

        binding.versionText.text = versionText()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        updateBatteryState()
        updateSubscribeAllState()
        // Fresh counters matter most right after returning from the background.
        evaluate("window.__zcodeShellReportLiveness && window.__zcodeShellReportLiveness();")
        startRefreshLoop()
    }

    override fun onPause() {
        stopRefreshLoop()
        super.onPause()
    }

    private fun startRefreshLoop() {
        stopRefreshLoop()
        val runnable = object : Runnable {
            override fun run() {
                binding.survivalText.text = ShellRuntime.livenessSummary()
                handler.postDelayed(this, 1000)
            }
        }
        refresh = runnable
        handler.post(runnable)
    }

    private fun stopRefreshLoop() {
        refresh?.let { handler.removeCallbacks(it) }
        refresh = null
    }

    // ------------------------------------------------------------- state rows

    private fun updatePermissionState() {
        val enabled = ShellRuntime.notifier().notificationsEnabled()
        binding.notificationPermissionState.text = getString(
            if (enabled) R.string.settings_notification_permission_ok
            else R.string.settings_notification_permission_denied
        )
    }

    private fun updateBatteryState() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = power?.isIgnoringBatteryOptimizations(packageName) == true
        binding.batteryState.text = getString(
            if (exempt) R.string.settings_battery_exempt else R.string.settings_battery_not_exempt
        )
    }

    private fun updateSubscribeAllState() {
        binding.subscribeAllState.text = getString(
            if (ShellRuntime.prefs().subscribeAllWorkspaces) R.string.settings_subscribe_all_on
            else R.string.settings_subscribe_all_off
        )
    }

    private fun versionText(): String {
        val name = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val debug = if (BuildConfig.DEBUG) " (debug)" else ""
        return "v$name$debug · ${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}"
    }

    // ---------------------------------------------------------------- actions

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Diagnostics.log("warn", "打开通知设置失败: ${e.message}")
        }
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast(getString(R.string.settings_battery_exempt))
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            // Some ROMs have no such activity; fall back to the list.
            Diagnostics.log("warn", "申请电池优化白名单失败，改为打开设置列表: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (inner: Exception) {
                toast(getString(R.string.error_no_battery_settings))
            }
        }
    }

    /**
     * The diagnostics viewer: live buffer, survival readout, and the copy/share
     * actions that make a log pullable without a computer.
     */
    private fun showDiagnostics() {
        val container = ScrollView(this)
        val text = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = buildDiagnosticsText()
        }
        container.addView(text)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_diagnostics_title)
            .setView(container)
            .setPositiveButton(R.string.settings_diag_copy) { _, _ ->
                copyToClipboard(ShellLog.exportText(this))
            }
            .setNeutralButton(R.string.settings_diag_share) { _, _ -> shareLog() }
            .setNegativeButton(R.string.settings_diag_close, null)
            .show()
    }

    private fun buildDiagnosticsText(): String {
        val builder = StringBuilder()
        builder.append("【后台存活检查】\n")
        builder.append(ShellRuntime.livenessSummary()).append("\n")
        builder.append("保留服务：")
            .append(if (ShellRuntime.isServiceRunning()) "运行中" else "未运行")
            .append("\n")
        builder.append("注入脚本：")
            .append(if (ShellRuntime.isInjectedReady()) "已就绪" else "未就绪")
            .append("\n")
        builder.append("工作区：").append(ShellRuntime.store.workspaceCount()).append(" 个\n")
        ShellLog.currentFile()?.let { file ->
            builder.append("日志文件：").append(file.absolutePath)
                .append(" (").append(file.length() / 1024).append(" KB)\n")
        }
        builder.append("\n【本次会话日志】\n")
        builder.append(Diagnostics.asText())
        return builder.toString()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("zcode-log", text))
        toast(getString(R.string.copied))
    }

    private fun shareLog() {
        val file = ShellLog.exportToFile(this)
        if (file == null) {
            toast(getString(R.string.error_export_log))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ZCode 远程 诊断日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.settings_share_log)))
        } catch (e: Exception) {
            Diagnostics.log("warn", "分享日志失败: ${e.message}")
            copyToClipboard(ShellLog.exportText(this))
        }
    }

    /** Settings never owns the WebView; the runtime forwards to whomever does. */
    private fun evaluate(script: String) = ShellRuntime.evaluateJs(script)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_CHANGE_URL = "com.zcode.remote.action.CHANGE_URL"
    }
}
