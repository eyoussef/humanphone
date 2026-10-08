package dev.humanagent.doc

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import javax.xml.parsers.DocumentBuilderFactory

/** One titled block of a document: the heading and the body text under it. */
data class DocSection(val title: String, val body: String)

/**
 * Minimal OOXML .docx writer/reader. The package holds no styles.xml: every run carries its own
 * properties (bold + size), which is all Word needs to render a plain document.
 */
object Docx {

    /** Minimal valid OOXML .docx written to [out] (created/overwritten). */
    fun write(title: String, sections: List<DocSection>, out: File) {
        val body = buildString {
            append(DOC_OPEN)
            append(paragraph(title, bold = true, sizeHalfPoints = 40))
            for (section in sections) {
                append(paragraph(section.title, bold = true, sizeHalfPoints = 28))
                for (line in bodyLines(section.body)) {
                    append(paragraph(line, bold = false, sizeHalfPoints = 22))
                }
            }
            append(SECTION_PROPERTIES)
            append(DOC_CLOSE)
        }
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELATIONSHIPS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Plain text of a .docx: paragraph titles and bodies, \n between paragraphs. */
    fun extract(bytes: ByteArray): String = runCatching {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == "word/document.xml" }
                ?.let { String(zip.readBytes(), Charsets.UTF_8) }
        } ?: return@runCatching ""
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val paragraphs = document.getElementsByTagName("w:p")
        (0 until paragraphs.length).joinToString("\n") { index ->
            val runs = (paragraphs.item(index) as org.w3c.dom.Element).getElementsByTagName("w:t")
            buildString { for (run in 0 until runs.length) append(runs.item(run).textContent) }
        }
    }.getOrDefault("")

    /** Line breaks inside a body become separate paragraphs, whatever the flavour. */
    private fun bodyLines(body: String): List<String> = body.split(BODY_BREAKS)

    private fun paragraph(text: String, bold: Boolean, sizeHalfPoints: Int): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            append("<w:sz w:val=\"$sizeHalfPoints\"/>")
        }
        return "<w:p><w:r><w:rPr>$props</w:rPr><w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r></w:p>"
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (char in text) when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            // XML 1.0 cannot carry the remaining control characters; they would break the parser.
            else -> if (char.code >= 0x20 || char == '\t') append(char)
        }
    }

    private val BODY_BREAKS = Regex("\\r\\n|\\r|\\n")

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """</Types>"""

    private const val RELATIONSHIPS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""

    private const val DOC_OPEN =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""

    private const val SECTION_PROPERTIES = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr>"""

    private const val DOC_CLOSE = """</w:body></w:document>"""
}
