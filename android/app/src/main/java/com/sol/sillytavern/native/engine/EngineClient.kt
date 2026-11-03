package com.sol.sillytavern.native.engine

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** One event of the `/api/native/chat` stream. */
sealed interface ChatEvent {
    /** A token of assistant text. Empty deltas are not emitted. */
    data class Delta(val text: String) : ChatEvent

    /** The server sent the terminal `data: [DONE]` frame. */
    data object Done : ChatEvent

    /** The turn ended in an in-band `event: error` frame or an HTTP failure. */
    data class Failure(val message: String) : ChatEvent
}

/** Failures the engine client raises before a stream can be read. */
sealed class EngineError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The CSRF token was rejected twice: the server rotated the token again
     * behind the refresh, or rejected the session outright. Retrying a third
     * time cannot succeed.
     */
    class Csrf(message: String = "CSRF token rejected twice") : EngineError(message)

    /** A non-2xx response outside the CSRF retry path. */
    class Http(val code: Int, val body: String) : EngineError("HTTP $code")
}

/**
 * HTTP client for the bundled Node server on `127.0.0.1:<port>`.
 *
 * The server enables csrf-sync: every write carries `x-csrf-token`, obtained
 * from `GET /csrf-token` in the same cookie session. The token is cached for
 * the life of the client, and a 403 means the token rotated — refresh once and
 * replay the request; a second 403 is surfaced as [EngineError.Csrf] instead
 * of looping.
 *
 * Cookies (the session that binds the token) live only in memory: cookies are
 * never persisted to disk, so closing the process drops the session.
 */
class EngineClient(baseUrl: String, client: OkHttpClient = OkHttpClient()) {
    private val root = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedToken: String? = null
    private val tokenLock = Mutex()

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(MemoryCookieJar())
        .addInterceptor(CsrfInterceptor())
        .build()

    /** The current CSRF token, fetching it once per session if necessary. */
    suspend fun token(): String = cachedToken ?: tokenLock.withLock {
        cachedToken ?: fetchToken().also { cachedToken = it }
    }

