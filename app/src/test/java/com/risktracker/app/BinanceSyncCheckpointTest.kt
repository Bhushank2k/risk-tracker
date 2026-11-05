package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceSyncCheckpointTest {
    @Test
    fun firstSyncUsesRollingSevenDayWindow() {
        val end = 30L * 24 * 60 * 60 * 1000
        assertEquals(end - BINANCE_SYMBOL_SYNC_LOOKBACK_MS, binanceSymbolSyncStart(0L, end))
    }

    @Test
    fun staleCheckpointResumesFromLastSuccessInsteadOfSkippingGap() {
        val end = 60L * 24 * 60 * 60 * 1000
        val lastSuccess = end - 30L * 24 * 60 * 60 * 1000
        assertEquals(
            lastSuccess - BINANCE_SYMBOL_SYNC_OVERLAP_MS,
            binanceSymbolSyncStart(lastSuccess, end)
        )
    }

    @Test
    fun checkpointAfterCurrentTimeFallsBackToRecentWindow() {
        val end = 30L * 24 * 60 * 60 * 1000
        assertEquals(
            end - BINANCE_SYMBOL_SYNC_LOOKBACK_MS,
            binanceSymbolSyncStart(end + 1L, end)
        )
    }

    @Test
    fun overlapNeverProducesNegativeTimestamp() {
        assertEquals(0L, binanceSymbolSyncStart(1L, 10L))
    }
}
