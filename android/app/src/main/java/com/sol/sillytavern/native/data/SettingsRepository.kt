package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.string
import com.sol.sillytavern.native.engine.EngineClient
import com.sol.sillytavern.native.engine.EngineError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The three providers the native client can configure. */
enum class ChatProvider(val source: String, val secretKey: String) {
    /** Any OpenAI-compatible endpoint: `custom_url` + `custom_model`. */
    CUSTOM("custom", "api_key_custom"),

    /** OpenRouter: `openrouter_model`. */
    OPENROUTER("openrouter", "api_key_openrouter"),

    /** api.openai.com: `openai_model`. */
    OPENAI("openai", "api_key_openai");

    companion object {
        /** Unknown sources fall back to the official provider; saving then replaces it. */
        fun fromSource(source: String): ChatProvider =
            entries.firstOrNull { it.source == source } ?: OPENAI
    }
}

/**
 * The provider settings the app owns.
 *
 * Only the active preset's model field is written on save; the other presets'
 * values are read so switching back does not lose them.
 */
data class ProviderConfig(
    val provider: ChatProvider = ChatProvider.OPENAI,
    /** Base URL of the OpenAI-compatible endpoint (`custom_url` in settings.json). */
    val baseUrl: String = "",
    val customModel: String = "",
    val openRouterModel: String = "",
    val openAiModel: String = "",
)

/**
 * `settings.json` could not be parsed, or its shape is not what the endpoints
 * documented. Load must fail loudly: `POST /api/settings/save` replaces the file
 * wholesale (`SillyTavern/src/endpoints/settings.js:207-216`), so guessing a shape
 * and saving would delete the user's configuration.
 */
class SettingsFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A settings/secrets call answered non-2xx; [message] is `HTTP <code>: <first line>`. */
class SettingsHttpException(val code: Int, val detail: String) :
    Exception(if (detail.isBlank()) "HTTP $code" else "HTTP $code: $detail")

/**
 * `/api/settings/save` answered 2xx but did not report success.
 *
 * The handler catches write errors and calls `response.send(err)` without a
 * status (`settings.js:206-215`); an `Error` serialises to `{}`, so a failed
 * write looks like a 200 with an empty object. Only `{ result: "ok" }`
 * (settings.js:211) may be reported as saved.
 */
class SettingsSaveException(message: String) : Exception(message)

/** The seam the view model uses; the repository is the HTTP implementation. */
interface SettingsStore {
    suspend fun load(): ProviderConfig

    suspend fun save(config: ProviderConfig, apiKey: String?)

    /**
     * Whether [provider] has at least one stored secret.
     *
     * The form cannot read a key back, so this is the only way to tell "the user
     * already stored a key for this provider" from "switching to this provider
     * left it without any key at all".
     */
    suspend fun hasApiKey(provider: ChatProvider): Boolean
}

/**
 * Reads and writes the provider block of `settings.json` through the bundled server.
 *
 * Wire contract, verified in the server source:
 *  - `POST /api/settings/get` returns `{ settings: "<raw settings.json>", ...presets }`
 *    (`settings.js:219-268`). `settings` is a **string**, not an object.
 *  - `POST /api/settings/save` writes the request body wholesale to the file
 *    (`settings.js:207-216`). The repository therefore parses the raw string,
 *    patches only the fields of the selected provider (all of them live inside
 *    `oai_settings`) plus the top-level `main_api` that selects the chat backend,
 *    and posts the whole document back. Unknown top-level keys and unknown nested
 *    objects are preserved because the parsed map is copied, not rebuilt.
 *  - API keys never enter `settings.json`: they go to `POST /api/secrets/write`
 *    as `{ key, value, label }` (`secrets.js:511-526`). `/api/secrets/read` returns
 *    only ids/labels, so the stored value cannot be read back by this screen.
 *
 * Field placement is taken from the client that actually builds requests:
 * `oai_settings.chat_completion_source`, `oai_settings.custom_url` and
 * `custom_model` for the custom source (`public/scripts/openai.js:2923`,
 * `getChatCompletionModel`), `oai_settings.openrouter_model` for OpenRouter, and
 * `oai_settings.openai_model` for OpenAI. `reverse_proxy` is the proxy for the
 * *official* providers (`openai.js:2739-2749`) and is deliberately untouched.
 */
