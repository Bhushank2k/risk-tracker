package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class ScannerCoreTest {
    @Test fun fundingMustBePresentAndNotAdverse() {
        assertTrue(isFundingDataAcceptable("LONG", 0.0001))
        assertTrue(isFundingDataAcceptable("SHORT", -0.0001))
        assertFalse(isFundingDataAcceptable("LONG", null))
        assertFalse(isFundingDataAcceptable("SHORT", null))
        assertFalse(isFundingDataAcceptable("LONG", 0.0011))
        assertFalse(isFundingDataAcceptable("SHORT", -0.0011))
    }

    @Test fun liquidityRequiresFreshCompleteTwoSidedInputs() {
        assertTrue(isTradeIdeaLiquidityAcceptable("LONG", 5.0, 0.2, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", null, 0.2, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 5.0, null, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 5.0, 0.2, 14_999.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("SHORT", 5.0, 0.2, 20_000.0, 18_000.0))
    }

    @Test fun scoringIsBoundedAndTransparent() {
        val score = confluenceScore(true, true, true, 1.5, true, true, false, true)
        assertEquals(100, score)
        assertEquals(97, confluenceScore(true, true, true, 1.5, true, true, true, true))
        assertEquals(2, confluenceScore(false, false, false, Double.NaN, false, false, true, false))
    }

    @Test fun unavailableFundingAndUnsupportedHorizonsFailClosed() {
        assertFalse(isConfluenceReady(true, true, true, false, true, true, true, true, true, 100))
        assertTrue(isTradeIdeaHorizonSupported("Intraday"))
        assertFalse(isTradeIdeaHorizonSupported("Scalp"))
        assertFalse(isTradeIdeaHorizonSupported("Swing"))
    }


    @Test fun balancedExecutionVolumeThresholdsAreLessRestrictiveButNotZero() {
        assertEquals(1.20, confluenceExecutionVolumeThreshold("Breakout + retest"), 0.0001)
        assertEquals(0.90, confluenceExecutionVolumeThreshold("Trend pullback"), 0.0001)
    }

    @Test fun balancedPullbackVolumeAllowsNearBaselineParticipation() {
        assertTrue(confluencePullbackVolumeConfirmed(95.0, 92.0, 100.0))
        assertFalse(confluencePullbackVolumeConfirmed(120.0, 110.0, 100.0))
        assertFalse(confluencePullbackVolumeConfirmed(80.0, 80.0, 100.0))
    }

    @Test fun riskCodesBlockInvalidPlans() {
        assertFalse(isTradeIdeaRiskValid("LONG", 100.0, 98.0, 104.0, 106.0,
            setOf(TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE)))
        assertTrue(isTradeIdeaRiskValid("LONG", 100.0, 98.0, 104.0, 106.0, emptySet()))
        assertFalse(isTradeIdeaRiskValid("LONG", 100.0, 98.0, 103.9, 106.0, emptySet()))
    }
}
