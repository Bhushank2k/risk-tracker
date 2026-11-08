package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGateDiagnosticsTest {
    private fun idea(codes: Set<TradeIdeaRiskCode>) = TradeIdea(
        symbol = "BTCUSDT", side = "LONG", score = 70, volume = 1.0, change = 0.0,
        last = 100.0, entry = 100.0, stop = 95.0, target1 = 110.0, target2 = 115.0,
        entryTriggered = false, rsi = 50.0, volumeRatio = 1.0, bias4h = "Bullish",
        bias1h = "Bullish", setup = "Trend pullback", reasons = emptyList(),
        warning = "test", horizon = "Intraday", bias5m = "Mixed", spreadBps = null,
        bookImbalance = null, fundingRate = null, oiChangePct = null,
        backtest = TradeIdeaBacktestStats(), riskCodes = codes
    )

    @Test
    fun countsEachGateHitAcrossPlansAndCountsBlockedPlansOnce() {
        val diagnostics = ScanGateDiagnostics().record(
            listOf(
                idea(setOf(TradeIdeaRiskCode.LOW_LIQUIDITY, TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE)),
                idea(setOf(TradeIdeaRiskCode.LOW_LIQUIDITY, TradeIdeaRiskCode.COSTS_NOT_INCLUDED)),
                idea(emptySet())
            )
        )
        assertEquals(3, diagnostics.evaluatedPlans)
        assertEquals(2, diagnostics.blockedPlans)
        assertEquals(2, diagnostics.gateHits[TradeIdeaRiskCode.LOW_LIQUIDITY])
        assertEquals(1, diagnostics.gateHits[TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE])
        assertEquals(2, diagnostics.topGateSummary(2).split(" · ").size)
    }

    @Test
    fun advisoryOnlyCodesDoNotCountAsBlocked() {
        val diagnostics = ScanGateDiagnostics().record(
            listOf(idea(setOf(TradeIdeaRiskCode.COSTS_NOT_INCLUDED, TradeIdeaRiskCode.WAITING_FOR_ENTRY_CONFIRMATION)))
        )
        assertEquals(1, diagnostics.evaluatedPlans)
        assertEquals(0, diagnostics.blockedPlans)
        assertTrue(diagnostics.topGateSummary().contains("costs not included"))
    }

    @Test
    fun emptyInputDoesNotChangeCounts() {
        val initial = ScanGateDiagnostics().record(listOf(idea(setOf(TradeIdeaRiskCode.INVALID_STOP))))
        assertEquals(initial, initial.record(emptyList()))
    }
}
