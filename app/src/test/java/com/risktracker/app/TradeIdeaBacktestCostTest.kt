package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaBacktestCostTest {
    @Test
    fun costsReduceGrossReturnAndCanTurnSmallWinIntoNetLoss() {
        val gross = 0.10
        val net = tradeIdeaNetReturnR(
            grossR = gross,
            entry = 100.0,
            riskDistance = 0.5,
            heldBars = 12,
            setupBarMinutes = 15
        )

        assertTrue(net < gross)
        assertTrue(net < 0.0)
    }

    @Test
    fun longerHoldingPeriodAddsFundingCost() {
        val shortHold = tradeIdeaNetReturnR(1.0, 100.0, 2.0, 4, 15)
        val longHold = tradeIdeaNetReturnR(1.0, 100.0, 2.0, 64, 15)

        assertTrue(longHold < shortHold)
    }

    @Test
    fun invalidCostInputsAreRejected() {
        val result = tradeIdeaNetReturnR(
            grossR = 1.0,
            entry = 100.0,
            riskDistance = 2.0,
            heldBars = 10,
            setupBarMinutes = 15,
            roundTripFeeBps = -1.0
        )

        assertTrue(result.isNaN())
    }

    @Test
    fun incompleteForwardWindowIsExcludedFromReplay() {
        assertTrue(hasCompleteTradeHorizon(setupIndex = 10, candleCount = 30, maxHoldingBars = 18))
        assertTrue(!hasCompleteTradeHorizon(setupIndex = 10, candleCount = 29, maxHoldingBars = 18))
    }

    @Test
    fun invalidHorizonBoundsAreRejected() {
        assertTrue(!hasCompleteTradeHorizon(setupIndex = -1, candleCount = 100, maxHoldingBars = 10))
        assertTrue(!hasCompleteTradeHorizon(setupIndex = 10, candleCount = 100, maxHoldingBars = 0))
    }
}
