package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.engine.EngineClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins `POST /api/characters/get` parsing.
 *
 * `processCharacter` swallows a card read failure and answers
 * `{date_added:0, chat_size:0}` with status 200 (characters.js:426-439). Treating
 * that as a greeting-less card would create an empty chat and hide the real error.
 */
class CharacterRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: CharacterRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = CharacterRepository(EngineClient(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aFailedCardReadIsAnErrorNotAnEmptyGreeting() {
        dispatcherFor("""{"date_added":0,"chat_size":0}""")

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.greeting(AVATAR) }
        }

        assertTrue(
            "the diagnostic must name the endpoint: ${error.message}",
            error.message.orEmpty().contains("/api/characters/get"),
        )
    }

    @Test
    fun firstMesIsHoistedAndAlternatesComeFromTheV2DataObject() = runBlocking {
        dispatcherFor(
            """
            {
              "name": "Seraphina",
              "first_mes": "Hello.",
              "data": {
                "name": "Seraphina",
                "first_mes": "Hello.",
                "alternate_greetings": ["Hi.", "Hey."]
              }
            }
            """.trimIndent(),
        )

        val greeting = repo.greeting(AVATAR)

        assertEquals("Hello.", greeting.firstMes)
        assertEquals(listOf("Hi.", "Hey."), greeting.alternates)
    }

    private fun dispatcherFor(card: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/characters/get" -> jsonResponse(card)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val AVATAR = "Seraphina.png"
    }
}
