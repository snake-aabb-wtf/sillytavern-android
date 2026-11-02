package com.sol.sillytavern.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.sol.sillytavern.BuildConfig
import com.sol.sillytavern.R
import com.sol.sillytavern.server.ServerController
import com.sol.sillytavern.server.ServerState
import kotlinx.coroutines.launch

/**
 * Full-screen WebView host for the local SillyTavern server.
 *
 * The splash overlay covers the WebView until [ServerState.Ready]; every terminal startup
 * path shows either the page or the error panel, never an indefinite splash.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var splashOverlay: View
    private lateinit var splashStatus: TextView
    private lateinit var errorPanel: View
    private lateinit var errorReason: TextView

    /** URL currently requested from the WebView; avoids reloading on state re-emission. */
    private var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashStatus = findViewById(R.id.splashStatus)
        errorPanel = findViewById(R.id.errorPanel)
        errorReason = findViewById(R.id.errorReason)

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

        ServerController.start(this)
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
                    // Unknown schemes (intent:, mailto:, tel:, ...) stay out of the WebView.
                    else -> true
                }
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
                splashOverlay.visibility = View.GONE
                errorPanel.visibility = View.GONE
                val url = "http://127.0.0.1:${state.port}/"
                if (loadedUrl != url) {
                    loadedUrl = url
                    webView.loadUrl(url)
                }
            }
            is ServerState.Failed -> showError(state.reason)
        }
    }

    private fun showSplash(text: String) {
        errorPanel.visibility = View.GONE
        splashStatus.text = text
        splashOverlay.visibility = View.VISIBLE
    }

    private fun showError(reason: String) {
        splashOverlay.visibility = View.GONE
        errorReason.text = reason
        errorPanel.visibility = View.VISIBLE
    }

    private fun retry() {
        val state = ServerController.state.value
        if (state is ServerState.Ready) {
            // The server is up but the page failed to load: reload instead of restarting.
            loadedUrl = null
            errorPanel.visibility = View.GONE
            render(state)
        } else {
            showSplash(getString(R.string.status_installing))
            loadedUrl = null
            ServerController.start(this)
        }
    }

    private fun openLog() {
        try {
            startActivity(Intent().setClassName(this, LOG_ACTIVITY_CLASS))
        } catch (e: ActivityNotFoundException) {
            // Task 11 adds LogActivity; until then explain instead of crashing.
            Toast.makeText(this, R.string.log_unavailable, Toast.LENGTH_LONG).show()
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
        host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)

    override fun onDestroy() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val LOG_ACTIVITY_CLASS = "com.sol.sillytavern.ui.LogActivity"
    }
}
