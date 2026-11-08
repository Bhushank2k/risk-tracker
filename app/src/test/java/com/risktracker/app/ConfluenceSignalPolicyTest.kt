package com.risktracker.app

import org.junit.Assert.assertFalse
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
    @Test fun readyRequiresEveryHardGateAndMinimumScore() {
        assertTrue(isConfluenceReady(true,true,true,true,true,true,true,true,true,80))
        for (failedGate in 0..8) {
            val gates = BooleanArray(9) { true }; gates[failedGate] = false
            assertFalse(isConfluenceReady(gates[0],gates[1],gates[2],gates[3],gates[4],gates[5],gates[6],gates[7],gates[8],100))
        }
        assertFalse(isConfluenceReady(true,true,true,true,true,true,true,true,true,79))
    }
}
