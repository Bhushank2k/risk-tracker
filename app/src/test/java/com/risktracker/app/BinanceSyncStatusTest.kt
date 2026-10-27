package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceSyncStatusTest {
    @Test
    fun successfulSyncIsExplicitlyWindowBounded() {
        assertEquals("Up to date (7-day window)", classifyBinanceSyncState(
            success = true,
            failureCount = 0,
            discoverySucceeded = 3
        ))
    }

    @Test
    fun failedRequestsWithSuccessfulDiscoveryArePartial() {
        assertEquals("Partial sync", classifyBinanceSyncState(
            success = false,
            failureCount = 2,
            discoverySucceeded = 1
        ))
    }

    @Test
    fun noSuccessfulDiscoveryIsFailure() {
        assertEquals("Sync failed", classifyBinanceSyncState(
            success = false,
            failureCount = 3,
            discoverySucceeded = 0
        ))
    }

    @Test
    fun zeroFailuresWithoutSuccessfulDiscoveryIsStillFailure() {
        assertEquals("Sync failed", classifyBinanceSyncState(
            success = false,
            failureCount = 0,
            discoverySucceeded = 0
        ))
    }
}
