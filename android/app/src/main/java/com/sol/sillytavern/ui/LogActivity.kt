package com.sol.sillytavern.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.sol.sillytavern.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only view of `filesDir/logs/node.log`, the only startup diagnostics channel on a
 * device without adb.
 *
 * The tail is bounded twice: at most [MAX_TAIL_BYTES] are read from the end of the file and
 * only the last [MAX_LINES] lines are rendered. Exporting copies the whole file as a
 * stream, so the byte cap never truncates the exported log.
 */
class LogActivity : ComponentActivity() {

    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var shareButton: Button
    private lateinit var exportButton: Button

    private lateinit var logFile: File

    /** Last rendered tail, shared verbatim through ACTION_SEND. */
    private var tailText: String? = null

    /** Whether the last read found the file; keeps File.stat off the main thread. */
    private var hasLogFile = false

    private var busy = false

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            setBusy(false)
            Toast.makeText(this, R.string.log_export_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        runExport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logScroll = findViewById(R.id.logScroll)
        logText = findViewById(R.id.logText)
        statusText = findViewById(R.id.logStatus)
        refreshButton = findViewById(R.id.buttonRefresh)
        shareButton = findViewById(R.id.buttonShare)
        exportButton = findViewById(R.id.buttonExportLog)

        logFile = resolveLogFile(intent)

        // targetSdk 35 draws edge-to-edge; keep the title and last log line off the bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.logRoot)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        refreshButton.setOnClickListener { refresh() }
        shareButton.setOnClickListener { share() }
        exportButton.setOnClickListener { startExport() }

        refresh()
    }

    /**
     * Uses the path handed over by [MainActivity]; the default keeps the screen usable when
     * opened directly (e.g. from Settings or a future deep link).
     */
    private fun resolveLogFile(intent: Intent?): File {
        val provided = intent?.getStringExtra(EXTRA_LOG_PATH)
        if (provided.isNullOrBlank()) return File(filesDir, NODE_LOG_RELATIVE_PATH)
        val file = File(provided)
        return if (file.isAbsolute) file else File(filesDir, provided)
    }

