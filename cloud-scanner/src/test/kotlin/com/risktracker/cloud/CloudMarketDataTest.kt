package com.risktracker.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudMarketDataTest {
    @Test fun fiveLevelDepthMatchesMobileNotionalSemantics() {
        val bids = (1..5).map { (100.0 - it * 0.1) to 100.0 }
        val asks = (1..5).map { (100.0 + it * 0.1) to 100.0 }
        val micro = buildMarketMicrostructure(99.9, 100.1, bids, asks, 0.0001)
        assertNotNull(micro.spreadBps)
        assertEquals(20.0, micro.spreadBps!!, 0.0001)
        assertEquals(0.0, micro.bookImbalance!!, 0.0001)
        assertTrue(micro.bidDepthUsd!! > 0.0)
        assertEquals(0.0001, micro.fundingRate!!, 0.0000001)
    }

    @Test fun incompleteDepthFailsClosed() {
        val fourLevels = (1..4).map { (100.0 - it * 0.1) to 100.0 }
        val fiveAsks = (1..5).map { (100.0 + it * 0.1) to 100.0 }
        val micro = buildMarketMicrostructure(99.9, 100.1, fourLevels, fiveAsks, null)
        assertNull(micro.bookImbalance)
        assertNull(micro.bidDepthUsd)
        assertNull(micro.askDepthUsd)
        assertNull(micro.fundingRate)
    }
}
