package dev.humanagent.site

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SiteWorkspaceTest {

    private fun fresh(): SiteWorkspace =
        SiteWorkspace(Files.createTempDirectory("humanphone-sites").toFile())

    @Test
    fun writesReadsAndListsFiles() {
        val workspace = fresh()
        workspace.open("Cafe Luna")
        assertEquals("cafe-luna", workspace.current)
        assertEquals("index.html", workspace.writeFile(null, "index.html", "<h1>hi</h1>"))
        assertEquals("<h1>hi</h1>", workspace.readFile(null, "index.html"))
        workspace.writeFile(null, "style.css", "body{}")
        val listing = workspace.list(null)
        assertTrue(listing.contains("index.html"))
        assertTrue(listing.contains("style.css"))
    }

    @Test
    fun refusesPathsThatEscapeTheSite() {
        val workspace = fresh()
        workspace.open("innocent")
        assertThrows(IllegalArgumentException::class.java) {
            workspace.writeFile(null, "../evil.html", "no")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.writeFile(null, "a/../../evil.html", "no")
        }
        assertNull(workspace.readFile(null, "../../secret.txt"))
    }

    @Test
    fun saveImageLandsInImagesWithCleanName() {
        val workspace = fresh()
        workspace.open("site")
        assertEquals("images/hero.png", workspace.saveImage(null, "hero", byteArrayOf(1), "png"))
        assertEquals(1, workspace.fileFor(null, "images/hero.png").length())
    }

    @Test
    fun openSwitchesTheCurrentSite() {
        val workspace = fresh()
        workspace.open("first")
        workspace.writeFile(null, "index.html", "one")
        workspace.open("second")
        assertNull(workspace.readFile(null, "index.html"))
        assertEquals("second", workspace.current)
    }
}