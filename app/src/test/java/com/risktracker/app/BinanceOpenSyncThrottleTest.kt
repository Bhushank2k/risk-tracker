package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceOpenSyncThrottleTest {
    private val now = 1_000_000_000L
    private val interval = BINANCE_OPEN_SYNC_MIN_INTERVAL_MS

    @Test
    fun appOpenSyncThrottleIsFifteenMinutes() {
        assertEquals(15L * 60L * 1000L, interval)
    }

    @Test
    fun syncsWhenNeverAttempted() {
        assertTrue(shouldSyncOnOpen(lastAttemptMillis = 0L, nowMillis = now))
    }

    @Test
    fun skipsWhenLastAttemptIsRecent() {
        assertFalse(shouldSyncOnOpen(lastAttemptMillis = now - 1_000L, nowMillis = now))
        assertFalse(shouldSyncOnOpen(lastAttemptMillis = now - interval + 1, nowMillis = now))
    }

    @Test
    fun syncsOnceIntervalHasPassed() {
        assertTrue(shouldSyncOnOpen(lastAttemptMillis = now - interval, nowMillis = now))
        assertTrue(shouldSyncOnOpen(lastAttemptMillis = now - 3 * interval, nowMillis = now))
    }

    @Test
    fun clockMovingBackwardsDoesNotBlockSync() {
        assertTrue(shouldSyncOnOpen(lastAttemptMillis = now + interval, nowMillis = now))
    }
}
