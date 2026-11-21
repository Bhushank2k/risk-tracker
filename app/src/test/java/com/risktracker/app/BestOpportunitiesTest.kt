package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestOpportunitiesTest {
    private fun idea(
        side: String = "LONG",
        spread: Double? = 5.0,
        entryTriggered: Boolean = true
    ) = TradeIdea(
        symbol = "ETHUSDT", side = side, score = 85, volume = 10_000_000.0,
        change = 1.0, last = 100.0, entry = 100.0,
        stop = if (side == "LONG") 95.0 else 105.0,
        target1 = if (side == "LONG") 111.0 else 89.0,
        target2 = if (side == "LONG") 116.0 else 84.0,
        entryTriggered = entryTriggered, rsi = 55.0, volumeRatio = 1.06,
        bias4h = if (side == "LONG") "Bullish" else "Bearish",
        bias1h = if (side == "LONG") "Bullish" else "Bearish",
        setup = "Trend Pullback", reasons = emptyList(), warning = "",
        horizon = "Intraday · 15M setup / 5M execution",
        bias5m = if (side == "LONG") "Bullish" else "Bearish",
        spreadBps = spread, bookImbalance = if (side == "LONG") 0.20 else -0.20,
        fundingRate = 0.0, oiChangePct = null,
        backtest = TradeIdeaBacktestStats(),
        strategyChecks = listOf(
            "1H trend structure: higher highs + higher lows" to true,
            "1H swing structure aligned and 4H context not opposing" to true,
            "5M directional confirmation candle" to true,
            "Stop-loss beyond recent 5M swing low" to true,
            "Target 1 minimum 1:2.0 estimated net risk/reward" to true
        ),
        bidDepthUsd = 25_000.0, askDepthUsd = 25_000.0
    )

    private fun smc(
        id: String = "smc-long",
        side: String = "LONG",
        low: Double = 99.0,
        high: Double = 101.0,
        detectedAt: Long = 1_000_000L
    ) = UnifiedSMCDisplaySignal(
        id = id, symbol = "ETHUSDT", side = side, horizon = "Intraday", score = 82,
        entryLow = low, entryHigh = high, stop = 95.0, target = 111.0,
        rewardRisk = 2.2, sources = setOf("Core SMC", "Advanced SMC"),
        patterns = setOf("BOS", "Liquidity sweep"), status = "WAIT FOR CONFIRMATION",
        explanation = "Structure aligned", levelSource = "Core SMC levels", detectedAt = detectedAt
    )

    @Test fun alignedFreshIdeaAndSmcAreConfirmedAndDeduplicated() {
        val rows = buildBestOpportunities(listOf(idea()), listOf(smc()), 60_000L, 1_050_000L)
        assertEquals(1, rows.size)
        assertEquals(BestOpportunityStatus.CONFIRMED, rows.single().status)
        assertEquals(setOf("Daily Trade Ideas", "Core SMC", "Advanced SMC"), rows.single().sources)
        assertEquals(90, rows.single().priorityScore)
    }

    @Test fun missingMandatoryExecutionDataRemainsWaiting() {
        val rows = buildBestOpportunities(listOf(idea(spread = null)), listOf(smc()), 60_000L, 1_050_000L)
        assertEquals(BestOpportunityStatus.WAITING, rows.single().status)
        assertTrue(rows.single().eligibility?.hasUnavailableHardGate == true)
    }

    @Test fun partialDailySnapshotCannotConfirm() {
        val rows = buildBestOpportunities(
            listOf(idea()), listOf(smc()), 60_000L, 1_050_000L,
            tradeIdeaSnapshotComplete = false
        )
        assertEquals(BestOpportunityStatus.WAITING, rows.single().status)
    }

    @Test fun staleSmcSnapshotCannotConfirm() {
        val rows = buildBestOpportunities(listOf(idea()), listOf(smc(detectedAt = 1L)), 60_000L, 1_050_000L)
        assertEquals(BestOpportunityStatus.WAITING, rows.single().status)
        assertTrue(rows.single().explanation.contains("stale"))
    }

    @Test fun dailyOnlyCandidateWaitsForSmcConfirmation() {
        val rows = buildBestOpportunities(listOf(idea()), emptyList(), 60_000L, 1_050_000L)
        assertEquals(1, rows.size)
        assertEquals(BestOpportunityStatus.WAITING, rows.single().status)
        assertFalse(rows.single().sources.contains("Core SMC"))
    }

    @Test fun smcOnlyCandidateWaitsForExecutionRiskValidation() {
        val rows = buildBestOpportunities(emptyList(), listOf(smc()), 0L, 1_050_000L)
        assertEquals(1, rows.size)
        assertEquals(BestOpportunityStatus.WAITING, rows.single().status)
        assertTrue(rows.single().explanation.contains("live spread"))
    }

    @Test fun opposingDirectionsForSameSymbolAreFlaggedAsConflict() {
        val rows = buildBestOpportunities(
            listOf(idea()), listOf(smc(), smc(id = "smc-short", side = "SHORT", low = 109.0, high = 111.0)),
            60_000L, 1_050_000L
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == BestOpportunityStatus.CONFLICT })
        assertTrue(rows.all { it.explanation.contains("Opposing LONG/SHORT") })
    }

    @Test fun nonOverlappingEntryAreasAreNotMerged() {
        val rows = buildBestOpportunities(listOf(idea()), listOf(smc(low = 105.0, high = 106.0)), 60_000L, 1_050_000L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sources.size == 1 })
        assertTrue(rows.all { it.status == BestOpportunityStatus.WAITING })
    }

    @Test fun hardRiskFailureIsRejectedRatherThanConfirmed() {
        val rows = buildBestOpportunities(listOf(idea(spread = 15.0)), listOf(smc()), 60_000L, 1_050_000L)
        assertEquals(BestOpportunityStatus.REJECTED, rows.single().status)
    }
}
