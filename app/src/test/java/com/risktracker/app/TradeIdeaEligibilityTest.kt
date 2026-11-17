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
    fun missingDirectionalDepthWaitsForFreshDataInsteadOfAvoidingTheSetup() {
        val result = evaluateTradeIdeaEligibility(idea(bidDepth = null))
        assertEquals(TradeIdeaEligibilityState.WAIT, result.state)
        assertFalse(result.hardGateBlocked)
        assertTrue(result.hasUnavailableHardGate)
        assertTrue(result.checks.any {
            it.label.contains("Directional order-book") && it.hardGate && it.passed == null
        })
    }

    @Test
    fun missingFundingDataWaitsForFreshData() {
        val result = evaluateTradeIdeaEligibility(idea(funding = null))
        assertEquals(TradeIdeaEligibilityState.WAIT, result.state)
        assertFalse(result.hardGateBlocked)
        assertTrue(result.hasUnavailableHardGate)
    }

    @Test
    fun missingSpreadDataWaitsRatherThanApprovingOrAvoiding() {
        val result = evaluateTradeIdeaEligibility(idea().copy(spreadBps = null))
        assertEquals(TradeIdeaEligibilityState.WAIT, result.state)
        assertTrue(result.hasUnavailableHardGate)
        assertFalse(result.hardGateBlocked)
    }

    @Test
    fun supplementalStrategiesProvideAllCanonicalMandatoryChecks() {
        val names = listOf(
            "Trend Pullback", "Range Reversal", "Volatility Breakout",
            "Futures Squeeze", "Relative Strength / SMT", "Session Liquidity Raid"
        )
        names.forEach { name ->
            val checks = supplementalStrategyChecks(name, "LONG", 100.0, 95.0, 111.0, 115.0)
            assertTrue("$name must provide a stop-loss check", checks.any { it.first.startsWith("Stop-loss") && it.second == true })
            assertTrue("$name must provide a target check", checks.any { it.first.startsWith("Target 1") && it.second == true })
            assertTrue("$name must provide a structure check", checks.any {
                it.first.contains("trend structure", ignoreCase = true) && it.second == true
            })
            assertTrue("$name must remain unconfirmed until a trigger exists", checks.any {
                it.first.contains("confirmation candle", ignoreCase = true) && it.second == false
            })
        }
    }

    @Test
    fun supplementalCandidateWithValidStructureWaitsForEntryConfirmation() {
        val checks = supplementalStrategyChecks("Trend Pullback", "LONG", 100.0, 95.0, 111.0, 115.0)
        val candidate = idea(entryTriggered = false, confirmation = false).copy(
            strategyChecks = checks,
            bias5m = "Awaiting confirmation"
        )
        val result = evaluateTradeIdeaEligibility(candidate)
        assertEquals(TradeIdeaEligibilityState.WAIT, result.state)
        assertFalse(result.hardGateBlocked)
    }

    @Test
    fun supplementalStrategyChecksRejectInvalidStopAndTargetGeometry() {
        val checks = supplementalStrategyChecks("Range Reversal", "LONG", 100.0, 101.0, 99.0, 98.0)
        assertTrue(checks.any { it.first.startsWith("Stop-loss") && it.second == false })
        assertTrue(checks.any { it.first.startsWith("Target 1") && it.second == false })
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
    fun scalpUsesFifteenMinuteTrendAndOneHourContext() {
        val result = evaluateTradeIdeaEligibility(
            idea().copy(
                horizon = "Scalp · 5M setup / 1M execution",
                bias1h = "Bearish",
                bias4h = "Bullish"
            )
        )
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.checks.any { it.label.startsWith("15M trend aligned") && it.passed == false })
        assertTrue(result.checks.any { it.label.startsWith("1H context aligned") && it.passed == true })
    }
    @Test
    fun expiredSnapshotBlocksEntry() {
        val result = evaluateTradeIdeaEligibility(idea(), snapshotAgeMs = 61 * 60_000L)
        assertEquals(TradeIdeaEligibilityState.AVOID, result.state)
        assertTrue(result.hardGateBlocked)
    }
}
