package com.sol.sillytavern.native.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Incremental parser for the OpenAI-style SSE stream served by
 * `/api/native/chat`.
 *
 * Socket reads split the stream at arbitrary byte offsets, so two boundaries
 * must be tolerated:
 *
 *  - an event split across reads: [feed] keeps undecoded text until a blank
 *    line arrives;
 *  - a UTF-8 code point split across reads: one [CharsetDecoder] is reused and
 *    the bytes it could not consume stay pending for the next [feed]. Decoding
 *    each chunk independently would turn a split emoji into two U+FFFD.
 *
 * [CodingErrorAction.REPORT] is deliberate: REPLACE would silently corrupt a
 * stream instead of failing the turn.
 */
class SseParser {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /** Bytes held back because they are a prefix of an incomplete code point. */
    private var pending = ByteArray(0)

    /** Decoded text that has not yet formed a complete event. */
    private val text = StringBuilder()

    /**
     * Decodes [bytes] and returns the event blocks completed by this feed.
     *
     * A block is the raw frame text (`event:` and `data:` lines), without the
     * blank line that terminated it. Feed the result to [takeEvent] for the
     * payload. The terminal `data: [DONE]` may arrive without a trailing blank
     * line; it is recognised as a complete event on its own.
     */
    fun feed(bytes: ByteArray): List<String> {
        decode(bytes)

        val events = mutableListOf<String>()
        var boundary = text.indexOf("\n\n")
        while (boundary >= 0) {
            val event = text.substring(0, boundary)
            text.delete(0, boundary + 2)
            if (event.isNotBlank()) events.add(event)
            boundary = text.indexOf("\n\n")
        }

        // [DONE] can be the last bytes on the socket: no blank line follows, so
        // the loop above never sees a boundary. It is the protocol's terminal
        // sentinel, so a lone complete data line is safe to emit early.
        if (isTerminalSentinel()) {
            events.add(text.toString().trimEnd('\n', '\r'))
            text.setLength(0)
        }
        return events
    }

    /**
     * Extracts the payload of one event block. Multiple `data:` lines are
     * joined with `\n` as the SSE spec requires; a leading single space after
     * the colon is removed. Returns null when the block carries no data (for
     * example a comment/heartbeat frame).
     */
    fun takeEvent(line: String): String? {
        val data = StringBuilder()
        var sawData = false
        for (raw in line.split('\n')) {
            val field = raw.trimEnd('\r')
            if (!field.startsWith("data:")) continue
            var value = field.substring("data:".length)
            if (value.startsWith(" ")) value = value.substring(1)
            if (sawData) data.append('\n')
            data.append(value)
            sawData = true
        }
        return if (sawData) data.toString() else null
    }

    private fun decode(bytes: ByteArray) {
        if (bytes.isEmpty() && pending.isEmpty()) return
        val input = if (pending.isEmpty()) bytes else pending + bytes
        pending = ByteArray(0)

        val source = ByteBuffer.wrap(input)
        var chars = CharBuffer.allocate(input.size + 1)
        var result: CoderResult = decoder.decode(source, chars, false)
        while (result.isOverflow) {
            chars.flip()
            text.append(chars)
            chars = CharBuffer.allocate(input.size + 1)
            result = decoder.decode(source, chars, false)
        }
        if (result.isError) result.throwException()
        chars.flip()
        text.append(chars)

        // Whatever the decoder could not consume is the prefix of a code point
        // that the next read will complete.
        if (source.hasRemaining()) {
            val rest = ByteArray(source.remaining())
            source.get(rest)
            pending = rest
        }
    }

    private fun isTerminalSentinel(): Boolean {
        if (text.isEmpty()) return false
        val frame = text.toString().trimEnd('\n', '\r')
        if (!frame.startsWith("data:")) return false
        if (frame.indexOf('\n') >= 0) return false
        return frame.substring("data:".length).trim() == "[DONE]"
    }
}
