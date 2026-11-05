package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BinanceRestGuardTest {
    @Before fun resetGuard() = BinanceRestGuard.resetForTests()
    @Test
    fun recordsBinance418BanExpiryFromResponseBody() {
        val expiry = 1_793_777_865_683L
        BinanceRestGuard.recordResponse(418, "Way too many requests; IP(127.0.0.1) banned until $expiry", now = 1_700_000_000_000L)
        assertEquals(expiry, BinanceRestGuard.blockedUntilMillis())
    }

    @Test
    fun honorsRetryAfterFor429() {
        val now = 1_700_000_000_000L
        BinanceRestGuard.recordResponse(429, "Too many requests", retryAfterSeconds = "30", now = now)
        assertEquals(now + 30_000L, BinanceRestGuard.blockedUntilMillis())
    }

    @Test
    fun pausedRequestsExplainWhenSyncCanResume() {
        val now = 1_700_000_000_000L
        BinanceRestGuard.recordResponse(429, "Too many requests", retryAfterSeconds = "60", now = now)
        val error = runCatching { BinanceRestGuard.beforeRequest(now) }.exceptionOrNull()
        assertTrue(error?.message?.contains("Sync can resume after") == true)
        assertFalse(error?.message?.contains("1700") == true)
    }
}
