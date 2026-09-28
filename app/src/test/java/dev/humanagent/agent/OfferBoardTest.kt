package dev.humanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferBoardTest {

    @Test
    fun cheapestIsRankedFirstAndNamed() {
        val board = OfferBoard()
        board.record("Turkish Airlines Basic", 289.0, "EUR", "nonstop, 1 bag")
        board.record("Kiwi Combo", 249.5, "EUR", "2 stops")
        board.record("SkyScan Pick", null, "", "price hidden")
        val report = board.compare()
        assertTrue(report.contains("Best price: Kiwi Combo at 249.5 EUR"))
        assertTrue(report.indexOf("Kiwi Combo") < report.indexOf("Turkish Airlines Basic"))
        assertTrue(report.contains("price not shown"))
    }

    @Test
    fun fewOptionsCarryANudge() {
        val board = OfferBoard()
        board.record("Only option", 100.0, "USD", "")
        assertTrue(board.compare().contains("record more options"))
    }

    @Test
    fun emptyBoardNudgesToRecord() {
        assertTrue(OfferBoard().compare().contains("No offers recorded"))
    }

    @Test
    fun sameNameReplacesTheOffer() {
        val board = OfferBoard()
        board.record("Flight A", 300.0, "USD", "")
        board.record("Flight A", 250.0, "USD", "")
        assertEquals(1, board.size())
        assertTrue(board.compare().contains("250.0 USD"))
    }

    @Test
    fun unreadablePricesSortLast() {
        val board = OfferBoard()
        board.record("Hidden Price", null, "", "")
        board.record("Cheap One", 10.0, "", "")
        val first = board.compare().lines()[1]
        assertTrue(first.startsWith("1. Cheap One"))
    }
}