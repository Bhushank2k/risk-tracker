package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinancePositionJournalTest {
    private fun fill(
        id: String,
        side: String,
        qty: Double,
        price: Double,
        time: Long,
        pnl: Double? = 0.0,
        market: String = "USDⓈ-M Futures",
        symbol: String = "BTCUSDT",
        fee: Double = 0.0,
        feeAsset: String = "USDT"
    ) = BinanceJournalFill(id, "Binance", market, symbol, side, qty, price, qty * price,
        fee, feeAsset, pnl, time, id)

    @Test
    fun groupsPartialLongEntriesAndExitsIntoOneClosedPosition() {
        val cycles = BinancePositionJournal.futuresCycles(listOf(
            fill("1", "BUY", 1.0, 100.0, 1, 0.0, fee = 1.0),
            fill("2", "BUY", 1.0, 120.0, 2, 0.0, fee = 1.0),
            fill("3", "SELL", 0.5, 130.0, 3, 5.0),
            fill("4", "SELL", 1.5, 140.0, 4, 20.0)
        ))
        assertEquals(1, cycles.size)
        val cycle = cycles.single()
        assertEquals("LONG", cycle.direction)
        assertEquals(2.0, cycle.openedQuantity, 1e-9)
        assertEquals(2.0, cycle.closedQuantity, 1e-9)
        assertEquals(110.0, cycle.averageEntryPrice!!, 1e-9)
        assertEquals(137.5, cycle.averageExitPrice!!, 1e-9)
        assertEquals(25.0, cycle.realizedPnl!!, 1e-9)
        assertEquals(2.0, cycle.feeTotals.getValue("USDT"), 1e-9)
        assertFalse(cycle.isUnclosed)
    }

    @Test
    fun groupsPartialShortEntriesAndExitsIntoOneClosedPosition() {
        val cycles = BinancePositionJournal.futuresCycles(listOf(
            fill("1", "SELL", 2.0, 100.0, 1, 0.0),
            fill("2", "BUY", 0.5, 90.0, 2, 5.0),
            fill("3", "BUY", 1.5, 80.0, 3, 30.0)
        ))
        assertEquals(1, cycles.size)
        assertEquals("SHORT", cycles.single().direction)
        assertEquals(2.0, cycles.single().closedQuantity, 1e-9)
        assertEquals(35.0, cycles.single().realizedPnl!!, 1e-9)
    }

    @Test
    fun splitsAReversalIntoTheClosedCycleAndNewOppositeCycle() {
        val cycles = BinancePositionJournal.futuresCycles(listOf(
            fill("1", "BUY", 1.0, 100.0, 1, 0.0),
            fill("2", "SELL", 1.5, 110.0, 2, 10.0)
        )).sortedBy { it.startTime }
        assertEquals(2, cycles.size)
        assertEquals("LONG", cycles[0].direction)
        assertEquals(1.0, cycles[0].closedQuantity, 1e-9)
        assertEquals(10.0, cycles[0].realizedPnl!!, 1e-9)
        assertEquals("SHORT", cycles[1].direction)
        assertEquals(0.5, cycles[1].unclosedQuantity, 1e-9)
        assertTrue(cycles[1].isUnclosed)
    }

    @Test
    fun keepsMarketsAndSymbolsSeparateAndDoesNotGuessSpotPnl() {
        val input = listOf(
            fill("1", "BUY", 1.0, 100.0, 1, null, market = "Spot"),
            fill("2", "SELL", 1.0, 110.0, 2, null, market = "Spot"),
            fill("3", "BUY", 1.0, 100.0, 3, 0.0, symbol = "ETHUSDT")
        )
        assertEquals(1, BinancePositionJournal.futuresCycles(input).size)
        val spot = BinancePositionJournal.spotActivity(input).single()
        assertEquals("BTCUSDT", spot.symbol)
        assertEquals(1.0, spot.baseQuantityBought, 1e-9)
        assertEquals(1.0, spot.baseQuantitySold, 1e-9)
        assertEquals(0, spot.fills.count { it.realizedPnl != null })
    }
}
