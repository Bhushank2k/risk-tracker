package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class CoinExplosionRadarEngineTest {
    private fun candles(count: Int = 100, breakout: Boolean = false): List<MarketCandle> {
        return (0 until count).map { i ->
            val base = 100.0 + (i % 5) * 0.08
            val close = if (breakout && i == count - 1) 103.0 else base
            val high = maxOf(base, close) + 0.08
            val low = minOf(base, close) - 0.08
            val quote = if (i == count - 1) 2_000_000.0 else 1_000_000.0
            MarketCandle(i * 3_600_000L, base, high, low, close, quote / close, (i + 1) * 3_600_000L - 1,
                quote, (quote / close) * 0.58, quote * 0.58)
        }
    }
    private val context = ExplosionMarketContext(20_000_000.0, 5.0, 0.0001, 0.0, 3.0, true)

    @Test fun rejectsInsufficientHistory() {
        assertNull(CoinExplosionRadarEngine.analyze("TESTUSDT", candles(20), context))
    }

    @Test fun rejectsIlliquidOrWideSpreadMarkets() {
        val lowVolume = CoinExplosionRadarEngine.analyze("TESTUSDT", candles(), context.copy(quoteVolume24h = 100_000.0))!!
        assertEquals(ExplosionStage.REJECTED, lowVolume.stage)
        val wide = CoinExplosionRadarEngine.analyze("TESTUSDT", candles(), context.copy(spreadBps = 80.0))!!
        assertEquals(ExplosionStage.REJECTED, wide.stage)
    }


    @Test fun rejectsUnknownSpreadAndCrowdedFunding() {
        val noSpread = CoinExplosionRadarEngine.analyze("TESTUSDT", candles(), context.copy(spreadBps = null))!!
        assertEquals(ExplosionStage.REJECTED, noSpread.stage)
        val crowded = CoinExplosionRadarEngine.analyze("TESTUSDT", candles(), context.copy(fundingRate = 0.001))!!
        assertEquals(ExplosionStage.REJECTED, crowded.stage)
    }

    @Test fun computesBoundedScoreAndRiskLevels() {
        val signal = CoinExplosionRadarEngine.analyze("TESTUSDT", candles(), context)!!
        assertTrue(signal.score in 0..100)
        assertTrue(signal.invalidationPrice != null && signal.invalidationPrice!! > 0.0)
        assertTrue(signal.breakdown.total == signal.score)
        assertEquals(ExplosionDirection.LONG, signal.direction)
    }

    @Test fun replayReportsCostsAndDoesNotInventTradesOnShortHistory() {
        val short = CoinExplosionRadarEngine.replay(candles(50))
        assertEquals(0, short.trades)
        val long = CoinExplosionRadarEngine.replay(candles(160))
        assertEquals(8.0, long.feeBpsPerSide, 0.0)
        assertEquals(5.0, long.slippageBpsPerSide, 0.0)
    }
}
