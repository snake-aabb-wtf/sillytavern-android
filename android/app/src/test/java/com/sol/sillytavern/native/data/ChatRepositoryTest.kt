package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.engine.EngineClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the repository contract against the wire formats of the bundled server:
 * `POST /api/characters/all`, `POST /api/characters/chats`, `POST /api/chats/get`
 * and `POST /api/chats/save` (SillyTavern/src/endpoints/characters.js and chats.js).
 *
 * The central test is the round trip: a chat line carries `swipes`, `swipe_id`,
 * `extra.variables`, `extra.reasoning` and arbitrary top-level keys that this app
 * does not understand. `save` must forward every one of them byte-for-byte, so the
 * outgoing `chat` array is compared to the served array as a whole, not field by
 * field through a model.
 */
class ChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: ChatRepository

    private val json = Json

    @Volatile
    private var savedBody: String? = null

    @Volatile
    private var lastPathRequestBody: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = ChatRepository(EngineClient(server.url("/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A chat file as `/api/chats/get` returns it: header line, then messages. */
    private val servedChat = """
        [
          {
            "user_name": "User",
            "character_name": "Seraphina",
            "create_date": "2024-05-01T10:00:00.000Z",
            "chat_metadata": {
              "integrity": "4d3c2b1a-0000-4000-8000-000000000000",
              "note_prompt": "keep the user's world info out of the prompt"
            }
          },
          {
            "name": "Seraphina",
            "is_user": false,
            "is_system": false,
            "send_date": "2024-05-01T10:00:05.000Z",
            "mes": "Hello there.",
            "swipes": ["Hello there.", "Hi."],
            "swipe_id": 1,
            "swipe_info": [
              {"send_date": "2024-05-01T10:00:05.000Z", "gen_started": "2024-05-01T10:00:04.000Z"}
            ],
            "extra": {
              "reasoning": "thought about it",
              "variables": {"hp": 12, "mood": "calm", "flags": ["a", "b"]},
              "model": "some-model"
            },
            "custom_user_key": {"nested": [1, 2, {"deep": true}]},
            "another_custom": "keep me"
          },
          {
            "name": "User",
            "is_user": true,
            "send_date": "2024-05-01T10:00:09.000Z",
            "mes": "Hey",
            "extra": {},
            "swipes": ["Hey"],
            "swipe_id": 0
          }
        ]
    """.trimIndent()

    @Test
    fun loadThenSavePreservesEveryMessageField() = runBlocking {
        val served = json.parseToJsonElement(servedChat)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/chats/get" -> jsonResponse(servedChat)
                "/api/chats/save" -> {
                    savedBody = request.body.readUtf8()
                    jsonResponse("""{"ok":true}""")
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        val chat = repo.loadChat(AVATAR, "Seraphina - 2024-05-01.jsonl")
        assertEquals("the header line is not a message", 2, chat.messages.size)
        repo.save(AVATAR, "Seraphina - 2024-05-01.jsonl", chat.messages, chat.metadata)

        val body = parse(requireNotNull(savedBody)).jsonObject
        assertEquals(AVATAR, body["avatar_url"]?.jsonPrimitive?.content)
        assertEquals("Seraphina - 2024-05-01", body["file_name"]?.jsonPrimitive?.content)
        // Deep equality over the whole array: key order does not matter, contents do.
        assertEquals("save must not drop or reshape any chat field", served, body["chat"])

        // Spelled out so a future refactor that "cleans" the message fails loudly.
        val message = body["chat"]!!.jsonArray[1].jsonObject
        assertEquals(listOf("Hello there.", "Hi."), message["swipes"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(1), message["swipe_id"])
        assertEquals(JsonPrimitive(12), message["extra"]!!.jsonObject["variables"]!!.jsonObject["hp"])
        assertEquals(JsonPrimitive("thought about it"), message["extra"]!!.jsonObject["reasoning"])
        assertTrue("unknown top-level keys must survive", message.containsKey("custom_user_key"))
        assertEquals(JsonPrimitive("keep me"), message["another_custom"])
    }

    @Test
    fun listPrefersTheMetadataTitleAndFallsBackToTheFileName() = runBlocking {
        val served = """
            [
              {
                "file_id": "with-title",
                "file_name": "with-title.jsonl",
                "file_size": "1.0 kB",
                "chat_items": 3,
                "mes": "last words",
                "last_mes": "2024-05-01T10:00:05.000Z",
                "chat_metadata": {"title": "Titled chat", "integrity": "x"}
              },
              {
                "file_id": "Seraphina - 2024-05-02",
                "file_name": "Seraphina - 2024-05-02.jsonl",
                "file_size": "12 B",
                "chat_items": 0,
                "mes": "[The chat is empty]",
                "last_mes": 1714561200000
              }
            ]
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/characters/chats" -> {
                    lastPathRequestBody = request.body.readUtf8()
                    jsonResponse(served)
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        val chats = repo.list(AVATAR)

        assertEquals(2, chats.size)
        assertEquals("Titled chat", chats[0].title)
        assertEquals("with-title.jsonl", chats[0].file)
        assertEquals("last words", chats[0].lastMessage)
        assertEquals(1_714_557_605_000L, chats[0].updatedAt)
        // No metadata title: the file name without its extension is the title.
        assertEquals("Seraphina - 2024-05-02", chats[1].title)
        assertEquals(1_714_561_200_000L, chats[1].updatedAt)

        val requestBody = parse(requireNotNull(lastPathRequestBody)).jsonObject
        assertEquals(AVATAR, requestBody["avatar_url"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), requestBody["metadata"])
    }

    @Test
    fun listTreatsTheNoChatsObjectAsAnEmptyList() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                // characters.js answers {"error":true} when the character has no chat directory.
                else -> jsonResponse("""{"error":true}""")
            }
        }

        assertEquals(emptyList<Any>(), repo.list(AVATAR))
    }

    @Test
    fun loadTreatsAnObjectResponseAsAnEmptyChat() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                // chats.js answers {} for a missing file_name or an unreadable file.
                else -> jsonResponse("{}")
            }
        }

        val chat = repo.loadChat(AVATAR, "missing.jsonl")

        assertTrue(chat.messages.isEmpty())
        assertTrue(chat.metadata.isEmpty())
    }

    @Test
    fun characterListExtractsTheFieldsTheScreensUse() = runBlocking {
        val served = """
            [
              {
                "avatar": "Seraphina.png",
                "name": "Seraphina",
                "description": "A dragon.",
                "tags": ["fantasy", "dragon"]
              },
              {
                "shallow": true,
                "avatar": "Shallow.png",
                "name": "Shallow",
                "tags": []
              },
              {
                "avatar": "NoTags.png",
                "name": "NoTags"
              }
            ]
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/characters/all" -> jsonResponse(served)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val characters = CharacterRepository(EngineClient(server.url("/").toString())).list()

        assertEquals(
            Character("Seraphina.png", "Seraphina", "A dragon.", listOf("fantasy", "dragon")),
            characters[0],
        )
        // performance.lazyLoadCharacters omits description; it must not fail the list.
        assertEquals("", characters[1].description)
        assertEquals(emptyList<String>(), characters[2].tags)
        assertNotNull(characters[2].avatar)
    }

    private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    private fun jsonResponse(text: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(text)

    private companion object {
        const val AVATAR = "Seraphina.png"
    }
}
