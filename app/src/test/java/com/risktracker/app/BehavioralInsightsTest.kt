package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class BehavioralInsightsTest {
    @Test fun groupsStrategiesAndCalculatesResults() {
        val rows = listOf(
            TradeEntry("1", "2026-01-01", "BTC", "Long", 100.0, 110.0, 1.0, strategy = "Breakout", riskUsd = 1.0),
            TradeEntry("2", "2026-01-02", "BTC", "Long", 100.0, 105.0, 1.0, strategy = "Breakout", riskUsd = 1.0),
            TradeEntry("3", "2026-01-03", "BTC", "Short", 100.0, 110.0, 1.0, strategy = "Breakout", mistake = "FOMO", riskUsd = 1.0)
        )
        val result = analyzeTradeBehavior(rows)
        val breakout = result.strategies.first { it.strategy == "Breakout" }
        assertEquals(3, breakout.sampleSize)
        assertEquals(5.0, breakout.netPnl, 1e-8)
        assertEquals(66.666666, breakout.winRate, 1e-4)
        assertEquals(1, result.mistakeCounts["FOMO"])
        assertTrue(result.warnings.isEmpty())
    }
    @Test fun warnsWhenHistoryIsSmallOrUntagged() {
        val result = analyzeTradeBehavior(listOf(TradeEntry("1", "2026-01-01", "BTC", "Long", 100.0, 99.0, 1.0)))
        assertTrue(result.warnings.any { it.contains("Only") })
        assertTrue(result.warnings.any { it.contains("untagged") })
        assertEquals(1, result.taggedCount.let { 1 - it })
    }

    @Test fun weeklyPerformanceGroupsByMondayAndUsesOnlyValidClosedTrades() {
        val rows = listOf(
            TradeEntry("1", "2026-01-05", "BTC", "Long", 100.0, 102.0, 1.0),
            TradeEntry("2", "2026-01-06", "BTC", "Long", 100.0, 99.0, 1.0),
            TradeEntry("3", "2026-01-12", "BTC", "Long", 100.0, 104.0, 1.0),
            TradeEntry("4", "bad-date", "BTC", "Long", 100.0, 200.0, 1.0)
        )
        val weeks = summarizeTradeTimeline(rows)
        assertEquals(2, weeks.size)
        assertEquals(2, weeks[0].tradeCount)
        assertEquals(1.0, weeks[0].netPnl, 1e-8)
        assertEquals(1, weeks[1].tradeCount)
        assertEquals(4.0, weeks[1].netPnl, 1e-8)
    }

    @Test fun currentStreakCountsLatestConsecutiveWinsOrLossesChronologically() {
        val rows = listOf(
            TradeEntry("3", "2026-01-03", "BTC", "Long", 100.0, 99.0, 1.0),
            TradeEntry("1", "2026-01-01", "BTC", "Long", 100.0, 102.0, 1.0),
            TradeEntry("2", "2026-01-02", "BTC", "Long", 100.0, 103.0, 1.0),
            TradeEntry("4", "2026-01-04", "BTC", "Long", 100.0, 99.0, 1.0)
        )
        val streak = currentTradeStreak(rows)
        assertEquals("Loss", streak.kind)
        assertEquals(2, streak.count)
    }
}
