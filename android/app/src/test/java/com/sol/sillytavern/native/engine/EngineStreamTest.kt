package com.sol.sillytavern.native.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pins the behaviour of [EngineClient.stream] against the wire formats the
 * native chat route produces: OpenAI chunks, `[DONE]`, in-band `event: error`
 * frames, and HTTP failures. The CSRF retry is asserted here too because the
 * stream path had its own copy of it.
 */
class EngineStreamTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamRetriesOnceAfter403() = runBlocking {
        val tokenFetches = AtomicInteger()
        val sentTokens = CopyOnWriteArrayList<String>()
        val posts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> token(tokenFetches)
                "/api/chat" -> {
                    posts.incrementAndGet()
                    sentTokens.add(request.getHeader("x-csrf-token").orEmpty())
                    if (posts.get() == 1) {
                        json("""{"error":"stale token"}""", 403)
                    } else {
                        sse(hiChunk, doneFrame)
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(server.url("/").toString())

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Delta("Hi"), ChatEvent.Done), events)
        assertEquals("one attempt plus one retry", 2, posts.get())
        assertEquals(listOf("tok1", "tok2"), sentTokens)
        assertEquals(2, tokenFetches.get())
    }

    @Test
    fun secondStreamRejectionClosesWithCsrfError() = runBlocking {
        val posts = AtomicInteger()
        val tokenFetches = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> token(tokenFetches)
                "/api/chat" -> {
                    posts.incrementAndGet()
                    json("""{"error":"still stale"}""", 403)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(server.url("/").toString())

        try {
            withTimeout(TIMEOUT_MS) {
                client.stream("/api/chat", buildJsonObject { }).toList()
            }
            fail("expected EngineError.Csrf")
        } catch (expected: EngineError.Csrf) {
            // A third attempt cannot succeed; the stream must surface the error.
        }

        assertEquals("one attempt plus one retry", 2, posts.get())
        assertEquals(2, tokenFetches.get())
    }

    @Test
    fun doneFrameEmitsTerminalDone() = runBlocking {
        val client = serveStream(sse(hiChunk, doneFrame))

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Delta("Hi"), ChatEvent.Done), events)
    }

    @Test
    fun streamWaitsPastTheDefaultReadTimeoutForTheFirstByte() = runBlocking {
        // Prompt assembly on a cold login takes 11-12 s before the server writes
        // anything. OkHttp's default 10 s read timeout turns that into a failed
        // first turn; the streaming client must have no read deadline at all.
        val tokens = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> token(tokens)
                "/api/chat" -> sse(hiChunk, doneFrame)
                    .setHeadersDelay(15, TimeUnit.SECONDS)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(server.url("/").toString())

        val events = withTimeout(LONG_TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Delta("Hi"), ChatEvent.Done), events)
    }

    @Test
    fun errorFrameEmitsFailure() = runBlocking {
        val client = serveStream(sseResponse("event: error\ndata: {\"error\":\"upstream exploded\"}\n\n"))

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Failure("upstream exploded")), events)
    }

    @Test
    fun errorFrameWithoutSpaceIsStillAnError() = runBlocking {
        // The SSE spec allows `event:error`; some generators omit the space.
        val client = serveStream(sseResponse("event:error\ndata: {\"error\":\"no space\"}\n\n"))

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Failure("no space")), events)
    }

    @Test
    fun jsonHttpErrorUsesCodeAndServerMessage() = runBlocking {
        // Longer than the 200-byte snippet: truncating before parsing was the bug.
        // The status code must still reach the bubble; the server's `error`
        // field alone reads like a client-side failure.
        val longMessage = "x".repeat(300)
        val client = serveStream(json("""{"error":"$longMessage"}""", 500))

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Failure("HTTP 500: $longMessage")), events)
    }

    @Test
    fun nonJsonHttpErrorUsesCodeAndSnippet() = runBlocking {
        val response = MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "text/plain; charset=utf-8")
            .setBody("upstream exploded")
        val client = serveStream(response)

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(listOf<ChatEvent>(ChatEvent.Failure("HTTP 500: upstream exploded")), events)
    }

    @Test
    fun eofWithoutDoneIsAFailure() = runBlocking {
        // The connection dies after one chunk: no [DONE], no error frame.
        val client = serveStream(sse(hiChunk))

        val events = withTimeout(TIMEOUT_MS) {
            client.stream("/api/chat", buildJsonObject { }).toList()
        }

        assertEquals(
            listOf<ChatEvent>(ChatEvent.Delta("Hi"), ChatEvent.Failure("stream ended without [DONE]")),
            events,
        )
    }

    @Test
    fun slowCollectorReceivesEveryDelta() = runBlocking {
        val chunks = 200
        val body = buildString {
            repeat(chunks) { index ->
                append("data: {\"choices\":[{\"delta\":{\"content\":\"x$index\"}}]}\n\n")
            }
            append("data: [DONE]\n\n")
        }
        val client = serveStream(sseResponse(body))
        val events = mutableListOf<ChatEvent>()

        withTimeout(15_000) {
            client.stream("/api/chat", buildJsonObject { }).collect { event ->
                events.add(event)
                if (event is ChatEvent.Delta) delay(2)
            }
        }

        assertEquals(chunks, events.count { it is ChatEvent.Delta })
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun cancellingCollectorCancelsTheCall() = runBlocking {
        val canceled = CountDownLatch(1)
        val listener = object : EventListener() {
            override fun canceled(call: Call) {
                canceled.countDown()
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> token(AtomicInteger())
                "/api/chat" -> sseResponse("data: $hiChunk\n\n")
                    // Headers arrive, then the body is held long enough for the
                    // cancel below; MockWebServer must still be able to shut
                    // down, so keep the hold short.
                    .setBodyDelay(2, TimeUnit.SECONDS)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(
            server.url("/").toString(),
            OkHttpClient.Builder().eventListener(listener).build(),
        )

        withTimeout(TIMEOUT_MS) {
            val job = launch(Dispatchers.IO) {
                client.stream("/api/chat", buildJsonObject { }).collect { }
            }
            server.takeRequest() // GET /csrf-token
            server.takeRequest() // POST /api/chat
            job.cancel()
            assertTrue("the call was not cancelled", canceled.await(5, TimeUnit.SECONDS))
            job.join()
        }
    }

    private fun serveStream(response: MockResponse): EngineClient {
        val tokens = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> token(tokens)
                "/api/chat" -> response
                else -> MockResponse().setResponseCode(404)
            }
        }
        return EngineClient(server.url("/").toString())
    }

    private fun token(fetches: AtomicInteger): MockResponse =
        json("""{"token":"tok${fetches.incrementAndGet()}"}""")

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun sseResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    private fun sse(vararg frames: String): MockResponse = sseResponse(frames.joinToString(""))

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 30_000L
        const val hiChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
        const val doneFrame = "data: [DONE]\n\n"
    }
}
