package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceSignalPolicyTest {
    @Test fun volumeFloorExpandsCandidatesAndRejectsInvalidValues() {
        assertFalse(isTradeIdeaVolumeEligible(1_999_999.99))
        assertTrue(isTradeIdeaVolumeEligible(2_000_000.0))
        assertTrue(isTradeIdeaVolumeEligible(5_000_000.0))
        assertFalse(isTradeIdeaVolumeEligible(Double.NaN))
        assertFalse(isTradeIdeaVolumeEligible(Double.POSITIVE_INFINITY))
        assertFalse(isTradeIdeaVolumeEligible(-1.0))
    }
    @Test fun liquidityRequiresSpreadAndBothSidesOfDepth() {
        assertTrue(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 10.1, 0.05, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 14_999.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 20_000.0, null))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", Double.NaN, 0.05, 20_000.0, 18_000.0))
    }
    @Test fun directionalImbalanceIsSideAware() {
        assertTrue(isTradeIdeaLiquidityAcceptable("LONG", 3.0, -0.15, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 3.0, -0.16, 20_000.0, 20_000.0))
        assertTrue(isTradeIdeaLiquidityAcceptable("SHORT", 3.0, 0.15, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("SHORT", 3.0, 0.16, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("OTHER", 3.0, 0.0, 20_000.0, 20_000.0))
    }
    @Test fun readyRequiresHardGatesAndBalancedScoreButNotEverySoftSignal() {
        assertTrue(isConfluenceReady(true,true,true,true,true,true,true,true,true,75))
        // Volume and momentum are scoring inputs, not standalone hard gates.
        assertTrue(isConfluenceReady(true,true,true,true,true,true,false,false,true,75))
        assertFalse(isConfluenceReady(false,true,true,true,true,true,true,true,true,100))
        assertFalse(isConfluenceReady(true,false,true,true,true,true,true,true,true,100))
        assertFalse(isConfluenceReady(true,true,false,true,true,true,true,true,true,100))
        assertFalse(isConfluenceReady(true,true,true,false,true,true,true,true,true,100))
        assertFalse(isConfluenceReady(true,true,true,true,false,true,true,true,true,100))
        assertFalse(isConfluenceReady(true,true,true,true,true,false,true,true,true,100))
        assertTrue(isConfluenceReady(true,true,true,true,true,true,true,false,true,100))
        assertFalse(isConfluenceReady(true,true,true,true,true,true,true,true,false,100))
        assertFalse(isConfluenceReady(true,true,true,true,true,true,true,true,true,74))
    }

    @Test fun volumeAndMomentumAreSoftSignalsButSafetyFailuresRemainHard() {
        assertFalse(TradeIdeaRiskCode.LOW_RELATIVE_VOLUME.blocksEntry)
        assertFalse(TradeIdeaRiskCode.MOMENTUM_NOT_CONFIRMED.blocksEntry)
        assertFalse(TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE.blocksEntry)
        assertTrue(TradeIdeaRiskCode.LOW_LIQUIDITY.blocksEntry)
        assertTrue(TradeIdeaRiskCode.INVALID_STOP.blocksEntry)
        assertTrue(TradeIdeaRiskCode.INSUFFICIENT_REWARD_RISK.blocksEntry)
        assertTrue(TradeIdeaRiskCode.ADVERSE_FUNDING.blocksEntry)
        assertTrue(TradeIdeaRiskCode.LOW_CONFLUENCE_SCORE.blocksEntry)
    }

    @Test fun scannerSizesGrossTargetSoEstimatedNetRewardRiskIsAtLeastTwoR() {
        val grossTarget = tradeIdeaGrossRForMinimumNetReturn(
            minimumNetR = 2.0, entry = 100.0, riskDistance = 1.0,
            heldBars = 8, setupBarMinutes = 15
        )
        val net = tradeIdeaNetReturnR(
            grossR = grossTarget, entry = 100.0, riskDistance = 1.0,
            heldBars = 8, setupBarMinutes = 15
        )
        assertTrue(grossTarget > 2.0)
        assertEquals(2.0, net, 1e-9)

        val tightStopGrossTarget = tradeIdeaGrossRForMinimumNetReturn(
            minimumNetR = 2.0, entry = 100.0, riskDistance = 0.1,
            heldBars = 8, setupBarMinutes = 15
        )
        val tightStopNet = tradeIdeaNetReturnR(
            grossR = tightStopGrossTarget, entry = 100.0, riskDistance = 0.1,
            heldBars = 8, setupBarMinutes = 15
        )
        assertTrue(tightStopGrossTarget > grossTarget)
        assertEquals(2.0, tightStopNet, 1e-9)
    }

    @Test fun invalidMinimumNetReturnProducesNaN() {
        assertTrue(tradeIdeaGrossRForMinimumNetReturn(
            Double.NaN, 100.0, 1.0, 8, 15
        ).isNaN())
    }

    @Test fun volumeConfluenceAwardsPartialCreditBelowPreferredThreshold() {
        val base = listOf(true,true,true,true,true,true,false,false)
        fun score(volume: Double) = confluenceScore(
            regimeConfirmed=base[0], structureConfirmed=base[1], entryConfirmed=base[2],
            relativeVolume=volume, momentumConfirmed=base[3], liquidityConfirmed=base[4],
            btcAgainst=base[5], stopGeometryConfirmed=base[6]
        )
        assertTrue(score(1.0) > score(0.99))
        assertTrue(score(1.2) > score(1.0))
        assertTrue(score(1.5) > score(1.2))
    }
}
