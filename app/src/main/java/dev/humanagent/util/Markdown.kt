package dev.humanagent.util

/** A run of text with the inline styling the model asked for. */
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

/**
 * Tiny markdown reader for chat, speech and notifications. Models answer with `**bold**`, `#`
 * headings and code fences even when told not to, so the app renders the emphasis instead of showing
 * the punctuation, and speaks the words without the syntax.
 *
 * Supported: `**bold**`, `*italic*`, `` `code` ``, ``` fences ```, `#` headings, `-`/`1.` lists and
 * backslash escapes. Underscores are deliberately left alone so `snake_case` stays readable, and a
 * marker with no partner is printed literally rather than swallowing the rest of the message.
 */
object Markdown {

    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val bullet = Regex("^\\s{0,3}[-+*]\\s+")
    private val ordered = Regex("^\\s{0,3}\\d+[.)]\\s+")
    private val fence = Regex("^\\s{0,3}```")

    fun parse(raw: String): List<MarkdownSpan> {
        if (raw.isEmpty()) return emptyList()
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val logical = ArrayList<Pair<String, Boolean>>(8)
        var inFence = false
        normalized.split('\n').forEach { line ->
            if (fence.containsMatchIn(line)) {
                inFence = !inFence
                return@forEach
            }
            logical += line to inFence
        }
        val spans = ArrayList<MarkdownSpan>(8)
        logical.forEachIndexed { index, entry ->
            if (index > 0) spans += MarkdownSpan("\n")
            val line = entry.first
            if (entry.second) {
                spans += MarkdownSpan(line, code = true)
                return@forEachIndexed
            }
            val headingMatch = heading.find(line)
            val orderedMatch = ordered.find(line)
            val bulletMatch = bullet.find(line)
            val body = when {
                headingMatch != null -> line.substring(headingMatch.value.length)
                orderedMatch != null -> orderedMatch.value.trim() + " " + line.substring(orderedMatch.value.length)
                bulletMatch != null -> "• " + line.substring(bulletMatch.value.length)
                else -> line
            }
            spans += inline(body, bold = headingMatch != null, italic = false, code = false)
        }
        return spans
    }

    /** The same text with every marker removed, ready to be spoken. */
    fun strip(raw: String): String = parse(raw).joinToString("") { it.text }

    /** One-paragraph version for notifications: newlines and bullets become separators. */
    fun singleLine(raw: String): String = strip(raw)
        .replace('\n', ' ')
        .replace("• ", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun inline(text: String, bold: Boolean, italic: Boolean, code: Boolean): List<MarkdownSpan> {
        val spans = ArrayList<MarkdownSpan>(4)
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += MarkdownSpan(buffer.toString(), bold = bold, italic = italic, code = code)
                buffer.setLength(0)
            }
        }

        while (index < text.length) {
            val char = text[index]
            when {
                char == '\\' && index + 1 < text.length -> {
                    buffer.append(text[index + 1])
                    index += 2
                }

                char == '`' -> {
                    val close = closingMarker(text, index, "`", 1)
                    if (close > index) {
                        flush()
                        spans += MarkdownSpan(text.substring(index + 1, close), code = true)
                        index = close + 1
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                text.startsWith("**", index) -> {
                    val close = closingMarker(text, index, "**", 2)
                    if (close > index) {
                        flush()
                        spans += inline(text.substring(index + 2, close), bold = true, italic = italic, code = false)
                        index = close + 2
                    } else {
                        buffer.append("**")
                        index += 2
                    }
                }

                char == '*' -> {
                    val close = closingMarker(text, index, "*", 1)
                    if (close > index) {
                        flush()
                        spans += inline(text.substring(index + 1, close), bold = bold, italic = true, code = false)
                        index = close + 1
                    } else {
                        buffer.append(char)
                        index++
                    }
                }

                else -> {
                    buffer.append(char)
                    index++
                }
            }
        }
        flush()
        return spans
    }

    /**
     * Finds the partner marker for [marker] at [start], or -1 when there is none. A partner must not
     * be glued to whitespace, which is what keeps "2 * 3 * 4" and "5 ** 6" as plain arithmetic.
     */
    private fun closingMarker(text: String, start: Int, marker: String, length: Int): Int {
        if (start + length >= text.length || text[start + length].isWhitespace()) return -1
        var searchFrom = start + length
        while (true) {
            val candidate = text.indexOf(marker, searchFrom)
            if (candidate < 0) return -1
            val precededByContent = !text[candidate - 1].isWhitespace()
            val isProperStart = marker != "*" || !text.startsWith("**", candidate)
            if (precededByContent && isProperStart) return candidate
            searchFrom = candidate + length
        }
    }
}