class SettingsRepository(private val engine: EngineClient) : SettingsStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** Loads the provider block; throws [SettingsFormatException] on an unreadable file. */
    override suspend fun load(): ProviderConfig = configOf(readRoot())

    /**
     * Patches the selected provider and saves the whole document.
     *
     * A non-blank [apiKey] is written to the secrets store first, so a failed
     * settings write cannot leave a new key active against a provider that was
     * never selected. A blank key leaves the stored secret untouched.
     */
    override suspend fun save(config: ProviderConfig, apiKey: String?) {
        // Read and parse before any write: a broken file must abort the whole
        // operation, not be silently replaced by a partial object.
        val root = readRoot()
        val patched = withMainApi(patchOaiSettings(root, fieldsFor(config)))

        val key = apiKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            post(
                PATH_SECRETS_WRITE,
                buildJsonObject {
                    put("key", config.provider.secretKey)
                    put("value", key)
                    put("label", SECRET_LABEL)
                },
            )
        }
        val response = post(PATH_SAVE, patched)
        val result = (response as? JsonObject)
            ?.get("result")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
        if (result != "ok") {
            throw SettingsSaveException(
                "保存未确认：服务器返回 HTTP 200 但没有 { result: \"ok\" }（$response）",
            )
        }
    }

    /**
     * Reads the secret index and reports whether [provider] has an entry.
     *
     * `POST /api/secrets/read` returns `{ <secretKey>: [ { id, value: masked, ... } ] | null }`
     * (`secrets.js:529-536`, `getSecretState`); values are masked, which is all
     * this call needs.
     */
    override suspend fun hasApiKey(provider: ChatProvider): Boolean {
        val state = post(PATH_SECRETS_READ, buildJsonObject { })
        val values = (state as? JsonObject)?.get(provider.secretKey) as? JsonArray
        return values != null && values.isNotEmpty()
    }

    /**
     * Selects the chat-completion backend at the top level.
     *
     * `main_api` is what `script.js` dispatches on (`main_api = settings.main_api`,
     * `Generate()` branches on it); `chat_completion_source` only chooses the
     * provider *inside* that backend. The shipped seed still says
     * `main_api: "koboldhorde"` (`SillyTavern/default/content/settings.json`), so
     * writing only `oai_settings` would leave every turn routed to the Horde and no
     * key would ever be used. All three presets this form offers are chat
     * completions, hence the unconditional value.
     */
    private fun withMainApi(root: JsonObject): JsonObject {
        val merged = root.toMutableMap().apply { put("main_api", JsonPrimitive("openai")) }
        return JsonObject(merged)
    }

    private suspend fun readRoot(): JsonObject {
        val payload = post(PATH_GET, buildJsonObject { })
        val raw = (payload as? JsonObject)
            ?.get("settings")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?: throw SettingsFormatException(
                "/api/settings/get returned no settings string; refusing to overwrite the file blind",
            )
        return parseSettings(raw)
    }

    private fun parseSettings(raw: String): JsonObject {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (error: SerializationException) {
            throw SettingsFormatException(
                "settings.json is not valid JSON; refusing to overwrite it",
                error,
            )
        }
        return element as? JsonObject
            ?: throw SettingsFormatException("settings.json is not a JSON object; refusing to overwrite it")
    }

    private fun configOf(root: JsonObject): ProviderConfig {
        val rawOai = root["oai_settings"]
        if (rawOai != null && rawOai !is JsonObject) {
            throw SettingsFormatException(
                "settings.json oai_settings is not an object; refusing to interpret it",
            )
        }
        val oai = rawOai as? JsonObject ?: JsonObject(emptyMap())
        return ProviderConfig(
            provider = ChatProvider.fromSource(oai.string("chat_completion_source")),
            baseUrl = oai.string("custom_url"),
            customModel = oai.string("custom_model"),
            // "OR_Website" is the client's sentinel for "no OpenRouter model"
            // (openai.js:1734-1735 returns null for it). Showing it in the form
            // and writing it back produces model:null and a provider rejection.
            openRouterModel = oai.string("openrouter_model")
                .takeUnless { it == OPENROUTER_WEBSITE_MODEL }
                .orEmpty(),
            openAiModel = oai.string("openai_model"),
        )
    }

    private fun fieldsFor(config: ProviderConfig): Map<String, JsonElement> = when (config.provider) {
        ChatProvider.CUSTOM -> mapOf(
            "chat_completion_source" to JsonPrimitive(config.provider.source),
            // `reverse_proxy` is not used for the custom source; the client puts
            // this value into `custom_url` when it builds the request body.
            "custom_url" to JsonPrimitive(config.baseUrl.trim()),
            "custom_model" to JsonPrimitive(config.customModel.trim()),
        )

        ChatProvider.OPENROUTER -> mapOf(
            "chat_completion_source" to JsonPrimitive(config.provider.source),
            "openrouter_model" to JsonPrimitive(config.openRouterModel.trim()),
        )

        ChatProvider.OPENAI -> mapOf(
            "chat_completion_source" to JsonPrimitive(config.provider.source),
            "openai_model" to JsonPrimitive(config.openAiModel.trim()),
        )
    }

    private suspend fun post(path: String, body: JsonElement): JsonElement = try {
        engine.postJson(path, body)
    } catch (error: EngineError.Http) {
        throw SettingsHttpException(error.code, firstLine(error.body))
    }

    /** The first non-blank line of an error body, bounded so a stack cannot fill the screen. */
    private fun firstLine(body: String): String = body
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(MAX_ERROR_LINE)
        .orEmpty()

    private companion object {
        const val PATH_GET = "/api/settings/get"
        const val PATH_SAVE = "/api/settings/save"
        const val PATH_SECRETS_WRITE = "/api/secrets/write"
        const val PATH_SECRETS_READ = "/api/secrets/read"
        const val SECRET_LABEL = "SillyTavern Android"
        const val MAX_ERROR_LINE = 200
        const val OPENROUTER_WEBSITE_MODEL = "OR_Website"
    }
}

/**
 * Returns [root] with [patches] merged into its `oai_settings` object.
 *
 * Every other key — top-level or nested — is copied through unchanged. A missing
 * `oai_settings` is created; one that exists but is not an object is refused,
 * because replacing it would discard whatever the user had there.
 */
internal fun patchOaiSettings(root: JsonObject, patches: Map<String, JsonElement>): JsonObject {
    val existing = root["oai_settings"]
    val oai = when (existing) {
        null -> JsonObject(emptyMap())
        is JsonObject -> existing
        else -> throw SettingsFormatException("settings.json oai_settings is not an object; refusing to overwrite it")
    }
    val mergedOai = oai.toMutableMap().apply { putAll(patches) }
    val mergedRoot = root.toMutableMap().apply { put("oai_settings", JsonObject(mergedOai)) }
    return JsonObject(mergedRoot)
}
