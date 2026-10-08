package dev.humanagent.doc

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Text-based PDF rendering and extraction on top of pdfbox-android (PDFBox 2.x API). */
object Pdf {

    /** Idempotent; call once from app code before write/extract (PdfBoxAndroid.init). Safe to call repeatedly. */
    fun ensureInit(context: Context) {
        // pdfbox-android keeps its font metrics and glyph tables in APK assets; this hands it
        // the AssetManager it loads them through. Repeating the call just stores it again.
        PDFBoxResourceLoader.init(context)
    }

    /** Multi-page A4 PDF with the document title and each section heading + body. */
    fun write(title: String, sections: List<DocSection>, out: File) {
        PDDocument().use { document ->
            val layout = Layout(document)
            layout.title(title)
            for (section in sections) {
                layout.heading(section.title)
                for (paragraph in section.body.split(BODY_BREAKS)) {
                    layout.body(paragraph)
                }
            }
            layout.close()
            out.parentFile?.mkdirs()
            document.save(out)
        }
    }

    /** Plain text of a text-based PDF (PDFTextStripper). */
    fun extract(bytes: ByteArray): String = runCatching {
        PDDocument.load(bytes).use { document ->
            PDFTextStripper().getText(document)
        }
    }.getOrDefault("")

    private val BODY_BREAKS = Regex("\\r\\n|\\r|\\n")

    private const val MARGIN = 56f
    private const val TITLE_SIZE = 20f
    private const val TITLE_LEADING = 24f
    private const val HEADING_SIZE = 14f
    private const val HEADING_LEADING = 18f
    private const val BODY_SIZE = 11f
    private const val BODY_LEADING = 15f
    private const val SECTION_GAP = 10f

    private val CONTENT_WIDTH = PDRectangle.A4.width - 2 * MARGIN
    private val TOP = PDRectangle.A4.height - MARGIN

    /**
     * WinAnsiEncoding is the standard-14 fonts' encoding: ASCII and Latin-1 plus these code
     * points, which it maps onto its single-byte codes.
     */
    private val WINANSI_EXTRA = intArrayOf(
        0x20AC, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160,
        0x2039, 0x0152, 0x017D, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x017E, 0x0178,
    )

    /** Anything WinAnsi cannot carry would make showText throw, so it becomes a question mark. */
    private fun sanitize(text: String): String = buildString(text.length) {
        for (char in text) when (val code = char.code) {
            0x09, 0x0A, 0x0D, 0x20, 0xA0 -> append(' ')
            in 0x21..0x7E, in 0xA1..0xFF -> append(char)
            else -> append(if (code in WINANSI_EXTRA) char else '?')
        }
    }

    private fun widthOf(text: String, font: PDType1Font, size: Float): Float =
        font.getStringWidth(text) / 1000f * size

    /** Greedy word wrap against the content width, breaking single words only when they overflow. */
    private fun wrap(text: String, font: PDType1Font, size: Float): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (word in sanitize(text).split(' ')) {
            var rest = word
            while (rest.length > 1 && widthOf(rest, font, size) > CONTENT_WIDTH) {
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line.setLength(0)
                }
                var cut = 1
                while (cut < rest.length && widthOf(rest.substring(0, cut + 1), font, size) <= CONTENT_WIDTH) cut++
                lines.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            val candidate = if (line.isEmpty()) rest else "$line $rest"
            if (line.isNotEmpty() && widthOf(candidate, font, size) > CONTENT_WIDTH) {
                lines.add(line.toString())
                line.setLength(0)
                line.append(rest)
            } else {
                line.setLength(0)
                line.append(candidate)
            }
        }
        lines.add(line.toString())
        return lines
    }

    /** One content stream at a time; a new page starts whenever the next line would fall below the margin. */
    private class Layout(private val document: PDDocument) {
        private val bold = PDType1Font.HELVETICA_BOLD
        private val regular = PDType1Font.HELVETICA
        private var page = PDPage(PDRectangle.A4).also { document.addPage(it) }
        private var content = PDPageContentStream(document, page)
        private var y = TOP

        fun title(text: String) {
            for (line in wrap(text, bold, TITLE_SIZE)) {
                need(TITLE_LEADING)
                draw(line, bold, TITLE_SIZE, TITLE_LEADING)
            }
            y -= BODY_LEADING
        }

        fun heading(text: String) {
            y -= SECTION_GAP
            need(HEADING_LEADING + BODY_LEADING)
            for (line in wrap(text, bold, HEADING_SIZE)) {
                need(HEADING_LEADING)
                draw(line, bold, HEADING_SIZE, HEADING_LEADING)
            }
        }

        fun body(text: String) {
            for (line in wrap(text, regular, BODY_SIZE)) {
                need(BODY_LEADING)
                draw(line, regular, BODY_SIZE, BODY_LEADING)
            }
        }

        fun close() {
            content.close()
        }

        private fun need(advance: Float) {
            if (y - advance < MARGIN) newPage()
        }

        private fun newPage() {
            content.close()
            page = PDPage(PDRectangle.A4)
            document.addPage(page)
            content = PDPageContentStream(document, page)
            y = TOP
        }

        private fun draw(line: String, font: PDType1Font, size: Float, leading: Float) {
            if (line.isNotEmpty()) {
                content.beginText()
                content.setFont(font, size)
                content.newLineAtOffset(MARGIN, y - size)
                content.showText(line)
                content.endText()
            }
            y -= leading
        }
    }
}
