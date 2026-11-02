package com.sol.sillytavern.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.sol.sillytavern.R
import com.sol.sillytavern.backup.DataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data backup screen: export the user data to a zip via SAF, stage a zip for import.
 *
 * Import only stages the archive; [com.sol.sillytavern.backup.DataBackup.applyPendingImport]
 * swaps it in on the next process start, because Node cannot be stopped and must not keep
 * writing into a detached `data/` directory.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    /** True while an export/import runs or the SAF picker is open. */
    private var busy = false

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            setBusy(false)
            Toast.makeText(this, R.string.settings_export_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        runExport(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            setBusy(false)
            Toast.makeText(this, R.string.settings_import_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        confirmImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        exportButton = findViewById(R.id.buttonExport)
        importButton = findViewById(R.id.buttonImport)
        progress = findViewById(R.id.settingsProgress)
        status = findViewById(R.id.settingsStatus)

        exportButton.setOnClickListener { startExport() }
        importButton.setOnClickListener { startImport() }
    }

    private fun startExport() {
        if (busy) return
        setBusy(true, R.string.settings_exporting)
        progress.visibility = View.GONE
        status.visibility = View.GONE
        try {
            createDocumentLauncher.launch(DataBackup.suggestedExportName())
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no create-document provider", e)
            setBusy(false)
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun startImport() {
        if (busy) return
        setBusy(true)
        progress.visibility = View.GONE
        status.visibility = View.GONE
        try {
            // Zip files are reported as any of these depending on the document provider.
            openDocumentLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no open-document provider", e)
            setBusy(false)
            Toast.makeText(this, R.string.file_chooser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun runExport(uri: Uri) {
        setBusy(true, R.string.settings_exporting)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DataBackup.export(applicationContext, uri)
            }
            setBusy(false)
            val message = when (result) {
                is DataBackup.Result.Success -> getString(
                    R.string.settings_export_success,
                    result.files,
                    Formatter.formatShortFileSize(this@SettingsActivity, result.bytes),
                )
                is DataBackup.Result.Failure ->
                    getString(R.string.settings_export_failed, result.reason)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Shows the archive name before anything is written to disk. */
    private fun confirmImport(uri: Uri) {
        setBusy(false)
        val name = displayName(uri) ?: getString(R.string.settings_import_archive_unknown)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import_confirm_title)
            .setMessage(getString(R.string.settings_import_confirm_message, name))
            .setPositiveButton(R.string.settings_import_confirm_positive) { _, _ ->
                runImport(uri)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                Toast.makeText(this, R.string.settings_import_cancelled, Toast.LENGTH_SHORT).show()
            }
            // Back / outside tap is a cancel too; otherwise the dialog vanishes silently.
            .setOnCancelListener {
                Toast.makeText(this, R.string.settings_import_cancelled, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun runImport(uri: Uri) {
        setBusy(true, R.string.settings_importing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DataBackup.stageImport(applicationContext, uri)
            }
            setBusy(false)
            val message = when (result) {
                is DataBackup.Result.Success -> getString(R.string.settings_import_success)
                is DataBackup.Result.Failure ->
                    getString(R.string.settings_import_failed, result.reason)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Document display name, when the provider exposes one. */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
    } catch (e: Exception) {
        Log.w(TAG, "could not read display name", e)
        uri.lastPathSegment
    }

    /**
     * Toggles the buttons, the indeterminate bar and the status line together, so a second
     * tap cannot start a concurrent export/import.
     */
    private fun setBusy(busy: Boolean, statusText: Int? = null) {
        this.busy = busy
        exportButton.isEnabled = !busy
        importButton.isEnabled = !busy
        if (busy) {
            progress.visibility = View.VISIBLE
            if (statusText != null) {
                status.setText(statusText)
                status.visibility = View.VISIBLE
            }
        } else {
            progress.visibility = View.GONE
            status.visibility = View.GONE
        }
    }

    private companion object {
        const val TAG = "SettingsActivity"
    }
}
