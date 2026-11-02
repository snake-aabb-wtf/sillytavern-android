package com.sol.sillytavern.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sol.sillytavern.BuildConfig
import com.sol.sillytavern.R
import com.sol.sillytavern.server.NodeService
import com.sol.sillytavern.server.ServerController
import com.sol.sillytavern.server.ServerState
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView host for the local SillyTavern server.
 *
 * The splash overlay covers the WebView until [ServerState.Ready]; every terminal startup
 * path shows either the page or the error panel, never an indefinite splash.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: View
    private lateinit var splashOverlay: View
    private lateinit var splashStatus: TextView
    private lateinit var errorPanel: View
    private lateinit var errorReason: TextView

    /** URL currently requested from the WebView; avoids reloading on state re-emission. */
    private var loadedUrl: String? = null

    /** True between loadUrl() and onPageFinished(); keeps the splash over an unpainted page. */
    private var firstPaintPending = false
    private var splashFallbackJob: Job? = null

    /** Log path from [ServerState.Failed], used by the 查看日志 button. */
    private var lastFailureLogPath: String? = null

    /**
     * Android 13+ gates the service notification behind a runtime permission. The result is
     * not needed to start the server, so a denial is simply ignored.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Recorded only after the dialog returns: a process death while it is up must not
        // suppress the prompt forever.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        root = findViewById(R.id.root)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashStatus = findViewById(R.id.splashStatus)
        errorPanel = findViewById(R.id.errorPanel)
        errorReason = findViewById(R.id.errorReason)

        applyWindowInsets()
        configureWebView()

        findViewById<Button>(R.id.buttonViewLog).setOnClickListener { openLog() }
        findViewById<Button>(R.id.buttonRetry).setOnClickListener { retry() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        lifecycleScope.launch {
            ServerController.state.collect { render(it) }
        }

        requestNotificationPermissionIfNeeded()
        startServer()
    }

    /**
     * Foreground service first, then the controller: the service is what keeps the
     * Node-bearing process alive once the user leaves the app.
     */
    private fun startServer() {
        ContextCompat.startForegroundService(this, Intent(this, NodeService::class.java))
        ServerController.start(this)
    }

    /**
     * Asks at most once per install: the flag is written by the result callback, so an
     * interrupted prompt is asked again on the next launch.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * targetSdk 35 enforces edge-to-edge on Android 15: pad the root (and therefore the
     * splash/error overlays) by the system bars, cutout and IME insets so the WebView and
     * SillyTavern's composer stay fully reachable.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    @Suppress("DEPRECATION")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Sub-frame navigations must stay in the page, not open the system browser.
                if (!request.isForMainFrame) return false
                val url = request.url
                return when (url.scheme?.lowercase()) {
                    "http", "https" -> {
                        if (isLocalHost(url.host)) {
                            false
                        } else {
                            openExternally(url)
                            true
                        }
                    }
                    // Top-level non-http(s) navigations (intent:, mailto:, tel:, ...).
                    else -> true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!firstPaintPending) return
                firstPaintPending = false
                splashFallbackJob?.cancel()
                splashOverlay.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                showError(
                    getString(
                        R.string.error_webview_format,
                        error.errorCode,
                        error.description?.toString().orEmpty(),
                    ),
                )
            }
        }
    }

    private fun render(state: ServerState) {
        when (state) {
            ServerState.Idle -> showSplash(getString(R.string.status_installing))
            ServerState.Installing -> showSplash(getString(R.string.status_installing))
            ServerState.Starting -> showSplash(getString(R.string.status_starting))
            is ServerState.Ready -> {
                errorPanel.visibility = View.GONE
                val url = "http://127.0.0.1:${state.port}/"
                if (loadedUrl != url) {
                    loadedUrl = url
                    firstPaintPending = true
                    showSplash(getString(R.string.status_loading_page))
                    webView.loadUrl(url)
                    scheduleSplashFallback()
                }
            }
            is ServerState.Failed -> {
                lastFailureLogPath = state.logPath
                showError(state.reason)
            }
        }
    }

    private fun showSplash(text: String) {
        errorPanel.visibility = View.GONE
        splashStatus.text = text
        splashOverlay.visibility = View.VISIBLE
    }

    private fun showError(reason: String) {
        firstPaintPending = false
        splashFallbackJob?.cancel()
        splashOverlay.visibility = View.GONE
        errorReason.text = reason
        errorPanel.visibility = View.VISIBLE
    }

    private fun scheduleSplashFallback() {
        splashFallbackJob?.cancel()
        splashFallbackJob = lifecycleScope.launch {
            delay(SPLASH_FALLBACK_MS)
            if (firstPaintPending) {
                firstPaintPending = false
                splashOverlay.visibility = View.GONE
            }
        }
    }

    private fun retry() {
        val state = ServerController.state.value
        if (state is ServerState.Ready) {
            // The server is up but the page failed to load: reload instead of restarting.
            loadedUrl = null
            firstPaintPending = false
            errorPanel.visibility = View.GONE
            render(state)
        } else {
            showSplash(getString(R.string.status_installing))
            loadedUrl = null
            firstPaintPending = false
            lastFailureLogPath = null
            startServer()
        }
    }

    private fun openLog() {
        val logPath = lastFailureLogPath ?: File(filesDir, NODE_LOG_RELATIVE_PATH).absolutePath
        try {
            startActivity(
                Intent()
                    .setClassName(this, LOG_ACTIVITY_CLASS)
                    .putExtra(EXTRA_LOG_PATH, logPath),
            )
        } catch (e: ActivityNotFoundException) {
            // Task 11 adds LogActivity; until then show the path the user can inspect.
            Toast.makeText(this, getString(R.string.log_path_toast, logPath), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.external_browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun isLocalHost(host: String?): Boolean =
        host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)

    // onStop() deliberately leaves NodeService running: Node backs the loaded page and must
    // survive backgrounding. Teardown happens in NodeService.onTaskRemoved.

    override fun onDestroy() {
        splashFallbackJob?.cancel()
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val PREFS_NAME = "sillytavern_prefs"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val LOG_ACTIVITY_CLASS = "com.sol.sillytavern.ui.LogActivity"
        const val EXTRA_LOG_PATH = "logPath"
        const val NODE_LOG_RELATIVE_PATH = "logs/node.log"
        const val SPLASH_FALLBACK_MS = 5_000L
    }
}
