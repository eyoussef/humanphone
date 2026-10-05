package dev.humanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDecisionTest {

    @Test
    fun taskOnlyReplyActs() {
        val parsed = AutoDecision.parse(
            """{"action":"Reply","replyText":"","task":"Check today's weather in Marrakech and Agadir","app":"WhatsApp","note":"ack later"}""",
        )
        assertEquals("reply", parsed.action)
        assertEquals("Check today's weather in Marrakech and Agadir", parsed.task)
        assertTrue(parsed.actionable)
    }

    @Test
    fun replyTextAloneStillActs() {
        val parsed = AutoDecision.parse(
            """{"action":"reply","replyText":"Yes, I will be there at 8.","task":"","app":"WhatsApp","note":"directanswer"}""",
        )
        assertTrue(parsed.actionable)
        assertTrue(parsed.task.isBlank())
    }

    @Test
    fun skipAndEmptyRepliesNeverAct() {
        val parsed = AutoDecision.parse(
            """{"action":"reply","replyText":"","task":"","app":"WhatsApp","note":"nothing to say"}""",
        )
        assertFalse(parsed.actionable)
        assertFalse(
            AutoDecision.parse("""{"action":"skip","replyText":"","task":"","app":"","note":""}""").actionable,
        )
    }

    @Test
    fun unreadableVerdictIsASkip() {
        val parsed = AutoDecision.parse("not json at all")
        assertEquals(AutoDecision.SKIP, parsed.action)
        assertFalse(parsed.actionable)
    }

    @Test
    fun fencedVerdictStillParses() {
        val fenced = "```json\n" +
            "{\"action\":\"reply\",\"replyText\":\"Give me a minute.\"," +
            "\"task\":\"Check today's weather in Marrakech\",\"app\":\"WhatsApp\",\"note\":\"ack+task\"}\n" +
            "```"
        val parsed = AutoDecision.parse(fenced)
        assertTrue(parsed.actionable)
        assertEquals("Give me a minute.", parsed.replyText)
        assertEquals("Check today's weather in Marrakech", parsed.task)
        assertEquals("WhatsApp", parsed.app)
    }

    @Test
    fun proseWrappedVerdictStillParses() {
        val parsed = AutoDecision.parse(
            "Sure! Here is my decision: {\"action\":\"reply\",\"replyText\":\"On it.\"," +
                "\"task\":\"\",\"app\":\"Gmail\",\"note\":\"wants the reply sent\"} — done.",
        )
        assertTrue(parsed.actionable)
        assertEquals("On it.", parsed.replyText)
        assertEquals("Gmail", parsed.app)
    }
}