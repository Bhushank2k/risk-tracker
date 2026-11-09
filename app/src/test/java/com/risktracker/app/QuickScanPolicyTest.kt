package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickScanPolicyTest {
    @Test fun priorityPrefersMovementAndLiquidityWithoutFilteringMarkets() {
        val active = quickScanMarketPriority(4.0, 8.0, 20_000_000.0)
        val quiet = quickScanMarketPriority(1.0, 1.0, 3_000_000.0)
        assertTrue(active > quiet)
        assertTrue(quickScanMarketPriority(0.5, 0.0, 2_000_000.0).isFinite())
    }

    @Test fun invalidTickerMetricsAreRankedLast() {
        assertEquals(
            Double.NEGATIVE_INFINITY,
            quickScanMarketPriority(Double.NaN, 2.0, 5_000_000.0),
            0.0
        )
        assertEquals(
            Double.NEGATIVE_INFINITY,
            quickScanMarketPriority(1.0, Double.POSITIVE_INFINITY, 5_000_000.0),
            0.0
        )
    }

    @Test fun boundedConcurrencyAndDepthBatchStayWithinStreamCapacity() {
        assertEquals(4, QUICK_SCAN_DEEP_SCAN_CONCURRENCY)
        assertEquals(150, QUICK_SCAN_DEPTH_BATCH_SIZE)
        assertTrue(QUICK_SCAN_DEEP_SCAN_CONCURRENCY <= QUICK_SCAN_DEPTH_BATCH_SIZE)
    }
}
