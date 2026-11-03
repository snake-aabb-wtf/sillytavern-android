package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.engine.EngineClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the read-modify-write contract of `POST /api/settings/get` +
 * `POST /api/settings/save` and the secret split of `POST /api/secrets/write`.
 *
 * `settings.js:207-216` writes the request body wholesale over `settings.json`, so
 * the repository must never post a reconstructed object: it has to parse what the
 * server returned, change only the provider fields it owns, and send the rest back
 * untouched. The fixture below therefore carries opaque top-level keys and opaque
 * objects nested inside `oai_settings`; every one of them must survive deep-equal.
 */
class SettingsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: SettingsRepository

    private val json = Json

    /** The raw `settings.json` text embedded in the `/api/settings/get` envelope. */
    @Volatile
    private var servedSettings: String? = DEFAULT_SETTINGS

    @Volatile
    private var savedBody: String? = null

    @Volatile
    private var secretBody: String? = null

    @Volatile
    private var saveCalls: Int = 0

    @Volatile
    private var secretCalls: Int = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = SettingsRepository(EngineClient(server.url("/").toString()))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/settings/get" -> jsonResponse(settingsEnvelope(servedSettings))
                "/api/settings/save" -> {
                    savedBody = request.body.readUtf8()
                    saveCalls++
                    jsonResponse("""{"result":"ok"}""")
                }
                "/api/secrets/write" -> {
                    secretBody = request.body.readUtf8()
                    secretCalls++
                    jsonResponse("""{"id":"secret-id"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // The fidelity gate: patch two fields, keep every unknown key
    // ------------------------------------------------------------------

    @Test
    fun patchChangesOnlyTheGivenFieldsAndPreservesEverythingElse() {
        val original = json.parseToJsonElement(DEFAULT_SETTINGS).jsonObject
        val patches = mapOf(
            "chat_completion_source" to JsonPrimitive("custom"),
            "reverse_proxy" to JsonPrimitive("https://proxy.example/v1"),
        )

        val patched = patchOaiSettings(original, patches)

        val originalOai = original["oai_settings"]!!.jsonObject
        val expectedOai = JsonObject(
            originalOai.toMutableMap().apply {
                put("chat_completion_source", JsonPrimitive("custom"))
                put("reverse_proxy", JsonPrimitive("https://proxy.example/v1"))
            },
        )
        val expected = JsonObject(
            original.toMutableMap().apply { put("oai_settings", expectedOai) },
        )

        assertEquals("the whole document must be deep-equal apart from the two patched fields", expected, patched)
        assertEquals("unknown top-level object must survive verbatim", original["unknown_top"], patched["unknown_top"])
        assertEquals(
            "opaque third-party settings must survive verbatim",
            original["extension_settings"],
            patched["extension_settings"],
        )
        assertEquals(
            "an unknown object nested in oai_settings must survive verbatim",
            originalOai["unknown_oai"],
            patched["oai_settings"]!!.jsonObject["unknown_oai"],
        )
        assertEquals(
            "the prompt preset array must survive verbatim",
            originalOai["prompts"],
            patched["oai_settings"]!!.jsonObject["prompts"],
        )
        assertEquals(
            "an untouched sibling of the patched fields must not change",
            originalOai["openai_model"],
            patched["oai_settings"]!!.jsonObject["openai_model"],
        )
    }

    @Test
    fun saveToCustomEndpointPreservesUnknownKeysAndDoesNotTouchReverseProxy() = runBlocking {
        val original = json.parseToJsonElement(DEFAULT_SETTINGS).jsonObject
        val config = ProviderConfig(
            provider = ChatProvider.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            customModel = "my-model",
        )

        repo.save(config, apiKey = null)

        val saved = requireNotNull(savedBody) { "no /api/settings/save request was made" }
        val posted = json.parseToJsonElement(saved).jsonObject
        val postedOai = posted["oai_settings"]!!.jsonObject

        assertEquals("custom", postedOai["chat_completion_source"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1", postedOai["custom_url"]!!.jsonPrimitive.content)
        assertEquals("my-model", postedOai["custom_model"]!!.jsonPrimitive.content)
        assertEquals("the chat-completion backend must be selected", "openai", posted["main_api"]!!.jsonPrimitive.content)

        // reverse_proxy belongs to the official OpenAI provider, not to `custom`
        // (openai.js:2739-2749,2923); a custom save must leave it alone.
        assertEquals(
            original["oai_settings"]!!.jsonObject["reverse_proxy"],
            postedOai["reverse_proxy"],
        )
        assertEquals(original["unknown_top"], posted["unknown_top"])
        assertEquals(original["extension_settings"], posted["extension_settings"])
        assertEquals(
            original["oai_settings"]!!.jsonObject["unknown_oai"],
            postedOai["unknown_oai"],
        )
        assertEquals(
            original["oai_settings"]!!.jsonObject["bias_presets"],
            postedOai["bias_presets"],
        )
        assertEquals(1, saveCalls)
    }

    @Test
    fun saveSelectsTheChatCompletionBackend() = runBlocking {
        // The shipped seed says main_api = "koboldhorde" (verified in
        // SillyTavern/default/content/settings.json); the client dispatches on it,
        // so selecting a provider without switching this routes every turn to the
        // Horde and no API key is ever used.
        repo.save(ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = "gpt-4o"), apiKey = null)

        val posted = json.parseToJsonElement(requireNotNull(savedBody)).jsonObject
        assertEquals("openai", posted["main_api"]!!.jsonPrimitive.content)
    }

    @Test
    fun switchingToOpenRouterKeepsTheOtherModels() = runBlocking {
        repo.save(
            ProviderConfig(provider = ChatProvider.OPENROUTER, openRouterModel = "openai/gpt-4o-mini"),
            apiKey = null,
        )

        val postedOai = json.parseToJsonElement(requireNotNull(savedBody))
            .jsonObject["oai_settings"]!!.jsonObject
        assertEquals("openrouter", postedOai["chat_completion_source"]!!.jsonPrimitive.content)
        assertEquals("openai/gpt-4o-mini", postedOai["openrouter_model"]!!.jsonPrimitive.content)
        // Selecting another preset must not clear what the other presets had.
        assertEquals("old-custom", postedOai["custom_model"]!!.jsonPrimitive.content)
        assertEquals("gpt-4-turbo", postedOai["openai_model"]!!.jsonPrimitive.content)
        assertEquals("https://old.example/v1", postedOai["custom_url"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Never replace an unreadable settings.json with {}
    // ------------------------------------------------------------------

    @Test
    fun brokenSettingsJsonThrowsAndSendsNothing() {
        servedSettings = """{"oai_settings": {"""

        val error = assertThrows(SettingsFormatException::class.java) {
            runBlocking { repo.save(ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = "gpt-4o"), "sk-x") }
        }

        assertTrue("the error must say the file was not overwritten", error.message!!.contains("not valid JSON"))
        assertEquals("an unparseable settings.json must never be saved over", 0, saveCalls)
        assertEquals("nothing is written when the file cannot be parsed", 0, secretCalls)
    }

    @Test
    fun loadThrowsOnBrokenSettingsJson() {
        servedSettings = """{"oai_settings": {"""

        assertThrows(SettingsFormatException::class.java) { runBlocking { repo.load() } }
    }

    @Test
    fun missingSettingsStringThrows() {
        servedSettings = null

        assertThrows(SettingsFormatException::class.java) { runBlocking { repo.load() } }
    }

    // ------------------------------------------------------------------
    // Secrets never enter settings.json and are never echoed back
    // ------------------------------------------------------------------

    @Test
    fun apiKeyGoesToSecretsAndNotIntoSettings() = runBlocking {
        repo.save(
            ProviderConfig(
                provider = ChatProvider.OPENROUTER,
                openRouterModel = "anthropic/claude-3.5-sonnet",
            ),
            apiKey = "sk-or-secret-value",
        )

        val secret = json.parseToJsonElement(requireNotNull(secretBody)).jsonObject
        assertEquals("api_key_openrouter", secret["key"]!!.jsonPrimitive.content)
        assertEquals("sk-or-secret-value", secret["value"]!!.jsonPrimitive.content)
        assertNotNull(secret["label"])

        val posted = requireNotNull(savedBody)
        assertFalse("the API key must never be written to settings.json", posted.contains("sk-or-secret-value"))
        assertFalse(
            "no secret key field may be invented in settings.json",
            json.parseToJsonElement(posted).jsonObject.containsKey("api_key_openrouter"),
        )
        assertEquals(1, secretCalls)
        assertEquals(1, saveCalls)
    }

    @Test
    fun blankApiKeyDoesNotOverwriteTheStoredSecret() = runBlocking {
        repo.save(ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = "gpt-4o"), apiKey = "  ")

        assertEquals("a blank field means 'keep the stored secret'", 0, secretCalls)
        assertEquals(1, saveCalls)
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @Test
    fun loadReadsTheProviderFields() = runBlocking {
        val config = repo.load()

        assertEquals(ChatProvider.OPENAI, config.provider)
        assertEquals("https://old.example/v1", config.baseUrl)
        assertEquals("old-custom", config.customModel)
        assertEquals("OR_Website", config.openRouterModel)
        assertEquals("gpt-4-turbo", config.openAiModel)
    }

    // ------------------------------------------------------------------
    // Readable HTTP failures (HTTP code + first line)
    // ------------------------------------------------------------------

    @Test
    fun saveFailureCarriesCodeAndFirstLine() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/csrf-token" -> jsonResponse("""{"token":"tok"}""")
                "/api/settings/get" -> jsonResponse(settingsEnvelope(DEFAULT_SETTINGS))
                "/api/settings/save" -> MockResponse().setResponseCode(500)
                    .setBody("settings write failed\nstack trace line")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val error = assertThrows(SettingsHttpException::class.java) {
            runBlocking { repo.save(ProviderConfig(provider = ChatProvider.OPENAI, openAiModel = "gpt-4o"), null) }
        }

        assertEquals(500, error.code)
        assertEquals("HTTP 500: settings write failed", error.message)
    }

    private fun settingsEnvelope(raw: String?): String = buildJsonObject {
        if (raw != null) put("settings", raw)
        put("openai_settings", JsonArray(emptyList()))
        put("openai_setting_names", JsonArray(emptyList()))
    }.toString()

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        val DEFAULT_SETTINGS = """
            {
              "firstRun": false,
              "username": "tester",
              "api_server": "http://127.0.0.1:8000",
              "main_api": "koboldhorde",
              "unknown_top": {
                "nested": [1, 2, {"deep": true}],
                "empty": {},
                "null_value": null
              },
              "extension_settings": {
                "third_party": {"enabled": true, "options": {"a": null, "b": [3, 4]}}
              },
              "tags": ["a", "b"],
              "tag_map": {"a": {"color": "#fff"}},
              "oai_settings": {
                "chat_completion_source": "openai",
                "reverse_proxy": "https://proxy.example/original",
                "custom_url": "https://old.example/v1",
                "custom_model": "old-custom",
                "openai_model": "gpt-4-turbo",
                "openrouter_model": "OR_Website",
                "unknown_oai": {"bias": [0.1, -0.2], "nested": {"x": "y"}},
                "bias_presets": {"Default (none)": []},
                "prompts": [{"name": "Main Prompt", "system_prompt": true}],
                "openai_max_tokens": 300
              }
            }
        """.trimIndent()
    }
}
