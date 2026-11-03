package com.sol.sillytavern.native.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One character card, as listed by `POST /api/characters/all`.
 *
 * `avatar` is the card's file name (`Seraphina.png`), which is the identifier every
 * other character endpoint expects as `avatar_url`.
 *
 * When the server runs with `performance.lazyLoadCharacters`, `/all` answers with a
 * shallow object that has no `description`; it is then empty rather than missing.
 */
data class Character(
    val avatar: String,
    val name: String,
    val description: String,
    val tags: List<String>,
)

/**
 * One chat file, as listed by `POST /api/characters/chats`.
 *
 * [file] is the server's `file_name` (`Seraphina - 2024-05-01.jsonl`). The repository
 * accepts either it or the bare `file_id` in [ChatRepository.load] and
 * [ChatRepository.save]; the `.jsonl` suffix is stripped before the request because
 * the server appends it again.
 *
 * [updatedAt] is epoch milliseconds. `last_mes` is an ISO timestamp for a chat with
 * messages and the file's fractional mtime in milliseconds for an empty one; an
 * unparseable value becomes 0 so a broken file sorts last instead of failing the list.
 */
data class ChatSummary(
    val file: String,
    val title: String,
    val lastMessage: String,
    val updatedAt: Long,
)

/**
 * One line of a chat file, kept as the exact object the server sent.
 *
 * A message carries data this app does not model — `swipes`, `swipe_id`, `swipe_info`,
 * `extra.reasoning`, `extra.variables`, and any key a user's extension added. Keeping
 * the whole [raw] object and re-serialising it on save is what makes a load/save
 * round trip byte-faithful: a field whitelist here would silently delete history.
 */
data class ChatMessage(val raw: JsonObject)

/**
 * A chat file split into its header line and its messages.
 *
 * The first line of every SillyTavern chat file is metadata, not a message
 * (`script.js` shifts it off before rendering). It must be written back unchanged as
 * the first element of `chat` on save, which is why [metadata] is carried alongside
 * the messages instead of being folded into them.
 */
data class Chat(
    val metadata: JsonObject,
    val messages: List<ChatMessage>,
)

/** A string field; absent, null and non-string values read as the empty string. */
internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** A list of string fields; absent, null and non-array values read as empty. */
internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
