package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.engine.EngineClient
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the orchestration, not just the pure builder: the name [NewChatService]
 * actually passes to `save`, and the two lines it writes.
 *
 * A test of [NewChat.uniqueName] alone would still pass if `create()` ignored the
 * taken set and saved the base name, overwriting an existing chat. This drives the
 * real repositories against a MockWebServer directory instead.
 */
class NewChatServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: NewChatService
    private val json = Json

    @Volatile
    private var savedBody: String? = null

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 4, 7, 41, 2, 0, ZoneOffset.UTC)
    private val baseFile = "Seraphina - 2026-11-04@07h41m02s.jsonl"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val engine = EngineClient(server.url("/").toString())
        service = NewChatService(
            CharacterRepository(engine),
            ChatRepository(engine),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aTakenBaseNameIsSuffixedAndTheGreetingIsWrittenAfterTheHeader() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/characters/get" -> jsonResponse(
                    """{"name":"Seraphina","first_mes":"Hello there.","data":{}}""",
                )

                "/api/characters/chats" -> jsonResponse(
                    """[{"file_name":"$baseFile","last_mes":1714561200000,"mes":""}]""",
                )

                "/api/chats/save" -> {
                    savedBody = request.body.readUtf8()
                    jsonResponse("""{"ok":true}""")
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        val file = service.create(AVATAR, CHARACTER)

        assertEquals("Seraphina - 2026-11-04@07h41m02s 2.jsonl", file)

        val body = json.parseToJsonElement(requireNotNull(savedBody)).jsonObject
        assertEquals(AVATAR, body["avatar_url"]?.jsonPrimitive?.content)
        // The repository strips `.jsonl`; the server appends it again.
        assertEquals(
            "Seraphina - 2026-11-04@07h41m02s 2",
            body["file_name"]?.jsonPrimitive?.content,
        )

        val chat = body.getValue("chat").jsonArray
        assertEquals("header + greeting", 2, chat.size)

        val header = chat[0].jsonObject
        assertEquals("User", header.getValue("user_name").jsonPrimitive.content)
        assertEquals(CHARACTER, header.getValue("character_name").jsonPrimitive.content)
        assertEquals(now.toInstant().toString(), header.getValue("create_date").jsonPrimitive.content)
        assertTrue(
            "the integrity slug is what lets the next save succeed",
            header.getValue("chat_metadata").jsonObject
                .getValue("integrity").jsonPrimitive.content.isNotBlank(),
        )

        val greeting = chat[1].jsonObject
        assertEquals("Hello there.", greeting.getValue("mes").jsonPrimitive.content)
        assertFalse(greeting.getValue("is_user").jsonPrimitive.boolean)
    }

    @Test
    fun aCardWithoutAGreetingStillSavesAHeaderOnlyFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/characters/get" -> jsonResponse("""{"name":"Seraphina","data":{}}""")
                "/api/characters/chats" -> jsonResponse("[]")
                "/api/chats/save" -> {
                    savedBody = request.body.readUtf8()
                    jsonResponse("""{"ok":true}""")
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        val file = service.create(AVATAR, CHARACTER)

        assertEquals(baseFile, file)
        val body = json.parseToJsonElement(requireNotNull(savedBody)).jsonObject
        val chat = body.getValue("chat").jsonArray
        assertEquals("a header-only chat is still a valid file", 1, chat.size)
        val integrity = chat[0].jsonObject.getValue("chat_metadata").jsonObject
            .getValue("integrity").jsonPrimitive.content
        assertTrue(integrity.isNotBlank())
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val AVATAR = "Seraphina.png"
        const val CHARACTER = "Seraphina"
    }
}
