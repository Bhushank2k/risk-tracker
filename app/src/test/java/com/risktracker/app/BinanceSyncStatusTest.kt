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
    @Test
    fun symbolBatchRotatesWithoutStarvingLaterSymbols() {
        val symbols = (1..205).map { "COIN%03dUSDT".format(it) }
        val first = selectBinanceSymbolBatch(symbols, offset = 0)
        val second = selectBinanceSymbolBatch(symbols, offset = first.second)
        val third = selectBinanceSymbolBatch(symbols, offset = second.second)

        assertEquals(100, first.first.size)
        assertEquals(100, second.first.size)
        assertEquals(5, third.first.size)
        assertEquals(205, (first.first + second.first + third.first).distinct().size)
        assertEquals(0, third.second)
    }

    @Test
    fun smallSymbolSetDoesNotNeedRotation() {
        val batch = selectBinanceSymbolBatch(listOf("ETHUSDT", "BTCUSDT"), offset = 99)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), batch.first)
        assertEquals(0, batch.second)
    }

}
