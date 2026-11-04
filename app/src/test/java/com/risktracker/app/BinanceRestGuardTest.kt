package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceRestGuardTest {
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
}
