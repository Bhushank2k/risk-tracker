package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class PersonalTradingCoachEngineTest {
    private fun trade(id: String, pnl: Double, strategy: String = "Breakout",
                      mistake: String = "", risk: Double = 10.0) =
        TradeEntry(id, "2026-01-%02d".format(id.toInt()), "BTCUSDT", "Long",
            100.0, 100.0 + pnl, 1.0, strategy = strategy, mistake = mistake, riskUsd = risk)

    @Test fun flagsNegativeStrategyExpectancyOnlyAfterMinimumSample() {
        val rows = (1..5).map { trade(it.toString(), -1.0) }
        val report = PersonalTradingCoachEngine.analyze(rows)
        assertTrue(report.insights.any { it.title == "Review strategy: Breakout" })
    }

    @Test fun reportsMostFrequentMistakeTag() {
        val rows = listOf(
            trade("1", 1.0, mistake = "FOMO"),
            trade("2", 1.0, mistake = "FOMO"),
            trade("3", 1.0, mistake = "FOMO")
        )
        val report = PersonalTradingCoachEngine.analyze(rows)
        assertTrue(report.insights.any { it.title.contains("FOMO") && it.sampleCount == 3 })
    }

    @Test fun doesNotClaimRevengeTradingFromDateOnlyRecords() {
        val report = PersonalTradingCoachEngine.analyze((1..4).map { trade(it.toString(), -1.0) })
        assertTrue(report.limitations.any { it.contains("execution timestamps") })
        assertFalse(report.insights.any { it.title.contains("revenge", true) })
    }

    @Test fun flagsLargeVariationInPlannedRisk() {
        val risks = listOf(1.0, 1.0, 1.0, 1.0, 20.0)
        val rows = risks.mapIndexed { index, risk -> trade((index + 1).toString(), 1.0, risk = risk) }
        assertTrue(PersonalTradingCoachEngine.analyze(rows).insights.any { it.title.contains("risk varies") })
    }

    @Test fun emptyJournalProvidesActionableSetupGuidance() {
        val report = PersonalTradingCoachEngine.analyze(emptyList())
        assertEquals(0, report.tradeCount)
        assertTrue(report.insights.any { it.title.contains("complete trade journal") })
    }
}
