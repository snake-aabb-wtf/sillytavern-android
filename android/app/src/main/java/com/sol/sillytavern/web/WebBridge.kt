package com.sol.sillytavern.web

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * JavaScript bridge for the loopback SillyTavern page.
 *
 * Android's `DownloadListener` never sees `blob:` URLs, so every SillyTavern export
 * (character card, chat, preset, theme, user backup) would silently do nothing on Android.
 * `blob_bridge.js` converts the clicked blob into a data URL and calls [saveBlob]; the bytes
 * are decoded off the JS thread and handed to the host activity, which writes them through
 * the Storage Access Framework (see [Host]).
 *
 * SECURITY: `addJavascriptInterface` exposes the object to *every* frame in the WebView,
 * including the remote YouTube/Maps iframes SillyTavern embeds. Cross-origin frames cannot
 * read the parent's `window.__stToken`, so every mutating call carries the per-session token
 * that MainActivity injects into the loopback main frame only. Calls with a wrong token are
 * logged and dropped. Never attach this interface to a WebView that may display arbitrary
 * top-level origins.
 */
class WebBridge(
    private val host: Host,
    private val token: String,
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "st-blob-decode").apply { isDaemon = true }
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    /** Implemented by the activity: it owns the SAF launcher and the pending byte array. */
    interface Host {
        fun onBlobReady(suggestedName: String, bytes: ByteArray)
        fun onBlobRejected(reason: Rejection)
    }

    enum class Rejection {
        /** Payload was not a `data:` URL, was not base64, or was empty. */
        INVALID_PAYLOAD,

        /** Decoded payload exceeds [MAX_BYTES]. */
        TOO_LARGE,

        /** Base64 decoding failed. */
        DECODE_FAILED,
    }

    /**
     * Called from the WebView JavaBridge thread. Must return quickly: decoding and delivery
     * happen on [decodeExecutor] and the main thread respectively, and no file IO happens
     * here at all.
     */
    @JavascriptInterface
    fun saveBlob(name: String, dataUrl: String, callerToken: String) {
        if (!tokenMatches(callerToken)) {
            // Log only: a hostile frame must not be able to drive UI.
            Log.w(TAG, "rejected blob export without the bridge token")
            return
        }
        if (!dataUrl.startsWith(DATA_PREFIX)) {
            reject(Rejection.INVALID_PAYLOAD)
            return
        }
        // Cheap pre-check before allocating anything: base64 inflates by 4/3.
        if (dataUrl.length > MAX_DATA_URL_CHARS) {
            reject(Rejection.TOO_LARGE)
            return
        }

        val suggestedName = sanitizeFileName(name)
        try {
            decodeExecutor.execute { decodeAndDeliver(suggestedName, dataUrl) }
        } catch (e: RejectedExecutionException) {
            // Only happens after the owning activity is gone; dropping the export is correct.
            Log.w(TAG, "blob decode rejected", e)
        }
    }

    /**
     * Reported by the page when the blob itself is already over the cap, so the user gets a
     * Toast instead of a silently cancelled click. No token: this only shows a message.
     */
    @JavascriptInterface
    fun reportTooLarge(name: String, bytes: Long) {
        Log.w(TAG, "export too large: $name ($bytes bytes)")
        reject(Rejection.TOO_LARGE)
    }

    /** Stops the decode thread; called from the activity's onDestroy(). */
    fun shutdown() {
        decodeExecutor.shutdownNow()
    }

    private fun decodeAndDeliver(name: String, dataUrl: String) {
        val bytes = try {
            decodeDataUrl(dataUrl)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "blob too large to decode", e)
            reject(Rejection.TOO_LARGE)
            return
        } catch (e: Exception) {
            Log.w(TAG, "blob decode failed", e)
            reject(Rejection.DECODE_FAILED)
            return
        }

        if (bytes.size > MAX_BYTES) {
            reject(Rejection.TOO_LARGE)
            return
        }
        if (bytes.isEmpty()) {
            // "data:...;base64," would otherwise create a 0-byte file and claim success.
            reject(Rejection.INVALID_PAYLOAD)
            return
        }
        mainHandler.post { host.onBlobReady(name, bytes) }
    }

    private fun reject(reason: Rejection) {
        mainHandler.post { host.onBlobRejected(reason) }
    }

    /**
     * Only `;base64,` data URLs are accepted. Percent-decoded payloads cannot represent
     * arbitrary binary safely (UTF-8 round-trips corrupt `+`/high bytes), and every producer
     * in SillyTavern uses FileReader.readAsDataURL.
     */
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        require(comma >= DATA_PREFIX.length) { "malformed data URL" }
        val meta = dataUrl.substring(DATA_PREFIX.length, comma)
        require(meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }) {
            "data URL is not base64 encoded"
        }
        val payload = dataUrl.substring(comma + 1)
        require(payload.isNotEmpty()) { "empty payload" }
        // DEFAULT tolerates the line breaks some producers insert.
        return Base64.decode(payload, Base64.DEFAULT)
    }

    /** Constant-time comparison so a wrong token cannot be probed byte by byte. */
    private fun tokenMatches(callerToken: String): Boolean {
        if (token.isEmpty() || callerToken.isEmpty()) return false
        return MessageDigest.isEqual(
            callerToken.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        private const val TAG = "WebBridge"

        /** 64 MB decoded payload cap, per the task's DoS bound. */
        const val MAX_BYTES = 64 * 1024 * 1024

        /** Base64 of [MAX_BYTES] plus a little room for the `data:...;base64,` header. */
        private const val MAX_DATA_URL_CHARS = (MAX_BYTES / 3 + 1) * 4 + 1024

        private const val DATA_PREFIX = "data:"
        const val DEFAULT_FILE_NAME = "download.bin"
        private const val MAX_NAME_LENGTH = 120

        /**
         * Strips path separators and traversal segments so the suggested name cannot escape
         * the directory the user picks in the SAF dialog. Returns [DEFAULT_FILE_NAME] when
         * nothing usable remains.
         */
        fun sanitizeFileName(raw: String?): String {
            var name = raw.orEmpty()
                .replace("/", "")
                .replace("\\", "")
                .replace("..", "")
                .filterNot { it.isISOControl() }
                .trim()
                .trim('.')
            if (name.isEmpty()) return DEFAULT_FILE_NAME
            if (name.length > MAX_NAME_LENGTH) {
                name = name.take(MAX_NAME_LENGTH).trim().trim('.')
            }
            return name.ifEmpty { DEFAULT_FILE_NAME }
        }
    }
}
