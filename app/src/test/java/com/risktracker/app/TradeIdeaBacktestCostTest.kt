package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaBacktestCostTest {


    @Test
    fun replayUsesOnlyCandlesClosedByTheDecisionTime() {
        val candles = listOf(
            V2Candle(101.0, 99.0, 100.0, 1.0, 1_000L),
            V2Candle(102.0, 100.0, 101.0, 1.0, 2_000L),
            V2Candle(103.0, 101.0, 102.0, 1.0, 3_000L)
        )
        val available = closedReplayCandlesAtOrBefore(candles, 2_000L)
        assertEquals(listOf(1_000L, 2_000L), available.map { it.closeTime })
        assertEquals(2, available.size)
    }

    @Test
    fun stopWinsWhenLongBarTouchesStopAndTargets() {
        val candle = V2Candle(107.0, 97.0, 102.0, 1.0, 1L)
        assertEquals(-1.0, resolveTradeReplayBar(true, 100.0, 98.0, 2.0, false, candle).exitR!!, 0.0)
    }

    @Test
    fun stopWinsWhenShortBarTouchesStopAndTargets() {
        val candle = V2Candle(103.0, 93.0, 98.0, 1.0, 1L)
        assertEquals(-1.0, resolveTradeReplayBar(false, 100.0, 102.0, 2.0, false, candle).exitR!!, 0.0)
    }

    @Test
    fun breakEvenStopAfterFirstTargetPreservesPartialProfit() {
        val candle = V2Candle(104.0, 99.0, 101.0, 1.0, 1L)
        assertEquals(1.0, resolveTradeReplayBar(true, 100.0, 98.0, 2.0, true, candle).exitR!!, 0.0)
    }

    @Test
    fun firstTargetIsPartialAndSecondTargetClosesAtWeightedTwoPointFiveR() {
        val first = V2Candle(104.0, 99.0, 103.0, 1.0, 1L)
        val second = V2Candle(106.0, 101.0, 105.0, 1.0, 2L)
        assertTrue(resolveTradeReplayBar(true, 100.0, 98.0, 2.0, false, first).reachedTarget1)
        assertEquals(2.5, resolveTradeReplayBar(true, 100.0, 98.0, 2.0, true, second).exitR!!, 0.0)
    }

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
    fun requiredGrossTargetIncludesAllEstimatedCostComponents() {
        val gross = tradeIdeaGrossRForMinimumNetReturn(
            minimumNetR = 2.0, entry = 100.0, riskDistance = 0.5,
            heldBars = 12, setupBarMinutes = 15,
            roundTripFeeBps = 8.0, slippageBps = 4.0, fundingBpsPer8h = 1.0
        )
        val net = tradeIdeaNetReturnR(
            grossR = gross, entry = 100.0, riskDistance = 0.5,
            heldBars = 12, setupBarMinutes = 15,
            roundTripFeeBps = 8.0, slippageBps = 4.0, fundingBpsPer8h = 1.0
        )
        assertEquals(2.0, net, 1e-9)
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
    @Test
    fun trendPullbackPredicateRequiresReclaimAndDirectionalClose() {
        val previous = V2Candle(102.0, 99.0, 100.0, 100.0, 1L)
        val reclaimed = V2Candle(103.0, 99.8, 102.0, 120.0, 2L)
        val failedReclaim = V2Candle(101.0, 99.8, 100.0, 120.0, 2L)

        assertTrue(isReplayTrendPullback(true, reclaimed, previous, 101.0, 2.0))
        assertTrue(!isReplayTrendPullback(true, failedReclaim, previous, 101.0, 2.0))
    }

    @Test
    fun rangeReversalPredicateRequiresRangeEdgeRejectionAndRsi() {
        val lowerRejection = V2Candle(101.0, 90.0, 99.0, 100.0, 1L, open = 95.0)
        val upperRejection = V2Candle(110.0, 99.0, 101.0, 100.0, 2L, open = 105.0)

        assertTrue(isReplayRangeReversal(true, true, lowerRejection, 110.0, 90.0, 3.0, 40.0))
        assertTrue(!isReplayRangeReversal(true, true, lowerRejection, 110.0, 90.0, 3.0, 50.0))
        assertTrue(isReplayRangeReversal(false, true, upperRejection, 110.0, 90.0, 3.0, 60.0))
        assertTrue(!isReplayRangeReversal(false, false, upperRejection, 110.0, 90.0, 3.0, 60.0))
    }

}
