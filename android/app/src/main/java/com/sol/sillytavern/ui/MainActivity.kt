package com.sol.sillytavern.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sol.sillytavern.R
import com.sol.sillytavern.backup.DataBackup
import com.sol.sillytavern.native.ui.SolApp
import com.sol.sillytavern.native.ui.theme.SolTheme
import com.sol.sillytavern.server.NodeService
import com.sol.sillytavern.server.ServerController
import com.sol.sillytavern.server.ServerState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the native Compose client for the local SillyTavern server.
 *
 * The splash overlay covers the Compose host until [ServerState.Ready]; every terminal
 * startup path shows either the app or the error panel, never an indefinite splash.
 */
class MainActivity : ComponentActivity() {

    private lateinit var composeHost: ComposeView
    private lateinit var root: View
    private lateinit var splashOverlay: View
    private lateinit var splashStatus: TextView
    private lateinit var errorPanel: View
    private lateinit var errorReason: TextView

    /** Port the current composition was built for; null until the first Ready. */
    private var composedPort: Int? = null

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
        setContentView(R.layout.activity_main)

        composeHost = findViewById(R.id.composeHost)
        root = findViewById(R.id.root)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashStatus = findViewById(R.id.splashStatus)
        errorPanel = findViewById(R.id.errorPanel)
        errorReason = findViewById(R.id.errorReason)

        applyWindowInsets()

        findViewById<Button>(R.id.buttonViewLog).setOnClickListener { openLog() }
        findViewById<Button>(R.id.buttonRetry).setOnClickListener { retry() }
        findViewById<ImageButton>(R.id.buttonSettings).setOnClickListener { openSettings() }

        lifecycleScope.launch {
            ServerController.state.collect { render(it) }
        }

        requestNotificationPermissionIfNeeded()
        applyPendingImportThenStart()
    }

    /**
     * Restores a staged import before Node starts, then boots the server.
     *
     * A live Node keeps file handles inside `data/`, so a swap is only safe when no Node
     * thread is alive (cold start). On activity recreation while the server is up, or when
     * a failed attempt left a live thread behind, the marker is left for the next cold
     * start instead.
     */
    private fun applyPendingImportThenStart() {
        val state = ServerController.state.value
        // Installing/Starting/Ready mean Node is (or is about to be) live; a rename under
        // it would detach the directory the server writes into. Idle and Failed (apply
        // failure or a failed install) still allow the swap.
        if (state is ServerState.Installing ||
            state is ServerState.Starting ||
            state is ServerState.Ready
        ) {
            startServer()
            return
        }
        // Failed is not proof that Node is gone: a readiness timeout fails the state while
        // the thread is still alive. Renaming data/ under it would leave the server writing
        // into the detached directory, so keep the marker for the next cold start.
        if (!ServerController.canSafelyRewriteData()) {
            Toast.makeText(this, R.string.import_needs_restart, Toast.LENGTH_LONG).show()
            startServer()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DataBackup.applyPendingImport(applicationContext)
            }
            if (result is DataBackup.Result.Failure) {
                showError(getString(R.string.import_apply_failed_format, result.reason))
                return@launch
            }
            startServer()
        }
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
     * splash/error overlays) by the system bars and cutout so the content stays reachable.
     *
     * The IME inset is deliberately not applied here. The Compose chat screen pads its
     * composer by the IME inset minus the nav bar (which this root already covers);
     * padding the root by the IME as well would lift the composer a keyboard-height
     * above the keyboard. No other screen hosts a text field.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun render(state: ServerState) {
        when (state) {
            ServerState.Idle -> showSplash(getString(R.string.status_installing))
            ServerState.Installing -> showSplash(getString(R.string.status_installing))
            ServerState.Starting -> showSplash(getString(R.string.status_starting))
            is ServerState.Ready -> {
                errorPanel.visibility = View.GONE
                // Only the first Ready (or a restart on another port) builds the
                // composition; state re-emission must not recompose the app.
                if (composedPort != state.port) {
                    composedPort = state.port
                    val port = state.port
                    composeHost.setContent {
                        SolTheme {
                            SolApp(
                                port = port,
                                // The splash stays up until the new tree has drawn a
                                // frame; hiding it on Ready alone reveals an unpainted
                                // ComposeView (white flash, then the character grid).
                                onFirstFrame = { splashOverlay.visibility = View.GONE },
                            )
                        }
                    }
                } else {
                    // Already composed (Ready after a failed retry): there is no new
                    // first frame to wait for.
                    splashOverlay.visibility = View.GONE
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
        splashOverlay.visibility = View.GONE
        errorReason.text = reason
        errorPanel.visibility = View.VISIBLE
    }

    private fun retry() {
        val state = ServerController.state.value
        if (state is ServerState.Ready) {
            // The UI is already composed: surface it again instead of restarting Node.
            errorPanel.visibility = View.GONE
            splashOverlay.visibility = View.GONE
        } else {
            showSplash(getString(R.string.status_installing))
            lastFailureLogPath = null
            // Retry may follow a failed/incomplete import apply: re-run it so the pending
            // archive is not silently skipped until the next cold start.
            applyPendingImportThenStart()
        }
    }

    /**
     * Opens the log viewer. A missing log is not handled here: LogActivity shows the path
     * and a clear "not started yet" message, so there is no dead Toast branch to keep.
     */
    private fun openLog() {
        val logPath = lastFailureLogPath ?: File(filesDir, NODE_LOG_RELATIVE_PATH).absolutePath
        startActivity(
            Intent(this, LogActivity::class.java)
                .putExtra(LogActivity.EXTRA_LOG_PATH, logPath),
        )
    }

    /** Opens the backup screen. The gear is a draggable overlay (FloatingGearButton). */
    private fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "settings activity missing", e)
        }
    }

    // onStop() deliberately leaves NodeService running: Node backs the loaded screen and
    // must survive backgrounding. Teardown happens in NodeService.onTaskRemoved.

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS_NAME = "sillytavern_prefs"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val NODE_LOG_RELATIVE_PATH = "logs/node.log"
    }
}
