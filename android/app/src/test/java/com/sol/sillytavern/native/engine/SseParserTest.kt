package com.sol.sillytavern.native.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine sends every byte of an SSE stream through [SseParser.feed].
 *
 * These cases pin the three ways the wire can disagree with the obvious
 * implementation: an event arriving whole, an event split mid-token across two
 * socket reads, and a UTF-8 sequence split inside a code point. The last one is
 * why the parser cannot decode each chunk on its own: decoding the first half
 * of an emoji yields U+FFFD and the second half yields another one.
 */
class SseParserTest {
    private val payload = """{"choices":[{"delta":{"content":"Hi"}}]}"""

    @Test
    fun completeEventInOneChunk() {
        val parser = SseParser()

        val events = parser.feed("data: $payload\n\n".toByteArray(Charsets.UTF_8))

        assertEquals(1, events.size)
        assertEquals("data: $payload", events.single())
        assertEquals(payload, parser.takeEvent(events.single()))
    }

    @Test
    fun eventSplitAcrossTwoChunks() {
        val parser = SseParser()

        // The split lands inside the JSON token, not on a frame boundary.
        assertTrue(parser.feed("""data: {"cho""".toByteArray(Charsets.UTF_8)).isEmpty())
        val events = parser.feed(
            "ices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n".toByteArray(Charsets.UTF_8),
        )

        assertEquals(1, events.size)
        assertEquals(payload, parser.takeEvent(events.single()))
    }

    @Test
    fun emojiSplitInsideCodePoint() {
        val parser = SseParser()
        val json = """{"choices":[{"delta":{"content":"😀"}}]}"""
        val bytes = "data: $json\n\n".toByteArray(Charsets.UTF_8)

        val emojiAt = bytes.indexOfFirst { it == 0xF0.toByte() }
        assertTrue("expected the 😀 code point in the payload", emojiAt >= 0)
        assertEquals(0x9F.toByte(), bytes[emojiAt + 1])
        assertEquals(0x98.toByte(), bytes[emojiAt + 2])
        assertEquals(0x80.toByte(), bytes[emojiAt + 3])
        val splitAt = emojiAt + 2

        assertTrue(parser.feed(bytes.copyOfRange(0, splitAt)).isEmpty())
        val events = parser.feed(bytes.copyOfRange(splitAt, bytes.size))

        assertEquals(1, events.size)
        val data = parser.takeEvent(events.single())
        assertEquals(json, data)
    }

    @Test
    fun crlfFramingEmitsEvents() {
        // Upstream providers are forwarded verbatim, so CRLF-only framing must
        // split events and terminate [DONE] just like LF framing.
        val parser = SseParser()

        val events = parser.feed("data: $payload\r\n\r\ndata: [DONE]\r\n".toByteArray(Charsets.UTF_8))

        assertEquals(2, events.size)
        assertEquals(payload, parser.takeEvent(events[0]))
        assertEquals("[DONE]", parser.takeEvent(events[1]))
    }

    @Test
    fun doneWithoutTrailingNewline() {
        val parser = SseParser()

        // The terminator can be the last bytes on the socket: no blank line,
        // no newline, and no opportunity to flush at end of input.
        val events = parser.feed("data: [DONE]".toByteArray(Charsets.UTF_8))

        assertEquals(listOf("data: [DONE]"), events)
        assertEquals("[DONE]", parser.takeEvent(events.single()))
    }
}
