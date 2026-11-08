package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaRiskCodeTest {

    @Test
    fun missingOrInvalidFundingDataIsNotAccepted() {
        assertFalse(isFundingDataAcceptable("LONG", null))
        assertFalse(isFundingDataAcceptable("SHORT", Double.NaN))
        assertFalse(isFundingDataAcceptable("LONG", Double.POSITIVE_INFINITY))
        assertFalse(isFundingDataAcceptable("OTHER", 0.0))
    }

    @Test
    fun fundingThresholdsAreDirectional() {
        assertTrue(isFundingDataAcceptable("LONG", 0.001))
        assertFalse(isFundingDataAcceptable("LONG", 0.0011))
        assertTrue(isFundingDataAcceptable("SHORT", -0.001))
        assertFalse(isFundingDataAcceptable("SHORT", -0.0011))
    }

    @Test
    fun validLongPlanPassesWithoutBlockingCodes() {
        assertTrue(isTradeIdeaRiskValid("LONG", 100.0, 95.0, 110.0, 115.0, emptySet()))
    }

    @Test
    fun validShortPlanPassesWithoutBlockingCodes() {
        assertTrue(isTradeIdeaRiskValid("SHORT", 100.0, 105.0, 90.0, 85.0, emptySet()))
    }

    @Test
    fun invalidStopGeometryBlocksLongPlan() {
        assertFalse(isTradeIdeaRiskValid("LONG", 100.0, 101.0, 110.0, 115.0, emptySet()))
    }

    @Test
    fun staleMarketDataCodeBlocksPlan() {
        assertFalse(
            isTradeIdeaRiskValid(
                "LONG", 100.0, 95.0, 110.0, 115.0,
                setOf(TradeIdeaRiskCode.STALE_MARKET_DATA)
            )
        )
    }

    @Test
    fun insufficientRewardRiskBlocksPlan() {
        assertFalse(isTradeIdeaRiskValid("LONG", 100.0, 95.0, 109.0, 115.0, emptySet()))
    }

    @Test
    fun advisoryCodesDoNotBlockOtherwiseValidPlan() {
        assertTrue(
            isTradeIdeaRiskValid(
                "LONG", 100.0, 95.0, 110.0, 115.0,
                setOf(
                    TradeIdeaRiskCode.WAITING_FOR_ENTRY_CONFIRMATION,
                    TradeIdeaRiskCode.BELOW_READY_SCORE_THRESHOLD,
                    TradeIdeaRiskCode.COSTS_NOT_INCLUDED
                )
            )
        )
    }
}
