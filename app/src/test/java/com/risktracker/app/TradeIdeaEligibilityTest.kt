package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaEligibilityTest {
    private fun idea(
        bidDepth: Double? = 25_000.0,
        askDepth: Double? = 25_000.0,
        funding: Double? = 0.0,
        bias5m: String = "Bullish",
        entryTriggered: Boolean = true,
        confirmation: Boolean? = true,
        rsi: Double = 55.0,
        volumeRatio: Double = 1.06
    ) = TradeIdea(
        symbol = "ETHUSDT",
        side = "LONG",
        score = 85,
        volume = 10_000_000.0,
        change = 1.0,
        last = 100.0,
        entry = 100.0,
        stop = 95.0,
        target1 = 111.0,
        target2 = 116.0,
        entryTriggered = entryTriggered,
        rsi = rsi,
        volumeRatio = volumeRatio,
        bias4h = "Bullish",
        bias1h = "Bullish",
        setup = "Trend Pullback",
        reasons = emptyList(),
        warning = "",
        horizon = "Intraday · 15M setup / 5M execution",
        bias5m = bias5m,
        spreadBps = 5.0,
        bookImbalance = 0.20,
        fundingRate = funding,
        oiChangePct = null,
        backtest = TradeIdeaBacktestStats(),
        strategyChecks = listOf(
            "1H trend structure: higher highs + higher lows" to true,
            "1H swing structure aligned and 4H context not opposing" to true,
            "5M directional confirmation candle" to confirmation,
            "Stop-loss beyond recent 5M swing low" to true,
            "Target 1 minimum 1:2.0 estimated net risk/reward" to true
        ),
        bidDepthUsd = bidDepth,
        askDepthUsd = askDepth
    )

    @Test
    fun fullyQualifiedSetupIsReady() {
        val result = evaluateTradeIdeaEligibility(idea())
        assertEquals(TradeIdeaEligibilityState.READY, result.state)
        assertFalse(result.hardGateBlocked)
        assertTrue(result.estimatedNetR >= 2.0)
    }

    @Test
    fun missingDirectionalDepthIsBlockedNotReview() {
        val result = evaluateTradeIdeaEligibility(idea(bidDepth = null))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
        assertTrue(result.checks.any {
            it.label.contains("Directional order-book") && it.hardGate && it.passed == null
        })
    }

    @Test
    fun missingFundingDataBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea(funding = null))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }

    @Test
    fun pendingExecutionConfirmationMeansWait() {
        val result = evaluateTradeIdeaEligibility(idea(entryTriggered = false, confirmation = false))
        assertEquals(TradeIdeaEligibilityState.WAIT, result.state)
        assertFalse(result.hardGateBlocked)
    }

    @Test
    fun opposingExecutionTrendAvoidsEntry() {
        val result = evaluateTradeIdeaEligibility(idea(bias5m = "Bearish"))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
    }

    @Test
    fun supportingRsiAndVolumeDoNotOverrideHardGateApproval() {
        val result = evaluateTradeIdeaEligibility(idea(rsi = 75.0, volumeRatio = 1.06))
        assertEquals(TradeIdeaEligibilityState.READY, result.state)
        assertFalse(result.hardGateBlocked)
        assertTrue(result.checks.any { it.label.startsWith("RSI") && it.passed == false && !it.hardGate })
    }

    @Test
    fun spreadAboveTenBasisPointsBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea().copy(spreadBps = 10.1))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }

    @Test
    fun unacceptableDirectionalImbalanceBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea().copy(bookImbalance = -0.20))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }

    @Test
    fun targetBelowTwoNetRBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea().copy(target1 = 110.0))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }

    @Test
    fun relativeVolumeBelowStrategyThresholdBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea().copy(volumeRatio = 0.89))
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }

    @Test
    fun expiredSnapshotBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea(), snapshotAgeMs = 61 * 60_000L)
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }
}
