package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceSignalPolicyTest {

    @Test
    fun volumeFloorExpandsCandidateUniverseButRejectsInvalidValues() {
        assertFalse(isTradeIdeaVolumeEligible(1_999_999.99))
        assertTrue(isTradeIdeaVolumeEligible(2_000_000.0))
        assertTrue(isTradeIdeaVolumeEligible(5_000_000.0))
        assertFalse(isTradeIdeaVolumeEligible(Double.NaN))
        assertFalse(isTradeIdeaVolumeEligible(Double.POSITIVE_INFINITY))
        assertFalse(isTradeIdeaVolumeEligible(-1.0))
    }

    @Test
    fun liquidityRequiresFreshUsableSpreadAndBothSidesOfDepth() {
        assertTrue(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 10.1, 0.05, 20_000.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 14_999.0, 18_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 4.0, 0.05, 20_000.0, null))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", Double.NaN, 0.05, 20_000.0, 18_000.0))
    }

    @Test
    fun directionalDepthImbalanceIsAppliedByTradeSide() {
        assertTrue(isTradeIdeaLiquidityAcceptable("LONG", 3.0, -0.15, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("LONG", 3.0, -0.16, 20_000.0, 20_000.0))
        assertTrue(isTradeIdeaLiquidityAcceptable("SHORT", 3.0, 0.15, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("SHORT", 3.0, 0.16, 20_000.0, 20_000.0))
        assertFalse(isTradeIdeaLiquidityAcceptable("OTHER", 3.0, 0.0, 20_000.0, 20_000.0))
    }

    @Test
    fun rangeSetupsEarnRangeRegimePointsNotTrendAlignmentPoints() {
        assertEquals(25, confluenceRegimePoints(isRangeSetup = true, rangeMode = true, trendAligned = false))
        assertEquals(0, confluenceRegimePoints(isRangeSetup = true, rangeMode = false, trendAligned = true))
        assertEquals(25, confluenceRegimePoints(isRangeSetup = false, rangeMode = false, trendAligned = true))
        assertEquals(0, confluenceRegimePoints(isRangeSetup = false, rangeMode = true, trendAligned = false))
    }

    @Test
    fun readyRequiresEveryHardGateAndMinimumScore() {
        val valid = arrayOf(true, true, true, true, true, true, true, true, true, 80)
        assertTrue(isConfluenceReady(
            valid[0] as Boolean, valid[1] as Boolean, valid[2] as Boolean, valid[3] as Boolean,
            valid[4] as Boolean, valid[5] as Boolean, valid[6] as Boolean, valid[7] as Boolean,
            valid[8] as Boolean, valid[9] as Int
        ))
        for (failedGate in 0..8) {
            val gates = BooleanArray(9) { true }
            gates[failedGate] = false
            assertFalse(isConfluenceReady(
                gates[0], gates[1], gates[2], gates[3], gates[4],
                gates[5], gates[6], gates[7], gates[8], 100
            ))
        }
        assertFalse(isConfluenceReady(true, true, true, true, true, true, true, true, true, 79))
    }
}
