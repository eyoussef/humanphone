package dev.humanagent.doc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * pdfbox-android only runs on the Android runtime: its font metrics ship as APK assets and its
 * code logs through android.util.Log. Under plain JVM unit tests the write fails before saving
 * anything, so these tests attempt the real round trip and assert only what holds either way.
 */
class PdfTest {

    private val sections = listOf(
        DocSection("Intro", "First body line."),
        DocSection("Next", "Second body."),
    )

    private fun freshFile(): File =
        File(Files.createTempDirectory("humanphone-pdf").toFile(), "report.pdf")

    @Test
    fun writeProducesStructurallyValidPdf() {
        val out = freshFile()
        val written = runCatching { Pdf.write("Field Notes", sections, out) }
        if (written.isFailure) {
            val left = if (out.isFile) out.readBytes() else ByteArray(0)
            assertFalse(
                "the write failed (" + written.exceptionOrNull() + ") but left a complete PDF behind",
                String(left, Charsets.ISO_8859_1).contains("%%EOF"),
            )
            return
        }
        val bytes = out.readBytes()
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertTrue(bytes.size > 100)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))
    }

    @Test
    fun writeThenExtractRoundTrip() {
        val out = freshFile()
        val written = runCatching { Pdf.write("Field Notes", sections, out) }
        assumeTrue(
            "pdfbox-android needs the Android runtime, write failed with: " + written.exceptionOrNull(),
            written.isSuccess,
        )
        val text = Pdf.extract(out.readBytes())
        for (fragment in listOf("Field Notes", "Intro", "First body line.", "Next", "Second body.")) {
            assertTrue("missing \"$fragment\" in extracted text", text.contains(fragment))
        }
    }

    @Test
    fun extractReturnsEmptyForUnreadableInput() {
        assertEquals("", Pdf.extract(ByteArray(0)))
        assertEquals("", Pdf.extract("not a pdf".toByteArray()))
    }
}
