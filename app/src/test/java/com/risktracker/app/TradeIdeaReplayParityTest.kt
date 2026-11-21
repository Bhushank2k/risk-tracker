package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TradeIdeaReplayParityTest {

    private fun series(count: Int, stepMinutes: Long, price: (Int) -> Double): List<V2Candle> =
        (0 until count).map { i ->
            val close = price(i)
            V2Candle(close + 0.5, close - 0.5, close, 100.0, (i + 1) * stepMinutes * 60_000L, close)
        }

    /** Builds a higher timeframe from 5-minute closes by grouping [ratio] bars. */
    private fun aggregate(m5: List<V2Candle>, ratio: Int): List<V2Candle> =
        m5.chunked(ratio).filter { it.size == ratio }.map { group ->
            V2Candle(
                high = group.maxOf { it.high }, low = group.minOf { it.low }, close = group.last().close,
                volume = group.sumOf { it.volume }, closeTime = group.last().closeTime, open = group.first().open
            )
        }

    @Test
    fun flatMarketProducesNoTrades() {
        val stats = TradeIdeasV2.backtest(
            series(60, 240) { 100.0 }, series(300, 60) { 100.0 },
            series(1200, 15) { 100.0 }, series(3600, 5) { 100.0 }, maxHoldingBars = 96
        )
        assertEquals(0, stats.trades)
    }

    @Test
    fun insufficientHistoryProducesNoTrades() {
        val stats = TradeIdeasV2.backtest(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(0, stats.trades)
    }

    @Test
    fun randomWalkReplayIsDeterministicAndFinite() {
        val random = Random(7)
        var price = 100.0
        val m5 = (0 until 4000).map { i ->
            val open = price
            price = (price + random.nextDouble(-0.4, 0.4)).coerceAtLeast(1.0)
            val high = maxOf(open, price) + random.nextDouble(0.0, 0.2)
            val low = minOf(open, price) - random.nextDouble(0.0, 0.2)
            V2Candle(high, low, price, 100.0 + random.nextDouble(0.0, 50.0), (i + 1) * 5 * 60_000L, open)
        }
        val h4 = aggregate(m5, 48)
        val h1 = aggregate(m5, 12)
        val m15 = aggregate(m5, 3)
        val first = TradeIdeasV2.backtest(h4, h1, m15, m5, maxHoldingBars = 96, horizon = "Intraday")
        val second = TradeIdeasV2.backtest(h4, h1, m15, m5, maxHoldingBars = 96, horizon = "Intraday")
        assertEquals(first.trades, second.trades)
        assertEquals(first.averageR, second.averageR, 0.0)
        assertTrue(first.averageR.isFinite())
        assertTrue(first.wins <= first.trades)
    }
}
