package dev.humanagent.agent

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owed-result ledger: an obligation opened for an auto task must stay tracked across
 * processes and delivery attempts until it is confirmed delivered — that is the whole answer
 * to "the agent forgot to reply with the result".
 */
class RunLedgerTest {

    private fun fresh(): File = File.createTempFile("ledger", ".json").also { it.delete() }

    @Test
    fun obligationStaysOpenUntilDeliveredAndSurvivesAReopen() = runTest {
        val file = fresh()
        val ledger = RunLedger(file)
        ledger.load()
        val obligation = ledger.open("text Sam the weather", "the conversation \"Sam\" in WhatsApp", "WhatsApp")
        assertNull(ledger.current("WhatsApp", "the conversation \"Sam\" in WhatsApp"))
        ledger.setResult(obligation.id, "It is 24C in Marrakech, no rain.")
        val owed = ledger.current("WhatsApp", "the conversation \"Sam\" in WhatsApp")
        assertTrue(owed!!.open)

        val reopened = RunLedger(file)
        reopened.load()
        val again = reopened.current("WhatsApp", "the conversation \"Sam\" in WhatsApp")
        assertTrue(again!!.open)
        assertEquals("It is 24C in Marrakech, no rain.", again.result)
        file.delete()
    }

    @Test
    fun confirmingDeliveryClosesItAndDropsItFromThePrompt() = runTest {
        val file = fresh()
        val ledger = RunLedger(file)
        ledger.load()
        val obligation = ledger.open("compare flights", "the conversation \"Sam\" in WhatsApp", "WhatsApp")
        ledger.setResult(obligation.id, "Cheapest: Royal Air Maroc at 289 EUR.")
        assertTrue(ledger.render().contains("Cheapest: Royal Air Maroc"))
        ledger.markDelivered(obligation.id)
        assertEquals("", ledger.render())
        assertNull(ledger.openObligations().firstOrNull { it.id == obligation.id && it.open })
        file.delete()
    }

    @Test
    fun threeFailedDeliveryAttemptsAbandonTheObligation() = runTest {
        val file = fresh()
        val ledger = RunLedger(file)
        ledger.load()
        val obligation = ledger.open("find a hotel", "the conversation \"Nadia\" in Telegram", "Telegram")
        ledger.setResult(obligation.id, "Best option: Riad Kalaa at 45 EUR.")
        repeat(RunLedger.MAX_ATTEMPTS) { index ->
            val updated = ledger.recordAttempt(obligation.id)
            if (index < RunLedger.MAX_ATTEMPTS - 1) {
                assertTrue(updated!!.open)
            } else {
                assertFalse(updated!!.open)
            }
        }
        assertEquals("", ledger.render())
        file.delete()
    }

    @Test
    fun reMentioningTheSameConversationRefreshesOneObligation() = runTest {
        val file = fresh()
        val ledger = RunLedger(file)
        ledger.load()
        val first = ledger.open("check the weather", "the conversation \"Sam\" in WhatsApp", "WhatsApp")
        val again = ledger.open("check it again", "the conversation \"Sam\" in WhatsApp", "WhatsApp")
        assertTrue(first.id != again.id)
        assertEquals(1, ledger.openObligations().size)
        assertEquals(again.id, ledger.openObligations().single().id)
        file.delete()
    }

    @Test
    fun obligationsOlderThanADayAreDroppedOnLoad() = runTest {
        val file = fresh()
        file.writeText(
            """{"obligations":[{"id":"o1","openedAtMs":0,"task":"old task","destination":"d","app":"A","result":"r"}]}""",
        )
        val ledger = RunLedger(file)
        ledger.load()
        assertNull(ledger.current())
        file.delete()
    }
}