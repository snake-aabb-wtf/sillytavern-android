package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.ChatMessage
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The parts of a full character card a new chat needs. [com.sol.sillytavern.native.data.model.Character]
 * comes from `/api/characters/all`, which may answer with a shallow card that has no
 * greeting; `POST /api/characters/get` is the endpoint that always carries it.
 */
data class CharacterGreeting(val firstMes: String, val alternates: List<String>)

/**
 * A chat file built in memory: the name to save it under and the exact JSONL lines
 * (header first) that will be written.
 */
data class NewChatFile(val file: String, val chat: JsonArray) {
    /** The first line. `chats.js` shifts it off before rendering, so it is not a message. */
    val header: JsonObject get() = chat.first().jsonObject

    /** The opening message, or none when the card has no greeting at all. */
    val messages: List<ChatMessage> get() = chat.drop(1).map { ChatMessage(it.jsonObject) }
}

/**
 * Builds the file a chat starts as, without touching the network or the disk.
 *
 * The shape is the one the web client writes: a header with `user_name`,
 * `character_name`, an ISO-8601 `create_date` and `chat_metadata.integrity`. The
 * integrity slug is mandatory — `chats.js` refuses to save a file whose header has
 * none, which would silently drop the first reply.
 *
 * File names follow `<character> - YYYY-MM-DD@HHhMMmSSs.jsonl` (local time). The
 * server appends `.jsonl` itself, so callers pass the name as returned here.
 */
object NewChat {

    private const val EXTENSION = ".jsonl"
    private const val USER_NAME = "User"
    private const val DEFAULT_CHARACTER_NAME = "Character"

    /** `2026-11-04@07h41m02s`, exactly as `humanizedDateTime()` in the server formats it. */
    private val FILE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'@'HH'h'mm'm'ss's'")

    /**
     * Path separators, characters Windows forbids, and control codes. Leaving any of
     * them in would either cross a directory or be stripped by the server, so the
     * name the app thinks it wrote would not be the name on disk.
     */
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun build(
        characterName: String,
        firstMes: String,
        alternates: List<String>,
        now: ZonedDateTime,
    ): NewChatFile {
        val name = characterName.trim().ifBlank { DEFAULT_CHARACTER_NAME }
        // The card may have no `first_mes` but still offer opening scenes; the first
        // non-blank alternate is what the web client would show first.
        val opening = firstMes.ifBlank { alternates.firstOrNull { it.isNotBlank() }.orEmpty() }

        val header = buildJsonObject {
            put("user_name", USER_NAME)
            put("character_name", name)
            put("create_date", now.toInstant().toString())
            put(
                "chat_metadata",
                buildJsonObject { put("integrity", UUID.randomUUID().toString()) },
            )
        }

        val lines = mutableListOf<JsonElement>(header)
        if (opening.isNotBlank()) lines += openingMessage(name, opening, now)
        return NewChatFile(file = "${fileName(name)} - ${FILE_TIME.format(now)}$EXTENSION", chat = JsonArray(lines))
    }

    /**
     * Keeps [candidate] unless it is already on disk; then appends ` 2`, ` 3`, … so a
     * second chat started in the same second never overwrites the first.
     */
    fun uniqueName(candidate: String, taken: Set<String>): String {
        if (candidate !in taken) return candidate
        val base = candidate.removeSuffix(EXTENSION)
        var index = 2
        while ("$base $index$EXTENSION" in taken) index++
        return "$base $index$EXTENSION"
    }

    private fun fileName(name: String): String {
        val cleaned = name.replace(UNSAFE, "_").trim().trim('.', ' ')
        return cleaned.ifBlank { DEFAULT_CHARACTER_NAME }
    }

    /** Mirrors `ChatVM.messageRaw` so the first line is swipeable in the web client. */
    private fun openingMessage(name: String, text: String, now: ZonedDateTime): JsonObject = buildJsonObject {
        put("name", name)
        put("is_user", false)
        put("is_system", false)
        put("send_date", now.toInstant().toString())
        put("mes", text)
        put("extra", JsonObject(emptyMap()))
        put("swipes", JsonArray(listOf(JsonPrimitive(text))))
        put("swipe_id", 0)
        put("swipe_info", JsonArray(emptyList()))
    }
}

/**
 * Creates the first chat of a character from its card.
 *
 * Existing names are read before the write: a second tap in the same second must not
 * overwrite the file the first tap just created. A failed list aborts the creation for
 * the same reason — without knowing what is on disk, a same-second write could destroy
 * a chat the user already had.
 */
class NewChatService(
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {

    /** Saves the new file and returns its name (with `.jsonl`), for navigation. */
    suspend fun create(avatar: String, characterName: String): String {
        val greeting = characters.greeting(avatar)
        val taken = chats.list(avatar).mapTo(mutableSetOf()) { it.file }
        val built = NewChat.build(characterName, greeting.firstMes, greeting.alternates, clock())
        val file = NewChat.uniqueName(built.file, taken)
        chats.save(avatar, file, built.messages, built.header)
        return file
    }
}
