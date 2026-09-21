package dev.humanagent.util

/**
 * Turns a fetched web page into readable text without pulling in a browser engine.
 * Deliberately small: scripts, styles and tags go away, entities become characters, layout
 * whitespace collapses. Pure string work, so it is unit tested on the JVM.
 */
object HtmlText {

    private val scriptOrStyle = Regex(
        "<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val title = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val blockBreak = Regex(
        "</?(p|div|br|li|ul|ol|tr|td|th|h[1-6]|section|article|header|footer|nav|table|blockquote)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val anyTag = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val whitespace = Regex("\\s+")
    private val blankLines = Regex("\\n{2,}")
    private const val BLOCK_BREAK = '\u0000'
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "-", "mdash" to "-", "hellip" to "...", "lsquo" to "'", "rsquo" to "'",
        "ldquo" to "\"", "rdquo" to "\"", "laquo" to "<<", "raquo" to ">>", "times" to "x",
        "middot" to "-", "bull" to "-", "deg" to " degrees", "euro" to "EUR", "pound" to "GBP",
        "yen" to "JPY", "cent" to "c", "copy" to "(c)", "reg" to "(R)", "trade" to "(TM)",
        "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "",
    )
    private val namedEntity = Regex("&([a-zA-Z][a-zA-Z0-9]{1,31});")
    private val decimalEntity = Regex("&#(\\d{1,7});")
    private val hexEntity = Regex("&#[xX]([0-9a-fA-F]{1,6});")

    fun titleOf(html: String): String {
        val raw = title.find(html)?.groupValues?.get(1) ?: return ""
        return decodeEntities(anyTag.replace(raw, " ")).replace(whitespace, " ").trim()
    }

    fun toText(html: String, maxChars: Int = 6_000): String {
        var text = comments.replace(html, " ")
        text = scriptOrStyle.replace(text, " ")
        // Block level tags become a sentinel first so that ordinary source line breaks, which mean
        // nothing in HTML, collapse into spaces while real paragraph breaks survive.
        text = blockBreak.replace(text, BLOCK_BREAK.toString())
        text = anyTag.replace(text, " ")
        text = decodeEntities(text)
        text = whitespace.replace(text, " ")
        text = text.replace(BLOCK_BREAK, '\n')
        text = text.split('\n').joinToString("\n") { line -> line.trim() }
        text = blankLines.replace(text, "\n").trim()
        if (text.length <= maxChars) return text
        return text.take(maxChars).trimEnd() + " […]"
    }

    private fun decodeEntities(input: String): String {
        var text = input
        namedEntity.findAll(input).forEach { match ->
            val name = match.groupValues[1].lowercase()
            val replacement = entities[name] ?: return@forEach
            text = text.replace(match.value, replacement)
        }
        decimalEntity.findAll(input).forEach { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@forEach
            text = text.replace(match.value, codePoint(code))
        }
        hexEntity.findAll(input).forEach { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@forEach
            text = text.replace(match.value, codePoint(code))
        }
        return text
    }

    private fun codePoint(code: Int): String =
        if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
}
