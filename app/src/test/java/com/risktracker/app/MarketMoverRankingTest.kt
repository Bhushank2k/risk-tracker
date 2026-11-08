package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketMoverRankingTest {

    @Test
    fun ranksBroadMarketMoversAndKeepsBothDirections() {
        val rows = listOf(
            MarketMover("BTCUSDT", 65000.0, 1.0, 2_000_000_000.0, 2.0),
            MarketMover("ALPHAUSDT", 1.0, 18.0, 4_000_000.0, 22.0),
            MarketMover("BETAUSDT", 2.0, -14.0, 8_000_000.0, 18.0)
        )

        val ranked = rankMarketMovers(rows)

        assertEquals(3, ranked.size)
        assertTrue(ranked.first().symbol == "ALPHAUSDT" || ranked.first().symbol == "BETAUSDT")
        assertTrue(ranked.any { it.changePct > 0.0 })
        assertTrue(ranked.any { it.changePct < 0.0 })
    }

    @Test
    fun removesInvalidAndDuplicateRowsAndHonorsLimit() {
        val rows = listOf(
            MarketMover("DUPUSDT", 1.0, 5.0, 2_000_000.0, 6.0),
            MarketMover("DUPUSDT", 1.1, 6.0, 3_000_000.0, 7.0),
            MarketMover("BADUSDT", Double.NaN, 20.0, 5_000_000.0, 21.0),
            MarketMover("ZEROUSDT", 1.0, 2.0, 0.0, 3.0),
            MarketMover("GOODUSDT", 2.0, -4.0, 2_000_000.0, 5.0)
        )

        val ranked = rankMarketMovers(rows, limit = 1)

        assertEquals(1, ranked.size)
        assertEquals("DUPUSDT", ranked.single().symbol)
        assertTrue(rankMarketMovers(rows, limit = 0).isEmpty())
    }
}
