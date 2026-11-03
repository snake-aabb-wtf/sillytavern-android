package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.Chat
import com.sol.sillytavern.native.data.model.ChatMessage
import com.sol.sillytavern.native.data.model.ChatSummary
import com.sol.sillytavern.native.data.model.string
import com.sol.sillytavern.native.engine.EngineClient
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Reads and writes chat files through the bundled server.
 *
 * Wire formats (verified in `SillyTavern/src/endpoints/chats.js` and
 * `characters.js`):
 *  - `POST /api/characters/chats` `{avatar_url, metadata}` →
 *    `[{file_name, file_id, mes, last_mes, ...}]`, or `{error:true}` when the
 *    character has no chats directory.
 *  - `POST /api/chats/get` `{avatar_url, file_name}` → the parsed JSONL lines as
 *    an array whose first element is the chat header; `{}` when the file is missing.
 *  - `POST /api/chats/save` `{avatar_url, file_name, chat: [...]}` → `{ok:true}`.
 *    The server writes `chat.map(JSON.stringify).join('\n')`, so whatever is posted
 *    is what lands on disk.
 *
 * [ChatMessage.raw] is never rebuilt: [save] re-emits each stored object and the
 * header unchanged, which is what keeps `swipes`, `extra.variables` and keys this
 * app does not know about intact.
 */
class ChatRepository(private val engine: EngineClient) {

    /**
     * Lists the character's chats. The server returns directory order, not
     * newest-first, so callers sort by [ChatSummary.updatedAt].
     */
    suspend fun list(avatar: String): List<ChatSummary> {
        val payload = engine.postJson(
            PATH_CHATS,
            buildJsonObject {
                put("avatar_url", avatar)
                // getChatInfo only attaches chat_metadata when asked; that is where a
                // title would live, and reading it costs no extra file read.
                put("metadata", true)
            },
        )
        val chats = payload as? JsonArray ?: return emptyList()
        return chats.mapNotNull { (it as? JsonObject)?.toSummary() }
    }

    /** Loads a chat's messages; the header line is available through [loadChat]. */
    suspend fun load(avatar: String, file: String): List<ChatMessage> =
        loadChat(avatar, file).messages

    /**
     * Loads both halves of a chat file.
     *
     * The header is the first array element (`script.js` does `data.shift()` before
     * rendering), and it must be sent back first on save.
     */
    suspend fun loadChat(avatar: String, file: String): Chat {
        val payload = engine.postJson(
            PATH_GET,
            buildJsonObject {
                put("avatar_url", avatar)
                put("file_name", chatName(file))
            },
        )
        // chats.js answers {} when file_name is missing or the file cannot be read.
        val lines = payload as? JsonArray ?: return EMPTY_CHAT
        val metadata = lines.firstOrNull() as? JsonObject ?: JsonObject(emptyMap())
        val messages = lines.drop(1).mapNotNull { (it as? JsonObject)?.let(::ChatMessage) }
        return Chat(metadata, messages)
    }

    /**
     * Writes the chat back verbatim: the header object first, then every stored
     * message object in order. No field is added, renamed or dropped.
     */
    suspend fun save(
        avatar: String,
        file: String,
        messages: List<ChatMessage>,
        metadata: JsonObject,
    ) {
        val chat = JsonArray(listOf(metadata) + messages.map { it.raw })
        engine.postJson(
            PATH_SAVE,
            buildJsonObject {
                put("avatar_url", avatar)
                // chats.js appends ".jsonl" itself; a doubled suffix would create a
                // second, invisible chat file.
                put("file_name", chatName(file))
                put("chat", chat)
            },
        )
    }

    private fun JsonObject.toSummary(): ChatSummary? {
        val fileName = string("file_name").takeIf { it.isNotBlank() } ?: return null
        return ChatSummary(
            file = fileName,
            title = titleOf(fileName),
            lastMessage = string("mes"),
            updatedAt = epochMillis(this["last_mes"]),
        )
    }

    /**
     * A chat file has no title field in this server version; a title is only used
     * when some other client wrote `chat_metadata.title`. Otherwise the file name
     * without its extension is the title, which is exactly what the web UI shows.
     */
    private fun JsonObject.titleOf(fileName: String): String {
        val metadataTitle = (this["chat_metadata"] as? JsonObject)?.string("title").orEmpty()
        return metadataTitle.ifBlank { fileName.removeSuffix(CHAT_EXTENSION) }
    }

    private fun epochMillis(element: JsonElement?): Long {
        val primitive = element as? JsonPrimitive ?: return 0L
        if (!primitive.isString) return primitive.longOrNull ?: 0L
        return runCatching { Instant.parse(primitive.content).toEpochMilli() }.getOrDefault(0L)
    }

    /** The server appends `.jsonl`; accept both the full name and the `file_id`. */
    private fun chatName(file: String): String = file.removeSuffix(CHAT_EXTENSION)

    private companion object {
        const val PATH_CHATS = "/api/characters/chats"
        const val PATH_GET = "/api/chats/get"
        const val PATH_SAVE = "/api/chats/save"
        const val CHAT_EXTENSION = ".jsonl"
        val EMPTY_CHAT = Chat(JsonObject(emptyMap()), emptyList())
    }
}
