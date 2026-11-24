package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class VolatilityForecastEngineTest {
    private fun candles(closes: List<Double>): List<MarketCandle> = closes.mapIndexed { index, close ->
        MarketCandle(index.toLong(), close, close * 1.01, close * 0.99, close, 1.0, index.toLong() + 1, close)
    }

    @Test fun returnsInsufficientWhenHistoryIsTooShort() {
        val result = VolatilityForecastEngine.forecast("BTCUSDT", candles((1..10).map { it.toDouble() }))
        assertEquals(VolatilityRegime.INSUFFICIENT, result.regime)
        assertNull(result.recentVolatilityPercent)
    }

    @Test fun calculatesFiniteVolatilityAndMoveStatistics() {
        val closes = (0..120).map { index ->
            100.0 * (1.0 + 0.003 * kotlin.math.sin(index / 2.0) + index * 0.0002)
        }
        val result = VolatilityForecastEngine.forecast("BTCUSDT", candles(closes))
        assertTrue(result.sampleCount >= 90)
        assertTrue(result.recentVolatilityPercent!!.isFinite())
        assertTrue(result.volatilityPercentile!! in 0.0..100.0)
        assertTrue(result.meanAbsoluteDailyMovePercent!! >= 0.0)
        assertTrue(result.p90AbsoluteDailyMovePercent!! >= result.meanAbsoluteDailyMovePercent!!)
    }

    @Test fun ignoresInvalidClosesAndSortsCandlesByCloseTime() {
        val valid = candles((1..40).map { it.toDouble() })
        val mixed = valid.reversed() + listOf(
            MarketCandle(1000, 1.0, 2.0, 0.0, Double.NaN, 1.0, 1001, 1.0)
        )
        val result = VolatilityForecastEngine.forecast("ETHUSDT", mixed)
        assertTrue(result.sampleCount >= 20)
        assertTrue(result.recentVolatilityPercent!!.isFinite())
    }
}
