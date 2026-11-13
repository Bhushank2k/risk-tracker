package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceSmcConfluenceTest {
    private fun candle(open: Double, high: Double, low: Double, close: Double, time: Long) =
        V2Candle(open, high, low, close, 100.0, time)

    @Test fun smcEvidenceIsSoftAndCappedAtTenPoints() {
        val evidence = TradeIdeaSmcEvidence(true, true, true, true, true)
        assertEquals(10, evidence.points)
        assertEquals(0, TradeIdeaSmcEvidence().points)
    }

    @Test fun bullishStructureBreakRequiresCloseAboveConfirmedSwing() {
        val bars = listOf(
            candle(9.0,10.0,8.0,9.0,1), candle(9.0,11.0,8.5,10.0,2),
            candle(10.0,10.5,8.0,9.0,3), candle(9.0,10.0,7.0,8.0,4),
            candle(8.0,9.0,7.5,8.5,5), candle(8.5,9.5,8.0,9.0,6),
            candle(9.0,10.0,8.5,9.5,7), candle(9.5,10.5,9.0,10.0,8),
            candle(10.0,12.0,9.5,11.5,9), candle(11.5,12.0,10.5,11.0,10)
        )
        assertTrue(hasTradeIdeaSmcStructureBreak(bars, "LONG"))
        assertFalse(hasTradeIdeaSmcStructureBreak(bars, "SHORT"))
    }

    @Test fun invalidSideAndInsufficientDataFailClosed() {
        assertEquals(0, evaluateTradeIdeaSmc(emptyList(), "LONG", 1.0).points)
        assertEquals(0, evaluateTradeIdeaSmc(listOf(candle(1.0,2.0,0.5,1.5,1)), "BUY", 1.0).points)
        assertFalse(hasTradeIdeaSmcFvgRetest(emptyList(), "LONG"))
        assertFalse(hasTradeIdeaSmcOrderBlockRetest(emptyList(), "LONG", 1.0))
    }

    @Test fun bullishFvgRetestRecognizesReturnIntoGap() {
        val bars = listOf(
            candle(10.0,11.0,9.0,10.5,1),
            candle(10.5,12.0,10.2,11.8,2),
            candle(11.8,13.0,11.5,12.8,3),
            candle(12.0,12.5,10.8,11.7,4)
        )
        assertTrue(hasTradeIdeaSmcFvgRetest(bars, "LONG"))
    }

    @Test fun premiumDiscountAlignmentDependsOnTradeSide() {
        val bars = listOf(
            candle(10.0,12.0,9.0,10.0,1),
            candle(10.0,11.0,8.0,9.0,2),
            candle(9.0,10.0,7.0,8.0,3),
            candle(8.0,9.0,7.5,8.5,4),
            candle(8.5,10.0,8.0,9.0,5),
            candle(9.0,10.0,8.5,9.5,6),
            candle(9.5,11.0,9.0,10.0,7),
            candle(10.0,11.0,9.5,10.5,8),
            candle(10.5,11.0,10.0,10.5,9),
            candle(10.5,11.0,10.0,10.5,10),
            candle(10.5,11.0,10.0,10.5,11),
            candle(10.5,11.0,10.0,10.5,12)
        )
        assertTrue(evaluateTradeIdeaSmc(bars, "LONG", 1.0).premiumDiscountAligned)
        assertFalse(evaluateTradeIdeaSmc(bars, "SHORT", 1.0).premiumDiscountAligned)
    }
}
