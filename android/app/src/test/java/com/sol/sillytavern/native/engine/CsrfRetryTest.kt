package com.sol.sillytavern.native.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * csrf-sync rejects a write whose `x-csrf-token` no longer matches the session.
 * The engine client must refresh `/csrf-token` and replay the write exactly
 * once; a second rejection is final and must not loop.
 */
class CsrfRetryTest {
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
    fun refreshesTokenAndRetriesWriteOnce() = runBlocking {
        val tokenFetches = AtomicInteger()
        val sentTokens = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> json("""{"token":"tok${tokenFetches.incrementAndGet()}"}""")
                "/api/write" -> {
                    sentTokens.add(request.getHeader("x-csrf-token").orEmpty())
                    if (sentTokens.size == 1) {
                        // The first token was issued before the session rotated.
                        json("""{"error":"invalid csrf token"}""", 403)
                    } else {
                        json("""{"ok":true}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(server.url("/").toString())

        val result = client.postJson("/api/write", buildJsonObject { })

        assertTrue(result.jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertEquals(listOf("tok1", "tok2"), sentTokens)
        assertEquals("one initial fetch, one refresh", 2, tokenFetches.get())
    }

    @Test
    fun secondRejectionThrowsCsrfError() = runBlocking {
        val tokenFetches = AtomicInteger()
        val posts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> json("""{"token":"tok${tokenFetches.incrementAndGet()}"}""")
                "/api/write" -> {
                    posts.incrementAndGet()
                    json("""{"error":"invalid csrf token"}""", 403)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = EngineClient(server.url("/").toString())

        try {
            client.postJson("/api/write", buildJsonObject { })
            fail("expected EngineError.Csrf")
        } catch (expected: EngineError.Csrf) {
            // The retry was spent; the second 403 is terminal.
        }

        assertEquals("one attempt plus one retry", 2, posts.get())
        assertEquals("one initial fetch, one refresh", 2, tokenFetches.get())
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
}
