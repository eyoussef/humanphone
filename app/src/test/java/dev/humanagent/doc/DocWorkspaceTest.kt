package dev.humanagent.doc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class DocWorkspaceTest {

    private fun fresh(): DocWorkspace =
        DocWorkspace(Files.createTempDirectory("humanphone-docs").toFile())

    @Test
    fun createSlugifiesAndBecomesCurrent() {
        val workspace = fresh()
        assertEquals("", workspace.name())
        assertNull(workspace.currentDir())
        val dir = workspace.create("Cafe Luna")
        assertEquals("cafe-luna", dir.name)
        assertEquals(dir, workspace.currentDir())
        assertEquals("Cafe Luna", workspace.name())
        assertTrue(File(dir, "project.json").isFile)
        assertEquals(emptyList<DocSection>(), workspace.sections())
    }

    @Test
    fun openCreatesMissingDocumentsAndSwitches() {
        val workspace = fresh()
        val dir = workspace.open("Brand New")
        assertEquals("brand-new", dir.name)
        assertEquals("Brand New", workspace.name())
        workspace.create("Other")
        assertEquals("Other", workspace.name())
        workspace.open("brand new")
        assertEquals("Brand New", workspace.name())
    }

    @Test
    fun addSectionCapsTitleBodyAndCount() {
        val workspace = fresh()
        workspace.create("caps")
        workspace.addSection("t".repeat(200), "b".repeat(20_500))
        val capped = workspace.sections().single()
        assertEquals(120, capped.title.length)
        assertEquals(20_000, capped.body.length)
        repeat(DocWorkspace.MAX_SECTIONS - 1) { workspace.addSection("s$it", "b$it") }
        assertEquals(DocWorkspace.MAX_SECTIONS, workspace.sections().size)
        assertThrows(IllegalArgumentException::class.java) { workspace.addSection("one too many", "") }
    }

    @Test
    fun projectJsonSurvivesReload() {
        val root = Files.createTempDirectory("humanphone-docs").toFile()
        val first = DocWorkspace(root)
        first.create("Cafe Luna")
        first.addSection("Intro", "Hello line one.\nLine two.")
        first.addSection("Details", "Second body text.")
        val second = DocWorkspace(root)
        assertEquals(emptyList<DocSection>(), second.sections())
        second.open("cafe luna")
        assertEquals("Cafe Luna", second.name())
        assertEquals(
            listOf(
                DocSection("Intro", "Hello line one.\nLine two."),
                DocSection("Details", "Second body text."),
            ),
            second.sections(),
        )
    }

    @Test
    fun renderWritesSlugNamedFiles() {
        val workspace = fresh()
        workspace.create("Cafe Luna")
        workspace.addSection("Intro", "Body.")
        val docx = workspace.render("DOCX")
        assertEquals("cafe-luna.docx", docx.name)
        assertTrue(docx.isFile)
        assertEquals("Cafe Luna\nIntro\nBody.", Docx.extract(docx.readBytes()))
        assertThrows(IllegalArgumentException::class.java) { workspace.render("html") }
    }

    @Test
    fun renderNamesThePdfAfterTheSlugWhenPdfboxRuns() {
        val workspace = fresh()
        workspace.create("Cafe Luna")
        workspace.addSection("Intro", "Body.")
        val rendered = runCatching { workspace.render("pdf") }
        assumeTrue(
            "pdfbox-android needs the Android runtime, write failed with: " + rendered.exceptionOrNull(),
            rendered.isSuccess,
        )
        assertEquals("cafe-luna.pdf", rendered.getOrThrow().name)
    }

    @Test
    fun listDescribesDocuments() {
        assertEquals("No documents yet.", fresh().list())
        val workspace = fresh()
        workspace.create("Cafe Luna")
        workspace.addSection("Intro", "Body.")
        workspace.addSection("More", "Text.")
        workspace.render("docx")
        val listing = workspace.list()
        assertTrue(listing.contains("Cafe Luna — 2 sections"))
        assertTrue(listing.contains("cafe-luna.docx"))
    }

    @Test
    fun mutationsRequireAnOpenDocument() {
        val workspace = fresh()
        assertThrows(IllegalStateException::class.java) { workspace.addSection("t", "b") }
        assertThrows(IllegalStateException::class.java) { workspace.render("docx") }
        assertEquals(emptyList<DocSection>(), workspace.sections())
    }
}
