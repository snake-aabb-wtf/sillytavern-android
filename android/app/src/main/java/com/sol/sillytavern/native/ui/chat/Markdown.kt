package com.sol.sillytavern.native.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * A deliberately small Markdown renderer for chat bubbles.
 *
 * It supports exactly what chat replies use in practice — strong/emphasis, inline
 * code, fenced code blocks, blockquotes, bullet and numbered lists, and links —
 * without pulling a CommonMark parser into an offline APK. Anything else is
 * emitted as literal text, which is the safe failure mode for an LLM's output.
 *
 * The parser is line-oriented: block markers (`>`, `-`, fences) are recognised at
 * the start of a line, and inline spans are parsed inside what remains. Markdown
 * is not nested across blocks (a list inside a quote stays literal), which keeps
 * the 60-line scanner predictable.
 */
internal fun renderMarkdown(source: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val lines = source.split('\n')
    var inFence = false
    var fenceBody = StringBuilder()
    var wroteLine = false

    fun nextLine() {
        if (wroteLine) builder.append('\n')
        wroteLine = true
    }

    for (raw in lines) {
        val line = raw.trimEnd('\r')
        val trimmed = line.trimStart()
        when {
            inFence -> {
                if (trimmed.startsWith(FENCE)) {
                    nextLine()
                    appendCodeBlock(builder, fenceBody.toString())
                    fenceBody = StringBuilder()
                    inFence = false
                } else {
                    fenceBody.append(line).append('\n')
                }
            }
            trimmed.startsWith(FENCE) -> {
                // The info string after the fence (```kotlin) is presentation only.
                inFence = true
            }
            trimmed.startsWith(">") -> {
                nextLine()
                builder.append(QUOTE_PREFIX)
                builder.appendInline(trimmed.removePrefix(">").trimStart())
            }
            BULLET.containsMatchIn(line) -> {
                nextLine()
                builder.append(BULLET_PREFIX)
                builder.appendInline(line.replaceFirst(BULLET, ""))
            }
            ORDERED.containsMatchIn(line) -> {
                nextLine()
                val marker = ORDERED.find(line)
                if (marker != null) {
                    builder.append(marker.value.trim())
                    builder.append(' ')
                    builder.appendInline(line.substring(marker.range.last + 1))
                }
            }
            else -> {
                nextLine()
                builder.appendInline(line)
            }
        }
    }
    if (inFence) {
        // An unterminated fence still renders as code: truncating the stream mid
        // block must not dump backticks into the bubble.
        nextLine()
        appendCodeBlock(builder, fenceBody.toString())
    }
    return builder.toAnnotatedString()
}

private fun appendCodeBlock(builder: AnnotatedString.Builder, body: String) {
    val code = body.trimEnd('\n')
    if (code.isEmpty()) return
    builder.withStyle(CODE_BLOCK_STYLE) { append(code) }
}

/**
 * Inline spans, recursive so `**bold with *italic***` works. A marker without a
 * closing partner is emitted literally; that is what a half-streamed reply looks
 * like and it must not leave the bubble with an unclosed span.
 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(BOLD) { appendInline(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(c)
                    i++
                }
            }
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    withStyle(BOLD) { appendInline(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(c)
                    i++
                }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(CODE) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            c == '[' -> {
                val labelEnd = text.indexOf(']', i + 1)
                val urlStart = labelEnd + 1
                if (labelEnd > i && urlStart < text.length && text[urlStart] == '(') {
                    val urlEnd = text.indexOf(')', urlStart + 1)
                    if (urlEnd > urlStart + 1) {
                        val label = text.substring(i + 1, labelEnd)
                        val url = text.substring(urlStart + 1, urlEnd).trim()
                        if (url.isNotEmpty()) {
                            withLink(LinkAnnotation.Url(url, TextLinkStyles(style = LINK))) {
                                appendInline(label)
                            }
                            i = urlEnd + 1
                        } else {
                            append(c)
                            i++
                        }
                    } else {
                        append(c)
                        i++
                    }
                } else {
                    append(c)
                    i++
                }
            }
            c == '*' || c == '_' -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    withStyle(ITALIC) { appendInline(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

private const val FENCE = "```"
private const val QUOTE_PREFIX = "▎ "
private const val BULLET_PREFIX = "• "

private val BULLET = Regex("""^\s*[-*+]\s+""")
private val ORDERED = Regex("""^\s*\d+[.)]\s+""")

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val CODE = SpanStyle(fontFamily = FontFamily.Monospace)
private val CODE_BLOCK_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)
private val LINK = SpanStyle(textDecoration = TextDecoration.Underline)
