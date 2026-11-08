package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class TradeIdeaAnalyticsTest {
    @Test fun positionSizeRespectsHalfPercentRisk() {
        val result = TradeSizing.calculate(10_000.0, 100.0, 99.0)!!
        assertEquals(50.0, result.riskAmount, 0.000001)
        assertEquals(0.5, result.actualRiskPercent, 0.000001)
        assertEquals(5_000.0, result.notional, 0.000001)
        assertEquals(1_666.6666667, result.marginAtMaxLeverage, 0.0001)
    }

    @Test fun positionSizeCapsNotionalAtThreeTimesEquity() {
        val result = TradeSizing.calculate(1_000.0, 100.0, 99.99)!!
        assertEquals(3_000.0, result.notional, 0.000001)
        assertEquals(3.0, result.notional / 1_000.0, 0.000001)
        assertTrue(result.actualRiskPercent <= 0.5)
    }

    @Test fun winLossEstimateRequiresThirtyCompletedSamples() {
        val insufficient = TradeIdeaBacktestStats(trades = 29, wins = 20, averageR = 0.5)
        assertFalse(insufficient.hasUsableEstimate)
        assertNull(insufficient.winRateLower95)
        assertEquals("Insufficient data", insufficient.confidenceLabel)
    }

    @Test fun winLossEstimateShowsRatesAndUncertaintyWithEnoughSamples() {
        val stats = TradeIdeaBacktestStats(trades = 50, wins = 32, averageR = 0.6)
        assertTrue(stats.hasUsableEstimate)
        assertEquals(64.0, stats.winRatePercent, 0.000001)
        assertEquals(36.0, stats.lossRatePercent, 0.000001)
        assertTrue(stats.winRateLower95!! < stats.winRatePercent)
        assertTrue(stats.winRateUpper95!! > stats.winRatePercent)
        assertEquals("Historical estimate · 50+ samples", stats.confidenceLabel)
    }

    @Test fun invalidSizingInputsReturnNull() {
        assertNull(TradeSizing.calculate(0.0, 100.0, 99.0))
        assertNull(TradeSizing.calculate(1000.0, 100.0, 100.0))
        assertNull(TradeSizing.calculate(1000.0, -1.0, 99.0))
    }
    @Test fun replayMetricsIncludeProfitFactorDrawdownAndSideBreakdown() {
        val stats = TradeIdeaBacktestStats.fromOutcomes(
            listOf(
                TradeIdeaReplayOutcome("LONG", "TREND", "BREAKOUT", 2.0),
                TradeIdeaReplayOutcome("LONG", "RANGE", "REVERSAL", -1.0),
                TradeIdeaReplayOutcome("SHORT", "TREND", "PULLBACK", 1.0),
                TradeIdeaReplayOutcome("SHORT", "RANGE", "REVERSAL", -2.0)
            )
        )

        assertEquals(1.0, stats.profitFactor!!, 0.000001)
        assertEquals(2.0, stats.maxDrawdownR, 0.000001)
        assertEquals(2, stats.longTrades)
        assertEquals(1, stats.longWins)
        assertEquals(0.5, stats.longAverageR!!, 0.000001)
        assertEquals(2, stats.shortTrades)
        assertEquals(1, stats.shortWins)
        assertEquals(-0.5, stats.shortAverageR!!, 0.000001)
        assertEquals(2, stats.trendTrades)
        assertEquals(2, stats.trendWins)
        assertEquals(1.5, stats.trendAverageR!!, 0.000001)
        assertEquals(2, stats.rangeTrades)
        assertEquals(0, stats.rangeWins)
        assertEquals(-1.5, stats.rangeAverageR!!, 0.000001)
        assertEquals(1, stats.breakoutTrades)
        assertEquals(2.0, stats.breakoutAverageR!!, 0.000001)
        assertEquals(1, stats.pullbackTrades)
        assertEquals(1.0, stats.pullbackAverageR!!, 0.000001)
        assertEquals(2, stats.reversalTrades)
        assertEquals(-1.5, stats.reversalAverageR!!, 0.000001)
    }

    @Test fun profitFactorIsUnavailableWhenThereAreNoLosingTrades() {
        val stats = TradeIdeaBacktestStats.fromOutcomes(listOf(TradeIdeaReplayOutcome("LONG", "TREND", "BREAKOUT", 1.0), TradeIdeaReplayOutcome("SHORT", "RANGE", "REVERSAL", 2.0)))
        assertNull(stats.profitFactor)
        assertEquals(0.0, stats.maxDrawdownR, 0.000001)
    }

}
