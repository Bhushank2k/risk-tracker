package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaScanCompletionTest {
    @Test
    fun cleanScanWithNoSetupsIsACompletedResult() {
        assertTrue(isCompletedEmptyTradeIdeaScan(evaluatedMarkets = 42, dataFailures = 0))
    }

    @Test
    fun scanWithMarketDataFailuresIsNotACompletedEmptyResult() {
        assertFalse(isCompletedEmptyTradeIdeaScan(evaluatedMarkets = 42, dataFailures = 1))
    }

    @Test
    fun zeroEvaluatedMarketsIsNotACompletedResult() {
        assertFalse(isCompletedEmptyTradeIdeaScan(evaluatedMarkets = 0, dataFailures = 0))
    }

    @Test
    fun invalidNegativeCountsAreNotAccepted() {
        assertFalse(isCompletedEmptyTradeIdeaScan(evaluatedMarkets = -1, dataFailures = 0))
        assertFalse(isCompletedEmptyTradeIdeaScan(evaluatedMarkets = 10, dataFailures = -1))
    }
}
