package dev.humanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModePromptsTest {

    @Test
    fun personaIsAppendedWhenSet() {
        val prompt = AutoModePrompts.system("Only messages from my family, in Arabic")
        assertTrue(prompt.contains(AutoModePrompts.BASE.trimStart()))
        assertTrue(prompt.contains("Only messages from my family, in Arabic"))
        // The persona must read as the user's own limits, above the base rules.
        assertTrue(prompt.contains("Special instructions from the user"))
        // The persona must come last so it stays salient after the base rules.
        assertTrue(prompt.indexOf("Special instructions") > prompt.indexOf("OTP"))
    }

    @Test
    fun blankPersonaLeavesTheBasePromptUnchanged() {
        for (persona in listOf("", "  \n ")) {
            if (persona.isBlank()) {
                assertTrue(AutoModePrompts.system(persona).contains(AutoModePrompts.BASE.trimStart()))
                assertFalse(AutoModePrompts.system(persona).contains("Special instructions"))
            }
        }
    }
}