package com.sol.sillytavern.web

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.net.URLDecoder
import java.util.concurrent.Executor
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
 * SECURITY: this interface is attached only to the WebView that loads
 * `http://127.0.0.1:<port>/`. Every script running in that WebView - including third-party
 * frames or user-supplied content that reaches the DOM - can call it. Never add it to a
 * WebView that may display arbitrary origins.
 */
class WebBridge(
    private val host: Host,
    private val decodeExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
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
        /** Payload was not a `data:` URL, or was malformed. */
        INVALID_PAYLOAD,

        /** Decoded payload exceeds [MAX_BYTES]. */
        TOO_LARGE,

        /** Base64/percent decoding failed. */
        DECODE_FAILED,
    }

    /**
     * Called from the WebView JavaBridge thread. Must return quickly: decoding and delivery
     * happen on [decodeExecutor] and the main thread respectively, and no file IO happens
     * here at all.
     */
    @JavascriptInterface
    fun saveBlob(name: String, dataUrl: String) {
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
        mainHandler.post { host.onBlobReady(name, bytes) }
    }

    private fun reject(reason: Rejection) {
        mainHandler.post { host.onBlobRejected(reason) }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        require(comma >= DATA_PREFIX.length) { "malformed data URL" }
        val meta = dataUrl.substring(DATA_PREFIX.length, comma)
        val payload = dataUrl.substring(comma + 1)
        val isBase64 = meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }
        return if (isBase64) {
            // DEFAULT tolerates the line breaks some producers insert.
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, Charsets.UTF_8.name()).toByteArray(Charsets.UTF_8)
        }
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
