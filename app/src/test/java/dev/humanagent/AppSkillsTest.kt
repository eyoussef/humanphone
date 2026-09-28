package dev.humanagent

import dev.humanagent.agent.AppSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app notes are what lets the agent drive a real app instead of guessing. Matching has to hit
 * for the package on screen and for the name the user or the model writes.
 */
class AppSkillsTest {

    @Test
    fun canvaIsMatchedByPackageAndByName() {
        assertEquals("Canva", AppSkills.forApp("Canva", "com.canva.editor")?.appName)
        assertEquals("Canva", AppSkills.forQuery("Canva")?.appName)
        assertEquals("Canva", AppSkills.forQuery("com.canva.editor")?.appName)
    }

    @Test
    fun canvaNotesExplainTheSidewaysToolbarAndThePromptSubmit() {
        val notes = AppSkills.forApp("Canva", "com.canva.editor")!!.notes.joinToString(" ")
        assertTrue(notes.contains("Canva AI"))
        assertTrue(notes.contains("scroll_in"))
        assertTrue(notes.contains("press_enter"))
        assertTrue(notes.contains("wait_for_text"))
    }

    @Test
    fun everyBuiltInSkillNamesItsAppAndHasNotes() {
        assertTrue(AppSkills.builtIn.size >= 12)
        AppSkills.builtIn.forEach { skill ->
            assertTrue(skill.appName.isNotBlank())
            assertTrue(skill.notes.isNotEmpty())
            assertTrue(skill.packages.isNotEmpty())
        }
    }

    @Test
    fun anAppNobodyWroteANoteForStaysUnknown() {
        assertNull(AppSkills.forApp("FitFlow", "com.example.fitflow"))
        assertNull(AppSkills.forQuery("FitFlow"))
    }
}
