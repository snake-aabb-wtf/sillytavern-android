package com.sol.sillytavern.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
import com.sol.sillytavern.web.WebBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** JS bridge exposed as `window.STBridge`; created before the first load. */
    private lateinit var webBridge: WebBridge

    /** Cached contents of assets/blob_bridge.js, read once. */
    private var blobBridgeScript: String? = null

    /** Outstanding `<input type=file>` request; must be answered exactly once. */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Bytes from STBridge.saveBlob waiting for the SAF picker. */
    private var pendingSaveBytes: ByteArray? = null
    private var savePickerOpen = false

    /**
     * `<input type=file>` support. Registered as a field so it exists before the activity
     * reaches STARTED, as ActivityResultRegistry requires.
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = if (result.resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        // A cancel or an empty selection must deliver null, never an empty array.
        callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
    }

    /** Blob export destination picker; the bytes are written once a URI comes back. */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        savePickerOpen = false
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri == null || bytes == null) {
            Toast.makeText(this, R.string.save_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        writeBlob(uri, bytes)
    }

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
        webBridge = WebBridge(object : WebBridge.Host {
            override fun onBlobReady(suggestedName: String, bytes: ByteArray) {
                launchSave(suggestedName, bytes)
            }

            override fun onBlobRejected(reason: WebBridge.Rejection) {
                val message = when (reason) {
                    WebBridge.Rejection.INVALID_PAYLOAD -> R.string.blob_invalid
                    WebBridge.Rejection.TOO_LARGE -> R.string.blob_too_large
                    WebBridge.Rejection.DECODE_FAILED -> R.string.blob_unreadable
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
        // Attached before loadUrl() so the very first page can export blobs. This is only
        // safe because the WebView is restricted to the loopback page: never expose the
        // interface to arbitrary origins.
        webView.addJavascriptInterface(webBridge, BRIDGE_NAME)

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
                if (view.url?.startsWith(LOOPBACK_URL_PREFIX) == true) {
                    injectBlobBridge(view)
                }
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                // A second request without answering the first leaks the WebView callback.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = chooserMimeType(fileChooserParams.acceptTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }
                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.file_chooser_title)),
                    )
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(
                        this@MainActivity,
                        R.string.file_chooser_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val scheme = Uri.parse(url).scheme?.lowercase()
            // blob:/data: never reach DownloadManager; blob_bridge.js covers those.
            if (scheme != "http" && scheme != "https") return@setDownloadListener
            enqueueDownload(url, contentDisposition, mimeType)
        }
    }

    /**
     * Character-card import uses `<input type=file>`. ACTION_GET_CONTENT plus a broad
     * fallback type keeps .png/.json cards selectable regardless of what the page asks for.
     */
    private fun chooserMimeType(acceptTypes: Array<String>?): String {
        val wantsImage = acceptTypes.orEmpty().any {
            it.contains("image", ignoreCase = true)
        }
        return if (wantsImage) "image/*" else "*/*"
    }

    private fun enqueueDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            if (!mimeType.isNullOrBlank()) {
                request.setMimeType(mimeType)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "could not enqueue download", e)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Stores the decoded blob and opens the SAF create-document picker. */
    private fun launchSave(suggestedName: String, bytes: ByteArray) {
        if (isFinishing || isDestroyed) return
        if (savePickerOpen) {
            Toast.makeText(this, R.string.save_busy, Toast.LENGTH_SHORT).show()
            return
        }
        pendingSaveBytes = bytes
        savePickerOpen = true
        try {
            createDocumentLauncher.launch(suggestedName)
        } catch (e: Exception) {
            // No SAF-capable document provider on this device.
            savePickerOpen = false
            pendingSaveBytes = null
            Log.w(TAG, "could not open create-document picker", e)
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Writes on an IO thread; the SAF stream is closed before the Toast is shown. */
    private fun writeBlob(uri: Uri, bytes: ByteArray) {
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    Log.w(TAG, "could not write export", e)
                    false
                }
            }
            Toast.makeText(
                this@MainActivity,
                if (written) R.string.save_complete else R.string.save_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Re-injects the asset on every loopback page load. The script itself is idempotent, so
     * repeated injections cannot stack duplicate listeners.
     */
    private fun injectBlobBridge(view: WebView) {
        val script = blobBridgeScript ?: try {
            assets.open(BLOB_BRIDGE_ASSET).bufferedReader().use { it.readText() }
                .also { blobBridgeScript = it }
        } catch (e: IOException) {
            Log.w(TAG, "blob bridge asset missing", e)
            return
        }
        view.evaluateJavascript(script, null)
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
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val BRIDGE_NAME = "STBridge"
        const val BLOB_BRIDGE_ASSET = "blob_bridge.js"
        const val LOOPBACK_URL_PREFIX = "http://127.0.0.1:"
        const val PREFS_NAME = "sillytavern_prefs"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val LOG_ACTIVITY_CLASS = "com.sol.sillytavern.ui.LogActivity"
        const val EXTRA_LOG_PATH = "logPath"
        const val NODE_LOG_RELATIVE_PATH = "logs/node.log"
        const val SPLASH_FALLBACK_MS = 5_000L
    }
}