    /**
     * POSTs [body] as JSON and returns the parsed response.
     *
     * A 403 triggers one token refresh and one replay; a second 403 throws
     * [EngineError.Csrf]. Any other non-2xx throws [EngineError.Http].
     */
    suspend fun postJson(path: String, body: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        token()
        var response = executeJson(path, body)
        if (response.code == 403) {
            response.close()
            refreshToken()
            response = executeJson(path, body)
            if (response.code == 403) {
                response.close()
                throw EngineError.Csrf()
            }
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw EngineError.Http(resp.code, resp.body?.string().orEmpty())
            }
            json.parseToJsonElement(resp.body?.string().orEmpty())
        }
    }

    /**
     * Streams one chat turn.
     *
     * The flow emits [ChatEvent.Delta] per text chunk, then exactly one terminal
     * event: [ChatEvent.Done], [ChatEvent.Failure], or an exception. A 403 is
     * retried like [postJson]; a second 403 closes the flow with
     * [EngineError.Csrf].
     */
    fun stream(path: String, body: JsonElement): Flow<ChatEvent> = callbackFlow {
        val callRef = AtomicReference<Call?>(null)
        val worker = launch(Dispatchers.IO) {
            try {
                token()
                var call = newJsonCall(path, body)
                callRef.set(call)
                var response = call.execute()
                if (response.code == 403) {
                    response.close()
                    refreshToken()
                    call = newJsonCall(path, body)
                    callRef.set(call)
                    response = call.execute()
                    if (response.code == 403) {
                        response.close()
                        close(EngineError.Csrf())
                        return@launch
                    }
                }
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        trySend(failureFor(resp))
                        return@use
                    }
                    val contentType = resp.header("Content-Type").orEmpty()
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        // A 200 that is not a stream is an error the server chose
                        // to phrase as JSON (for example a misrouted request).
                        trySend(ChatEvent.Failure("Unexpected content-type: $contentType"))
                        return@use
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(ChatEvent.Failure("Empty response body"))
                        return@use
                    }

                    val parser = SseParser()
                    val buffer = ByteArray(8192)
                    while (isActive) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        for (event in parser.feed(buffer.copyOfRange(0, read))) {
                            val data = parser.takeEvent(event) ?: continue
                            when {
                                isErrorEvent(event) -> {
                                    trySend(ChatEvent.Failure(errorMessage(data)))
                                    close()
                                    return@use
                                }
                                data == "[DONE]" -> {
                                    trySend(ChatEvent.Done)
                                    close()
                                    return@use
                                }
                                else -> {
                                    val delta = deltaText(data)
                                    if (!delta.isNullOrEmpty()) trySend(ChatEvent.Delta(delta))
                                }
                            }
                        }
                    }
                }
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose {
            // Closing the call unblocks source.read() in the worker; the flow
            // must not leave a socket to the engine open behind a cancelled turn.
            callRef.get()?.cancel()
            worker.cancel()
        }
    }

    private suspend fun refreshToken(): String = tokenLock.withLock {
        cachedToken = null
        fetchToken().also { cachedToken = it }
    }

    private suspend fun fetchToken(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/csrf-token")).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw EngineError.Http(resp.code, resp.body?.string().orEmpty())
            val payload = json.parseToJsonElement(resp.body?.string().orEmpty())
            payload.jsonObject["token"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("csrf-token response has no token")
        }
    }

    private fun executeJson(path: String, body: JsonElement): Response =
        newJsonCall(path, body).execute()

    private fun newJsonCall(path: String, body: JsonElement): Call {
        val request = Request.Builder()
            .url(url(path))
            .post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        return http.newCall(request)
    }

    private fun url(path: String): String = "$root/${path.trimStart('/')}"

    /** Reads at most the first 200 bytes of an error body for the message. */
    private fun failureFor(response: Response): ChatEvent.Failure {
        val buffer = Buffer()
        response.body?.source()?.let { source ->
            var remaining = MAX_ERROR_SNIPPET.toLong()
            while (remaining > 0) {
                val read = source.read(buffer, remaining)
                if (read == -1L) break
                remaining -= read
            }
        }
        val snippet = buffer.readUtf8()
        val contentType = response.header("Content-Type").orEmpty()
        val message = if (contentType.contains("json", ignoreCase = true) && snippet.isNotBlank()) {
            runCatching {
                json.parseToJsonElement(snippet).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP ${response.code}"
        } else {
            "HTTP ${response.code}"
        }
        return ChatEvent.Failure(message)
    }

    private fun isErrorEvent(event: String): Boolean =
        event.split('\n').any { it.trim() == "event: error" }

    private fun errorMessage(data: String): String = runCatching {
        json.parseToJsonElement(data).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: data

    private fun deltaText(data: String): String? = runCatching {
        val choice = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?: return null
        (choice.jsonObject["delta"]?.jsonObject?.get("content") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Adds the cached CSRF token to writes; cookies are handled by [CookieJar]. */
    private inner class CsrfInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method == "GET" || request.method == "HEAD") {
                return chain.proceed(request)
            }
            val token = cachedToken ?: return chain.proceed(request)
            return chain.proceed(
                request.newBuilder().header("x-csrf-token", token).build(),
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_ERROR_SNIPPET = 200
    }
}

/**
 * In-memory cookie store. The engine's session cookie is the only thing binding
 * a CSRF token to this client, and it must not survive the process.
 */
private class MemoryCookieJar : CookieJar {
    private val byHost = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = byHost.getOrPut(url.host) { mutableListOf() }
        for (cookie in cookies) {
            store.removeAll { it.name == cookie.name && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) store.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val store = byHost[url.host] ?: return emptyList()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }
}
