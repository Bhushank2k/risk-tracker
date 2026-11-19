package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TradeIdeaBacktestComparisonTest {
    @Test
    fun expectancyLeaderIsWithheldUntilBothModelsHaveThirtyTrades() {
        val legacy = TradeIdeaBacktestStats(trades = 29, averageR = 0.2)
        val v2 = TradeIdeaBacktestStats(trades = 40, averageR = 0.5)
        val result = TradeIdeaBacktestComparison(
            legacy = legacy,
            v2 = v2,
            averageRDelta = null,
            enoughSamplesForComparison = false
        )

        assertNull(result.expectancyLeader)
    }

    @Test
    fun expectancyLeaderUsesNetV2ExpectancyDeltaWhenBothSamplesAreLargeEnough() {
        val legacy = TradeIdeaBacktestStats(trades = 35, averageR = 0.1)
        val v2 = TradeIdeaBacktestStats(trades = 36, averageR = 0.3)
        val result = TradeIdeaBacktestComparison(
            legacy = legacy,
            v2 = v2,
            averageRDelta = v2.averageR - legacy.averageR,
            enoughSamplesForComparison = true
        )

        assertEquals(0.2, result.averageRDelta!!, 1e-9)
        assertEquals("V2", result.expectancyLeader)
    }

    @Test
    fun equalExpectancyReportsTie() {
        val stats = TradeIdeaBacktestStats(trades = 30, averageR = 0.25)
        val result = TradeIdeaBacktestComparison(stats, stats, 0.0, true)

        assertEquals("Tie", result.expectancyLeader)
    }
}
