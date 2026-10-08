package dev.humanagent.doc

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxTest {

    private fun freshFile(): File =
        File(Files.createTempDirectory("humanphone-docx").toFile(), "book.docx")

    @Test
    fun writeThenExtractRoundTrip() {
        val out = freshFile()
        val sections = listOf(
            DocSection("Intro", "Hello line one.\nLine two."),
            DocSection("Details", "Second body text."),
            DocSection("Line breaks", "A\r\nB"),
        )
        Docx.write("Cafe Luna", sections, out)
        val expected = listOf(
            "Cafe Luna",
            "Intro", "Hello line one.", "Line two.",
            "Details", "Second body text.",
            "Line breaks", "A", "B",
        ).joinToString("\n")
        assertEquals(expected, Docx.extract(out.readBytes()))
    }

    @Test
    fun escapesXmlEntitiesAndRoundTripsThem() {
        val out = freshFile()
        val tricky = "Tom & Jerry <co> \"quoted\" 'single' &amp;"
        Docx.write("T & T", listOf(DocSection("A < B", tricky)), out)
        val xml = entryText(out.readBytes(), "word/document.xml")
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;co&gt;"))
        assertTrue(xml.contains("&quot;quoted&quot;"))
        assertTrue(xml.contains("&apos;single&apos;"))
        assertFalse(xml.contains("<co>"))
        assertFalse(xml.contains("Tom & Jerry"))
        assertEquals(listOf("T & T", "A < B", tricky).joinToString("\n"), Docx.extract(out.readBytes()))
    }

    @Test
    fun packageHoldsTheMinimalParts() {
        val out = freshFile()
        Docx.write("Title", listOf(DocSection("S", "B")), out)
        val names = ZipInputStream(ByteArrayInputStream(out.readBytes())).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toSet()
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), names)
    }

    @Test
    fun extractReturnsEmptyForUnreadableInput() {
        assertEquals("", Docx.extract(ByteArray(0)))
        assertEquals("", Docx.extract("not a zip".toByteArray()))
    }

    private fun entryText(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == name }
                ?.let { String(zip.readBytes(), Charsets.UTF_8) }
                .orEmpty()
        }
}
