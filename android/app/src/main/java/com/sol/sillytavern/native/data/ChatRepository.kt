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
import kotlinx.serialization.json.doubleOrNull
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
 *    an array whose first element is the chat header. `[]` means the file does not
 *    exist yet (a new chat); `{}` means the request could not be read (missing
 *    `file_name` or a handler exception) and is surfaced as [ChatFormatException],
 *    never as an empty chat.
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
     * rendering), and it must be sent back first on save. An empty array is a chat
     * that does not exist yet: [Chat.metadata] is then empty and the caller builds a
     * fresh header. Anything that cannot be represented without losing data throws
     * [ChatFormatException] rather than being dropped, because a save after a lossy
     * load rewrites the file on disk without it.
     */
    suspend fun loadChat(avatar: String, file: String): Chat {
        val payload = engine.postJson(
            PATH_GET,
            buildJsonObject {
                put("avatar_url", avatar)
                put("file_name", chatName(file))
            },
        )
        // chats.js answers [] only when the file does not exist; {} is returned for a
        // missing file_name or any handler exception. Treating {} as empty would make
        // a read failure indistinguishable from a new chat, and save would overwrite
        // the real chat with a synthetic header.
        val lines = payload as? JsonArray
            ?: throw ChatFormatException("$PATH_GET returned $payload instead of a chat array")
        if (lines.isEmpty()) return Chat(JsonObject(emptyMap()), emptyList())

        val header = lines.first() as? JsonObject
            ?: throw ChatFormatException("chat header is not an object: ${lines.first()}")
        if (header.containsKey("mes") || header.containsKey("is_user")) {
            // getChatData filters falsy lines only, so an unparseable header simply
            // disappears and the first message becomes element 0. Promoting it to
            // metadata would overwrite user_name/character_name/create_date and the
            // integrity slug on the next save, bypassing the server's corrupt-chat guard.
            throw ChatFormatException("chat header is missing; the first line is a message")
        }

        val messages = lines.drop(1).mapIndexed { index, element ->
            val message = element as? JsonObject
                ?: throw ChatFormatException(
                    "chat line ${index + 2} is not an object: $element",
                )
            ChatMessage(message)
        }
        return Chat(header, messages)
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
        if (!primitive.isString) {
            // getChatInfo seeds last_mes with stats.mtimeMs, a fractional value
            // (chats.js:420), so an integral parse would turn every empty chat into 0.
            return primitive.doubleOrNull?.toLong() ?: 0L
        }
        return runCatching { Instant.parse(primitive.content).toEpochMilli() }.getOrDefault(0L)
    }

    /** The server appends `.jsonl`; accept both the full name and the `file_id`. */
    private fun chatName(file: String): String = file.removeSuffix(CHAT_EXTENSION)

    private companion object {
        const val PATH_CHATS = "/api/characters/chats"
        const val PATH_GET = "/api/chats/get"
        const val PATH_SAVE = "/api/chats/save"
        const val CHAT_EXTENSION = ".jsonl"
    }
}

/**
 * A chat file whose JSONL structure cannot be represented without losing data, or
 * a `/api/chats/get` response that is not a chat array.
 *
 * Load must fail loudly here: `save` rewrites the whole file from what it was given,
 * so a silently repaired or partially loaded chat would permanently delete the parts
 * that were not understood.
 */
class ChatFormatException(message: String) : Exception(message)
