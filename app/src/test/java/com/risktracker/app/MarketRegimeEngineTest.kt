package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class MarketRegimeEngineTest {
    private fun candles(closes: List<Double>): List<MarketCandle> = closes.mapIndexed { index, close ->
        MarketCandle(index.toLong() + 1, close, close * 1.01, close * 0.99, close, 1.0, index.toLong() + 1, close)
    }

    @Test fun identifiesPersistentBullishTrend() {
        val closes = (0..239).map { 100.0 + it * 1.2 }
        val result = MarketRegimeEngine.classify("BTCUSDT", candles(closes))
        assertEquals(MarketRegime.BULLISH_TREND, result.regime)
        assertTrue(result.confidencePercent!! >= 75.0)
    }

    @Test fun identifiesPersistentBearishTrend() {
        val closes = (0..239).map { 400.0 - it * 1.2 }
        val result = MarketRegimeEngine.classify("ETHUSDT", candles(closes))
        assertEquals(MarketRegime.BEARISH_TREND, result.regime)
        assertTrue(result.confidencePercent!! >= 75.0)
    }

    @Test fun identifiesSidewaysConditionsWhenTrendSignalsConflict() {
        val closes = (0..239).map { 100.0 + 0.2 * sin(it / 3.0) }
        val result = MarketRegimeEngine.classify("BTCUSDT", candles(closes))
        assertEquals(MarketRegime.SIDEWAYS, result.regime)
    }

    @Test fun highVolatilityOverridesDirectionalClassification() {
        val closes = (0..239).map { 100.0 + it * 0.5 }
        val volatility = VolatilityForecast(
            "BTCUSDT", 120, 2.0, 95.0, 1.5, 4.0,
            VolatilityRegime.EXTREME, "Historical comparison"
        )
        val result = MarketRegimeEngine.classify("BTCUSDT", candles(closes), volatility)
        assertEquals(MarketRegime.HIGH_VOLATILITY, result.regime)
    }

    @Test fun requiresTwoHundredCompletedCandles() {
        val result = MarketRegimeEngine.classify("BTCUSDT", candles((1..199).map { it.toDouble() }))
        assertEquals(MarketRegime.INSUFFICIENT, result.regime)
        assertNull(result.confidencePercent)
    }
}
