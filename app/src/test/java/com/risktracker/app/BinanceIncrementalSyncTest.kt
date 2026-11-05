package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceIncrementalSyncTest {
    @Test
    fun firstSyncRetainsSevenDayRecoveryWindow() {
        val now = 1_800_000_000_000L
        assertEquals(now - BINANCE_SYMBOL_SYNC_LOOKBACK_MS, binanceSymbolSyncStart(0L, now))
    }

    @Test
    fun subsequentSyncUsesCheckpointWithOverlap() {
        val now = 1_800_000_000_000L
        val checkpoint = now - 5 * 60_000L
        assertEquals(checkpoint - BINANCE_SYMBOL_SYNC_OVERLAP_MS, binanceSymbolSyncStart(checkpoint, now))
    }

    @Test
    fun staleCheckpointResumesFromLastSuccessInsteadOfSkippingGap() {
        val now = 1_800_000_000_000L
        val checkpoint = now - 30L * 24 * 60 * 60 * 1000
        assertEquals(
            checkpoint - BINANCE_SYMBOL_SYNC_OVERLAP_MS,
            binanceSymbolSyncStart(checkpoint, now)
        )
    }

    @Test
    fun futureCheckpointFallsBackWhenDeviceClockMovesBackwards() {
        val now = 1_800_000_000_000L
        assertEquals(now - BINANCE_SYMBOL_SYNC_LOOKBACK_MS,
            binanceSymbolSyncStart(now + 60_000L, now))
    }
}
