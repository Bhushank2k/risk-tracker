package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed candle fixtures exercise the shared strategy implementation used by both clients. */
class ScannerCoreFixtureTest {
    @Test fun confirmedSwingPivotsClassifyHigherHighsAndHigherLows() {
        val highs = listOf(10.0, 11.0, 13.0, 11.0, 10.0, 12.0, 13.0, 15.0, 13.0, 12.0, 14.0)
        val lows = listOf(7.0, 8.0, 9.0, 6.0, 8.0, 9.0, 10.0, 11.0, 7.0, 9.0, 10.0)
        val fixture = highs.indices.map { i ->
            val close = (highs[i] + lows[i]) / 2.0
            V2Candle(highs[i], lows[i], close, 100.0, i.toLong() + 1L, close)
        }
        assertEquals(TradeIdeaStructure.BULLISH, classifyTradeIdeaStructure(fixture))
    }

    @Test fun staleClosedCandleFixtureCannotProduceAnIntradayPlan() {
        fun series(count: Int): List<V2Candle> = (0 until count).map { i ->
            val close = 100.0 + i * 0.01
            V2Candle(close + 1.0, close - 1.0, close, 100.0, i.toLong() + 1L, close)
        }
        val plan = ConfluenceScannerV4.evaluate(
            h4 = series(50), h1 = series(50), m15 = series(45), m5 = series(30),
            micro = V2Microstructure(2.0, 0.0, 0.0001, null, 20_000.0, 20_000.0),
            quoteVolume = 3_000_000.0, btcBias = "Neutral", horizon = "Intraday"
        )
        assertNull(plan)
    }
}
