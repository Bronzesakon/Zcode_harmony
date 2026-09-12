package com.zcode.remote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.zcode.remote.core.Diagnostics
import com.zcode.remote.core.PageBarColor
import com.zcode.remote.core.PageBarState
import com.zcode.remote.core.PageTheme
import com.zcode.remote.core.Prefs
import com.zcode.remote.core.RemoteUrl
import com.zcode.remote.core.UploadMime
import com.zcode.remote.core.enableThemeEdgeToEdge
import com.zcode.remote.core.padForStatusBarAndIme
import com.zcode.remote.databinding.ActivityMainBinding

/**
 * The shell: a WebView on the real remote page, plus the native pieces the page
 * cannot provide (notifications, background survival, QR onboarding).
 *
 * Three things in here are load-bearing and easy to break by "cleaning up":
 *
 *  1. The document-start script must be installed BEFORE loadUrl, and must not
 *     be re-installed for the same URL.
 *  2. `webView.onPause()` must never be called. It suspends the WebView's
 *     timers, which stops the page's relay heartbeat — the opposite of what a
 *     background-survival shell wants.
 *  3. Back does not finish the Activity: it backgrounds the task, so the
 *     connection survives the way it does in the HarmonyOS build.
 *
 * There is no app bar. The page starts directly under the status bar, and the
 * strip above it is painted with the page's own top-surface colour, which the
 * injected layer reports by name (see core/PageBarColor.kt). The former overflow
 * menu's three actions live on the launcher long-press menu instead
 * (res/xml/shortcuts.xml) plus the two native panels.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Which page state the status-bar strip is currently painted for. */
    private var pageBarState: PageBarState = PageBarState.DEFAULT

    /** The theme the page resolved for itself; null until it says. */
    private var pageTheme: PageTheme? = null

    /** Script used when the WebView lacks document-start support (fallback). */
    private var fallbackScript: String? = null

    /** Set when a notification tap asked us to locate a task. */
    private var pendingLocate: Pair<String, String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var locateAttempts = 0
    /** The host the injected script was installed for, if any. */
    private var injectedHost: String? = null

    /**
     * Console lines captured from the page for this load. The page is a chatty
     * production SPA and this log is also written to a file that the user is
     * expected to share, so the capture is capped per load rather than endless.
     */
    private var consoleLines = 0

    /** Uptime at onPageStarted, for the "how long did the page take" line. */
    private var pageStartedAt = 0L

    /**
     * onPageFinished callbacks seen for the current document. WebView fires it
     * more than once (SPA history changes, late subframes), and only the first
     * one can be compared against the load start — see onPageFinished.
     */
    private var finishCallbacks = 0

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrEmpty()) {
            return@registerForActivityResult
        }
        val url = RemoteUrl.extractFromScannedText(contents)
        if (url == null) {
            toast(getString(R.string.error_bad_url))
        } else {
            Diagnostics.info("扫码得到有效链接")
            applyUrl(url)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Diagnostics.info(if (granted) "通知权限已授予" else "通知权限被拒绝")
        }

    /**
     * The `<input type="file">` callback currently waiting for a pick. It lives
     * here rather than in the dialog because the result arrives after the dialog
     * is gone, and it must be invoked exactly once or the page's upload hangs.
     */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    // Four launchers rather than two: FileChooserParams says whether the page
    // asked for one file or many, and each picker has its own contract. Both
    // photo contracts fall back to ACTION_OPEN_DOCUMENT by themselves on devices
    // without the photo picker, so no manual fallback is needed.

    private val photoPickerSingle = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> deliverPickedFiles(uri?.let { listOf(it) }.orEmpty()) }

    private val photoPickerMultiple = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_UPLOAD_ITEMS),
    ) { uris -> deliverPickedFiles(uris) }

    private val documentPickerSingle = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> deliverPickedFiles(uri?.let { listOf(it) }.orEmpty()) }

    private val documentPickerMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> deliverPickedFiles(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede setContentView: see enableThemeEdgeToEdge().
        enableThemeEdgeToEdge()
        super.onCreate(savedInstanceState)
        ShellRuntime.init(this)
        prefs = ShellRuntime.prefs()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // targetSdk 35 forces edge-to-edge. The top inset becomes the page's top
        // padding so the page's own header is not covered by the status bar's
        // clock; the bottom is left immersed on purpose (see the function).
        binding.root.padForStatusBarAndIme()
        // Before the page says anything, the strip takes the boot surface for the
        // *current* system appearance, so there is never a frame with light icons
        // on a light strip.
        applyStatusBarSurface()

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnManual.setOnClickListener { showManualEntry() }
        binding.btnRetry.setOnClickListener { reloadPage() }
        binding.btnChangeUrl.setOnClickListener { showSetup() }
        binding.btnSettings.setOnClickListener { openSettings() }

        configureWebView()
        installBackHandling()
        // The intent may already have decided what the screen should be — asking
        // for a new link must not be undone by the automatic load right below.
        val intentHandled = handleIntent(intent)
        ShellRuntime.setJsEvaluator { script -> binding.webview.evaluateJavascript(script, null) }
        ShellRuntime.setPageStateListener { state, theme -> onPageStateReported(state, theme) }

        if (!intentHandled) {
            val stored = prefs.remoteUrl
            if (stored == null) {
                showSetup()
            } else {
                applyUrl(stored)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // --------------------------------------------------------------- web view

    private fun configureWebView() {
        val webView = binding.webview
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Deliberately NOT setting useWideViewPort / loadWithOverviewMode:
            // they exist to make legacy desktop pages fit, and on a responsive
            // SPA they can make the viewport width disagree with the visible
            // area (content then looks off-centre relative to the scrollbar).
            // The default UA is kept deliberately: the page does its own mobile
            // feature detection and a custom UA could change its behaviour.
        }
        webView.addJavascriptInterface(WebAppBridge(prefs), BRIDGE_NAME)
        if (BuildConfig.DEBUG || prefs.webViewDebugging) {
            WebView.setWebContentsDebuggingEnabled(true)
            Diagnostics.info("WebView 远程调试已启用")
        }
        // §5.5: stops the renderer being deprioritised/reclaimed while the app
        // is backgrounded. This is an INSTANCE method — verified against AOSP
        // WebView.java (`public void setRendererPriorityPolicy(int, boolean)`),
        // not the static call the migration doc's shorthand suggested.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (RemoteUrl.isAllowed(uri)) return false
                // Anything outside the remote page's own origin is handed to
                // the system browser: the JS bridge must never be exposed to a
                // page we do not control.
                Diagnostics.info("外部链接交给系统浏览器: ${uri.host}")
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: ActivityNotFoundException) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                consoleLines = 0
                finishCallbacks = 0
                pageStartedAt = SystemClock.elapsedRealtime()
                Diagnostics.info("网页开始加载")
                // A fresh document boots with the page background at the top (the
                // boot shell), so drop back to that surface until the new document
                // reports otherwise. Without this a reload keeps the *previous*
                // document's header colour under the status bar.
                onPageStateReported(PageBarState.BOOT.token, null)
                fallbackScript?.let { script ->
                    // No document-start support: inject as early as we can. The
                    // page may already have opened its socket, which is exactly
                    // why document-start is preferred.
                    view.evaluateJavascript(script, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                finishCallbacks += 1
                val elapsed = if (pageStartedAt > 0L) {
                    SystemClock.elapsedRealtime() - pageStartedAt
                } else {
                    -1L
                }
                // Only the FIRST finish callback can be measured against the load
                // start. Later ones (SPA route changes, late frames) share that
                // start point, so measuring them produced absurd numbers like
                // "用时 736007 ms" for a document whose own timing said ttfb=397 ms.
                // Report the index instead and let the number stand alone.
                if (elapsed >= 0L) {
                    pageStartedAt = 0L
                }
                // The console count is printed on purpose: it is the one number
                // that says whether the page's own logs are reaching the file at
                // all, which a silent "0 行" makes obvious.
                Diagnostics.info(
                    "网页加载完成(第 ${finishCallbacks} 次回调)" +
                        (if (elapsed >= 0L) "，用时 $elapsed ms" else "") +
                        " · ${RemoteUrl.toDisplayString(url)}" +
                        " · 控制台已捕获 $consoleLines 行",
                )
                hideError()
                maybeRequestNotificationPermission()
                tryLocate()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                val description = error.description?.toString().orEmpty()
                Diagnostics.log("warn", "网页加载失败: $description")
                showError(getString(R.string.error_network) + if (description.isEmpty()) "" else "\n($description)")
            }
        }

        // File uploads. WebView ships no default chooser, so without this the
        // page's <input type="file"> silently does nothing.
        webView.webChromeClient = object : WebChromeClient() {
            /**
             * The page's own console, routed into the shell's diagnostics.
             *
             * This exists because the questions that matter here — "why does a
             * conversation take ten seconds to open", "what did the app do when
             * the fluid cloud showed up" — are answered by the page's own logs,
             * and the shell has no other way to see them: without adb there is no
             * remote inspector, and the log file is the only channel the phone
             * can hand back. The injected script reports its own numbers the same
             * way (see assets/inject.js), so both land in one timeline.
             */
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val level = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "error"
                    ConsoleMessage.MessageLevel.WARNING -> "warn"
                    else -> "web"
                }
                if (consoleLines > MAX_CONSOLE_LINES) {
                    return true
                }
                consoleLines += 1
                if (consoleLines == MAX_CONSOLE_LINES + 1) {
                    Diagnostics.log(
                        "info",
                        "[web] 控制台输出已达 ${MAX_CONSOLE_LINES} 行上限，本次加载后续省略",
                    )
                    return true
                }
                Diagnostics.log(level, "[web:${msg.lineNumber()}] ${condense(msg.message())}")
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    Diagnostics.info("网页渲染进度 100%")
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_SAVE) {
                    // Saving is a separate flow (ACTION_CREATE_DOCUMENT) and is
                    // not implemented yet. Returning false keeps the previous
                    // behaviour instead of holding a callback we never fire.
                    Diagnostics.log("warn", "网页请求保存文件，暂未实现（MODE_SAVE）")
                    return false
                }
                // A second request while one is still pending would strand the
                // first callback and freeze that input.
                if (pendingFileCallback != null) {
                    Diagnostics.log("warn", "上传请求：上一个选择仍未返回，先取消它")
                }
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                showUploadSourceDialog(fileChooserParams)
                return true
            }
        }
    }

    /**
     * Installs the injected scripts for [url]. Called before loadUrl so the hook
     * is in place before the page opens its WebSocket — the single most
     * important timing constraint in this design (§5.1).
     */
    private fun installInjection(url: String) {
        val webView = binding.webview
        val script = (readAsset("zcode-protocol.js") ?: "") + "\n" + (readAsset("inject.js") ?: "")
        if (script.isBlank()) {
            Diagnostics.log("error", "注入脚本缺失，通知功能将不可用")
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fallbackScript = null
            val rules = setOf(RemoteUrl.originRule(url), "https://*.$ALLOWED_ROOT")
            WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
            Diagnostics.info("已安装 document-start 注入 (${rules.joinToString()})")
        } else {
            fallbackScript = script
            Diagnostics.log(
                "warn",
                "系统 WebView 不支持 document-start 注入，回退到 onPageStarted（可能漏掉首帧）",
            )
        }
    }

    private fun readAsset(name: String): String? = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Diagnostics.log("error", "读取 $name 失败: ${e.message}")
        null
    }

    // ------------------------------------------------------------------- url

    private fun applyUrl(url: String) {
        prefs.remoteUrl = url
        Diagnostics.info("加载远程页面: ${RemoteUrl.toDisplayString(url)}")
        binding.webview.visibility = View.VISIBLE
        binding.setupPanel.visibility = View.GONE
        hideError()
        val host = try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }
        if (injectedHost != host) {
            installInjection(url)
            injectedHost = host
        }
        ShellRuntime.ensureServiceRunning(this)
        binding.webview.loadUrl(url)
    }

    private fun reloadPage() {
        val url = prefs.remoteUrl ?: return showSetup()
        hideError()
        binding.webview.loadUrl(url)
    }

    private fun showSetup() {
        binding.webview.visibility = View.GONE
        binding.errorPanel.visibility = View.GONE
        binding.setupPanel.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.webview.visibility = View.GONE
        binding.setupPanel.visibility = View.GONE
        binding.errorPanel.visibility = View.VISIBLE
        binding.errorText.text = message
    }

    private fun hideError() {
        binding.errorPanel.visibility = View.GONE
        if (prefs.remoteUrl != null) {
            binding.webview.visibility = View.VISIBLE
        }
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.setup_scan))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        try {
            scanLauncher.launch(options)
        } catch (e: Exception) {
            Diagnostics.log("warn", "无法启动扫码: ${e.message}")
            toast(getString(R.string.error_no_scanner))
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = RemoteUrl.extractFromScannedText(text)
        if (url == null) {
            toast(getString(R.string.error_clipboard_empty))
        } else {
            applyUrl(url)
        }
    }

    private fun showManualEntry() {
        val input = EditText(this).apply {
            hint = getString(R.string.setup_manual_hint)
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_manual_title)
            .setView(container)
            .setPositiveButton(R.string.setup_ok) { _, _ ->
                val url = RemoteUrl.normalize(input.text?.toString())
                if (url == null) toast(getString(R.string.error_bad_url)) else applyUrl(url)
            }
            .setNegativeButton(R.string.setup_cancel, null)
            .show()
    }

    // ------------------------------------------------------- status bar surface
    //
    // The app bar is gone, so the strip behind the status bar *is* the top of the
    // page's own chrome and has to be painted with the page's top-surface colour.
    // The injected layer reports names (which page state, which theme) and the
    // fixed table in core/PageBarColor.kt turns them into literals — the shell
    // never reads a colour out of the page at runtime, same rule as the
    // HarmonyOS build.

    /**
     * Called from the WebView's bridge thread. Hop to the main thread before
     * touching views; the state is deduped there too, because a busy page emits
     * several reports per second.
     */
    private fun onPageStateReported(stateToken: String?, themeToken: String?) {
        runOnUiThread {
            val state = PageBarColor.stateOf(stateToken)
            val theme = PageBarColor.themeOf(themeToken)
            if (state == pageBarState && theme == pageTheme) {
                return@runOnUiThread
            }
            pageBarState = state
            pageTheme = theme
            applyStatusBarSurface()
        }
    }

    private fun applyStatusBarSurface() {
        val dark = pageTheme?.let { it == PageTheme.DARK } ?: isSystemDark()
        val color = PageBarColor.resolve(pageBarState, dark)
        // The strip is the root's own background: the root is padded down by the
        // status bar inset, so this colour shows exactly in that strip and
        // nowhere else (the WebView covers everything below it).
        binding.root.setBackgroundColor(color)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = PageBarColor.appearanceLightStatusBars(pageTheme)
        // The gesture bar floats over the page, so its icons have to agree with
        // the page too. Only matters for 3-button navigation (API 26+).
        controller.isAppearanceLightNavigationBars = PageBarColor.appearanceLightStatusBars(pageTheme)
        Diagnostics.info(PageBarColor.describe(pageBarState, pageTheme, color))
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        notifyForegroundState(true)
        // Ask the injected layer for fresh counters: if we just came back from a
        // long background stint, this is what resolves the survival verdict.
        ShellRuntime.requestLivenessReport()
        // The page's theme can have changed while the app was away, and a system
        // dark-mode switch does not mutate its DOM — so ask for a fresh report
        // instead of waiting for the observer.
        ShellRuntime.evaluateJs(
            "window.__zcodeShellReportPageState && window.__zcodeShellReportPageState();"
        )
    }

    override fun onPause() {
        // Intentionally NOT calling webView.onPause(): it would suspend the
        // page's timers (including its relay heartbeat).
        notifyForegroundState(false)
        super.onPause()
    }

    private fun installBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            val webView = binding.webview
            if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack()
                return@addCallback
            }
            if (binding.setupPanel.visibility == View.VISIBLE) {
                finish()
                return@addCallback
            }
            // Background the task instead of finishing: keeping the Activity
            // alive is what keeps the WebView (and its socket) alive.
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        // A callback that outlives the WebView would leak it, and the page would
        // sit waiting on that input forever.
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        // Drop the evaluator: it closes over this Activity's binding.
        ShellRuntime.setJsEvaluator(null)
        ShellRuntime.setPageStateListener(null)
        super.onDestroy()
    }

    private fun notifyForegroundState(foreground: Boolean) {
        if (!::binding.isInitialized) return
        val script = "window.__zcodeShellSetAppForeground && window.__zcodeShellSetAppForeground($foreground);"
        try {
            binding.webview.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Diagnostics.log("debug", "前台状态同步失败: ${e.message}")
        }
    }

    /**
     * §5.6: the permission is requested when the user first lands on the control
     * page, not when a task happens to finish — at that moment the user is in
     * another app and the dialog is just noise.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (prefs.notificationPermissionRequested) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        prefs.notificationPermissionRequested = true
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Diagnostics.log("warn", "申请通知权限失败: ${e.message}")
        }
    }

    // ------------------------------------------------------- notification tap

    /**
     * Acts on the intent the Activity was started (or re-started) with.
     *
     * @return true when the intent has already put the screen into the state the
     *   user asked for, so [onCreate] must not run its automatic "load the stored
     *   URL" step on top of it. The two shortcut actions return opposite values
     *   on purpose: 换个链接 replaces the screen (so it must win), while 打开设置
     *   just pushes a screen on top (so the page still has to load underneath).
     */
    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action == ACTION_RELOAD) {
            reloadPage()
            return prefs.remoteUrl != null
        }
        if (intent?.action == ACTION_SCAN) {
            // Launcher long-press -> 重新扫码. With no stored link the setup panel
            // *is* the scanner entry, so going there is the same action; with one
            // stored, the page keeps loading behind the scanner.
            if (prefs.remoteUrl == null) {
                showSetup()
                return true
            }
            startScan()
            return false
        }
        if (intent?.action == ACTION_OPEN_SETTINGS) {
            openSettings()
            return false
        }
        if (intent?.action == SettingsActivity.ACTION_CHANGE_URL) {
            // The settings screen asked for a new link.
            showSetup()
            return true
        }
        if (intent?.action != ACTION_LOCATE_TASK) return false
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
        if (title.isEmpty() && sessionId.isEmpty()) return false
        pendingLocate = sessionId to title
        locateAttempts = 0
        Diagnostics.info("通知点击: 尝试定位任务")
        tryLocate()
        return false
    }

    /**
     * Runs the injected locator once the page is ready.
     *
     * The retry loop exists because a notification tap can cold-start the app,
     * and the document-start script only reports readiness after the page has
     * booted. Failure is silent by design — the user still gets the page.
     */
    private fun tryLocate() {
        val target = pendingLocate ?: return
        if (!ShellRuntime.isInjectedReady()) {
            if (locateAttempts >= MAX_LOCATE_ATTEMPTS) {
                Diagnostics.log("info", "放弃定位（注入脚本未就绪），仅打开应用")
                pendingLocate = null
                return
            }
            locateAttempts += 1
            mainHandler.postDelayed({ tryLocate() }, LOCATE_RETRY_MS)
            return
        }
        pendingLocate = null
        val script = buildString {
            append("window.__zcodeShellLocateTask && window.__zcodeShellLocateTask(")
            append(org.json.JSONObject.quote(target.first))
            append(',')
            append(org.json.JSONObject.quote(target.second))
            append(");")
        }
        try {
            binding.webview.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Diagnostics.log("warn", "执行定位脚本失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------- file upload

    /**
     * Mirrors the HarmonyOS build: a modal offering 相册 or 文件, then the matching
     * system picker. Neither path needs a storage permission — the photo picker
     * grants access to just the chosen media, and SAF grants access to just the
     * chosen documents.
     */
    private fun showUploadSourceDialog(params: WebChromeClient.FileChooserParams) {
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val pickMode = if (multiple) "多选" else "单选"
        val mimeTypes = UploadMime.normalisePlatform(params.acceptTypes?.toList())
        val request = PickVisualMediaRequest(visualMediaTypeFor(mimeTypes))
        Diagnostics.info("上传请求：网页打开选择器（$pickMode）accept=${mimeTypes.joinToString()}")

        UploadSourceDialog(
            context = this,
            onPickImages = {
                Diagnostics.info("上传方式：相册（$pickMode）")
                try {
                    if (multiple) {
                        photoPickerMultiple.launch(request)
                    } else {
                        photoPickerSingle.launch(request)
                    }
                } catch (e: Exception) {
                    Diagnostics.log("warn", "相册选择器打不开：${e.message}")
                    deliverPickedFiles(emptyList())
                }
            },
            onPickFiles = {
                Diagnostics.info("上传方式：文件（$pickMode）accept=${mimeTypes.joinToString()}")
                try {
                    if (multiple) {
                        documentPickerMultiple.launch(mimeTypes)
                    } else {
                        documentPickerSingle.launch(mimeTypes)
                    }
                } catch (e: Exception) {
                    Diagnostics.log("warn", "文件选择器打不开：${e.message}")
                    deliverPickedFiles(emptyList())
                }
            },
            onCancelled = { deliverPickedFiles(emptyList()) },
        ).show()
    }

    /**
     * The 相册 tile opens the photo picker, which only handles image/video. This
     * mirrors the HarmonyOS build's IMAGE_TYPE, except that an explicitly
     * video-only `accept` gets the video grid rather than a photo-only one.
     */
    private fun visualMediaTypeFor(
        mimeTypes: Array<String>,
    ): ActivityResultContracts.PickVisualMedia.VisualMediaType =
        if (mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("video/") }) {
            ActivityResultContracts.PickVisualMedia.VideoOnly
        } else {
            ActivityResultContracts.PickVisualMedia.ImageOnly
        }

    /**
     * Hands the pick back to the WebView. The contract is that the callback is
     * invoked exactly once, with null meaning "cancelled" — never invoking it is
     * what leaves an `<input type="file">` permanently stuck.
     */
    private fun deliverPickedFiles(uris: List<Uri>) {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        Diagnostics.info(
            if (uris.isEmpty()) "文件选择已取消"
            else "已选择 ${uris.size} 个文件，交回网页：${describePickedFiles(uris)}"
        )
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    /**
     * 一行、有界的选择结果：每个文件 名称（mime，大小），最多列 5 个。这是
     * 「安卓端 pick 到网页内发送」链条的安卓侧终点——文件是否按预期到达网页，
     * 类型/大小对不对，看这一行就够。
     */
    private fun describePickedFiles(uris: List<Uri>): String {
        val parts = uris.take(5).map { uri ->
            try {
                var name = ""
                var size = -1L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: ""
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
                if (name.length > 40) {
                    name = name.take(40) + "…"
                }
                "$name（${contentResolver.getType(uri) ?: "?"}, ${humanSize(size)}）"
            } catch (e: Exception) {
                "（读取失败：${e.message}）"
            }
        }
        val more = if (uris.size > 5) " …共 ${uris.size} 个" else ""
        return parts.joinToString() + more
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "大小未知"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * One line, bounded. A page console message can carry a whole stack trace or
     * a multi-line object dump, and both the ring buffer and the shared log file
     * are meant to stay readable.
     */
    private fun condense(message: String): String {
        val single = message.replace('\n', ' ').replace('\r', ' ').trim()
        return if (single.length > MAX_CONSOLE_CHARS) {
            single.take(MAX_CONSOLE_CHARS) + "…(共 ${single.length} 字符)"
        } else {
            single
        }
    }

    companion object {
        const val ACTION_LOCATE_TASK = "com.zcode.remote.action.LOCATE_TASK"
        const val ACTION_RELOAD = "com.zcode.remote.action.RELOAD"

        /** Launcher long-press shortcut: rescan the desktop's QR code. */
        const val ACTION_SCAN = "com.zcode.remote.action.SCAN"

        /** Launcher long-press shortcut: open the settings screen. */
        const val ACTION_OPEN_SETTINGS = "com.zcode.remote.action.OPEN_SETTINGS"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_WORKSPACE_KEY = "workspace_key"

        private const val BRIDGE_NAME = "ZCodeShell"
        private const val ALLOWED_ROOT = "z.ai"
        private const val LOCATE_RETRY_MS = 600L
        private const val MAX_LOCATE_ATTEMPTS = 40

        /** Console capture budget for one page load (see onConsoleMessage). */
        private const val MAX_CONSOLE_LINES = 200
        private const val MAX_CONSOLE_CHARS = 400

        /** Mirrors the HarmonyOS build's PhotoViewPicker maxSelectNumber. */
        private const val MAX_UPLOAD_ITEMS = 5
    }
}