    private fun refresh() {
        if (busy) return
        setBusy(true, R.string.log_loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readTail(logFile) }
            setBusy(false)
            render(result)
        }
    }

    private fun render(result: LoadResult) {
        hasLogFile = result !is LoadResult.Missing
        when (result) {
            is LoadResult.Loaded -> {
                tailText = result.text
                logText.text = result.text
                val size = Formatter.formatShortFileSize(this, result.bytes)
                statusText.text = if (result.truncated) {
                    getString(R.string.log_status_truncated, size, result.lines)
                } else {
                    getString(R.string.log_status_full, size, result.lines)
                }
            }
            LoadResult.Empty -> {
                tailText = null
                logText.text = getString(R.string.log_empty)
                statusText.text = logFile.absolutePath
            }
            LoadResult.Missing -> {
                tailText = null
                logText.text = getString(R.string.log_missing, logFile.absolutePath)
                statusText.text = getString(R.string.log_missing_hint)
            }
            is LoadResult.Failed -> {
                tailText = null
                logText.text = getString(R.string.log_read_failed, result.reason)
                statusText.text = logFile.absolutePath
            }
        }
        // Buttons follow what was actually rendered: no share without a tail, no export
        // without a file.
        shareButton.isEnabled = !busy && tailText != null
        exportButton.isEnabled = !busy && hasLogFile
        logText.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * Reads at most [MAX_TAIL_BYTES] from the end, drops the partial first line when the
     * read did not start at a line boundary, then keeps the last [MAX_LINES] lines.
     */
    private fun readTail(file: File): LoadResult {
        if (!file.isFile) return LoadResult.Missing
        if (file.length() == 0L) return LoadResult.Empty
        return try {
            val start = (file.length() - MAX_TAIL_BYTES).coerceAtLeast(0L)
            val bytes = ByteArrayOutputStream()
            var previousByte = -1
            FileInputStream(file).use { input ->
                // Read the byte before the cut first: landing exactly on a newline means
                // the first line in the slice is complete and must be kept.
                var skip = if (start > 0L) start - 1 else 0L
                while (skip > 0) {
                    val skipped = input.skip(skip)
                    if (skipped <= 0) break
                    skip -= skipped
                }
                previousByte = if (start > 0L) input.read() else -1
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var read = 0L
                while (read < MAX_TAIL_BYTES) {
                    val want = minOf(READ_BUFFER_BYTES.toLong(), MAX_TAIL_BYTES - read).toInt()
                    val n = input.read(buffer, 0, want)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    read += n
                }
            }

            var raw = bytes.toByteArray()
            if (raw.isEmpty()) return LoadResult.Empty

            if (start > 0L && previousByte != '\n'.code) {
                // The cut landed mid-line. Work on bytes: a char index would drift on
                // multi-byte UTF-8 (Chinese log lines). No newline means a single line
                // longer than the cap; keep that slice rather than nothing.
                val firstNewline = raw.indexOf(10.toByte())
                if (firstNewline >= 0) raw = raw.copyOfRange(firstNewline + 1, raw.size)
            }
            if (raw.isEmpty()) return LoadResult.Empty

            val lines = String(raw, Charsets.UTF_8)
                .split('\n')
                .map { it.trimEnd('\r') }
                // A file ending in "\n" leaves one empty element that is not a log line.
                .dropLastWhile { it.isEmpty() }
                .takeLast(MAX_LINES)

            // A cut immediately before a trailing newline leaves no complete line; report
            // the same empty state as a zero-byte log so 分享 stays disabled.
            if (lines.isEmpty()) return LoadResult.Empty

            LoadResult.Loaded(
                text = lines.joinToString("\n"),
                lines = lines.size,
                bytes = raw.size.toLong(),
                truncated = start > 0L,
            )
        } catch (e: IOException) {
            Log.w(TAG, "could not read log tail", e)
            LoadResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Log.w(TAG, "no access to log file", e)
            LoadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Shares the rendered tail. The payload is clamped to [MAX_SHARE_BYTES] because the
     * chooser re-marshals EXTRA_TEXT through EXTRA_INTENT, and a full 256 KB tail is
     * ~512 KB as UTF-16 — enough to blow the ~1 MB binder transaction limit.
     */
    private fun share() {
        val text = tailText ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_subject))
            putExtra(Intent.EXTRA_TEXT, clampForShare(text))
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no share target", e)
            Toast.makeText(this, R.string.log_share_unavailable, Toast.LENGTH_LONG).show()
        } catch (e: RuntimeException) {
            // TransactionTooLargeException and friends are unchecked.
            Log.w(TAG, "could not hand the log to the chooser", e)
            Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Keeps the newest [MAX_SHARE_BYTES] of UTF-8 text, on a whole-line boundary. */
    private fun clampForShare(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_SHARE_BYTES) return text

        var start = bytes.size - MAX_SHARE_BYTES
        // Never start inside a multi-byte sequence.
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++

        var clipped = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        val firstNewline = clipped.indexOf('\n')
        if (firstNewline >= 0) clipped = clipped.substring(firstNewline + 1)
        return clipped + "\n" + getString(R.string.log_share_truncated)
    }

    private fun startExport() {
        if (busy) return
        if (!hasLogFile) {
            Toast.makeText(this, R.string.log_missing_short, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        try {
            createDocumentLauncher.launch(suggestedExportName())
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no create-document provider", e)
            setBusy(false)
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Streams the whole log to the chosen URI; never reads it into memory. */
    private fun runExport(uri: Uri) {
        setBusy(true, R.string.log_exporting)
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                val ok = try {
                    val output = contentResolver.openOutputStream(uri, "wt")
                    if (output == null) {
                        false
                    } else {
                        output.use { out ->
                            FileInputStream(logFile).use { input -> input.copyTo(out) }
                        }
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "could not export log", e)
                    false
                }
                // Never leave a truncated document behind after a mid-write failure.
                if (!ok) discardDocument(uri)
                ok
            }
            setBusy(false)
            Toast.makeText(
                this@LogActivity,
                if (written) R.string.log_export_success else R.string.log_export_failed_short,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Removes a partial SAF document after a failed export; best effort. */
    private fun discardDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (e: Exception) {
            // Non-document providers reject this; the partial file is all we can leave.
            Log.w(TAG, "could not delete partial export document", e)
        }
    }

    private fun suggestedExportName(): String =
        "sillytavern-log-${TIMESTAMP_FORMAT.format(Date())}.txt"

    private fun setBusy(value: Boolean, messageRes: Int? = null) {
        busy = value
        refreshButton.isEnabled = !value
        shareButton.isEnabled = !value && tailText != null
        exportButton.isEnabled = !value && hasLogFile
        if (messageRes != null) {
            statusText.setText(messageRes)
            statusText.visibility = View.VISIBLE
        }
    }

    private sealed class LoadResult {
        data class Loaded(
            val text: String,
            val lines: Int,
            val bytes: Long,
            val truncated: Boolean,
        ) : LoadResult()

        object Empty : LoadResult()
        object Missing : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }

    companion object {
        const val EXTRA_LOG_PATH = "logPath"
        const val NODE_LOG_RELATIVE_PATH = "logs/node.log"

        /** Upper bound on bytes read from the end of the log. */
        const val MAX_TAIL_BYTES = 256 * 1024L

        /** Upper bound on rendered (and shared) lines. */
        const val MAX_LINES = 500

        /** Upper bound on the ACTION_SEND payload, in UTF-8 bytes. */
        const val MAX_SHARE_BYTES = 64 * 1024

        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val TAG = "LogActivity"

        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
