package com.sol.sillytavern.native.data

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the new-chat file contract (Task 12).
 *
 * A chat file is only usable by the server if its first line is a complete header —
 * `create_date` and `chat_metadata.integrity` included — and if the file name follows
 * the server's `<character> - YYYY-MM-DD@HHhMMmSSs.jsonl` convention. The integrity
 * slug is not cosmetic: without it `chats.js` refuses the next save, so the very first
 * reply would be lost.
 */
class NewChatTest {

    /** Fixed so the file name can be asserted character by character. */
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 4, 7, 41, 2, 0, ZoneOffset.UTC)

    @Test
    fun fileNameFollowsTheServerConvention() {
        val newChat = NewChat.build(CHARACTER, "Hello!", emptyList(), now)

        assertEquals("Seraphina - 2026-11-04@07h41m02s.jsonl", newChat.file)
    }

    @Test
    fun headerCarriesTheIdentityAndAnIntegritySlug() {
        val header: JsonObject = NewChat.build(CHARACTER, "Hello!", emptyList(), now).header

        assertEquals("User", header.getValue("user_name").jsonPrimitive.content)
        assertEquals(CHARACTER, header.getValue("character_name").jsonPrimitive.content)
        assertEquals(now.toInstant().toString(), header.getValue("create_date").jsonPrimitive.content)
        val integrity = header.getValue("chat_metadata").jsonObject
            .getValue("integrity").jsonPrimitive.content
        assertTrue("integrity must be present and non-empty", integrity.isNotBlank())
    }

    @Test
    fun theOpeningMessageIsTheCharactersFirstMessage() {
        val message = NewChat.build(CHARACTER, "Hello!", emptyList(), now).messages.single()

        assertEquals("Hello!", message.raw.getValue("mes").jsonPrimitive.content)
        assertFalse(message.raw.getValue("is_user").jsonPrimitive.boolean)
    }

    @Test
    fun theFirstAlternateGreetingIsUsedWhenFirstMesIsEmpty() {
        val message = NewChat.build(CHARACTER, "", listOf("hi"), now).messages.single()

        assertEquals("hi", message.raw.getValue("mes").jsonPrimitive.content)
    }

    @Test
    fun aCardWithoutAGreetingStillProducesAValidHeaderOnlyFile() {
        val newChat = NewChat.build(CHARACTER, "", emptyList(), now)

        assertTrue("no greeting means no message, not a blank one", newChat.messages.isEmpty())
        val header = newChat.header
        assertTrue(header.containsKey("user_name"))
        assertTrue(header.containsKey("character_name"))
        assertTrue(header.containsKey("create_date"))
        assertTrue(header.containsKey("chat_metadata"))
    }

    @Test
    fun separatorsInACharacterNameCannotEscapeTheChatDirectory() {
        val file = NewChat.build("a/b\\c:d", "hi", emptyList(), now).file

        assertFalse("a slash would create a subdirectory: $file", file.contains('/'))
        assertFalse("a backslash is a separator on Windows: $file", file.contains('\\'))
        assertFalse("a colon is illegal in a file name: $file", file.contains(':'))
        assertTrue(file.endsWith(".jsonl"))
    }

    @Test
    fun aNameTakenInTheSameSecondGetsASuffixInsteadOfOverwriting() {
        val base = "Seraphina - 2026-11-04@07h41m02s.jsonl"

        assertEquals(base, NewChat.uniqueName(base, emptySet()))
        assertEquals(
            "Seraphina - 2026-11-04@07h41m02s 2.jsonl",
            NewChat.uniqueName(base, setOf(base)),
        )
        assertEquals(
            "Seraphina - 2026-11-04@07h41m02s 3.jsonl",
            NewChat.uniqueName(base, setOf(base, "Seraphina - 2026-11-04@07h41m02s 2.jsonl")),
        )
    }

    private companion object {
        const val CHARACTER = "Seraphina"
    }
}
