package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class BinanceFiveYearTradeBackfillTest {
    private fun utcMillis(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(value)!!.time

    @Test
    fun rollingFiveYearRangeIsSplitIntoFiveContiguousRequests() {
        val now = utcMillis("2026-11-24 12:00:00")
        val windows = fiveYearFuturesTradeWindows(now)

        assertEquals(5, windows.size)
        assertEquals(utcMillis("2021-11-24 12:00:00"), windows.first().start)
        assertEquals(now, windows.last().end)
        windows.forEach { window ->
            assertTrue(window.start <= window.end)
            // Binance accepts no more than one year per asynchronous archive request.
            assertTrue(window.end - window.start < 367L * 24 * 60 * 60 * 1000)
        }
        windows.zipWithNext().forEach { (left, right) ->
            assertEquals(left.end + 1L, right.start)
        }
    }

    @Test
    fun partialCurrentYearIsIncludedWithoutExtendingPastNow() {
        val now = utcMillis("2026-11-24 12:00:00")
        val windows = fiveYearFuturesTradeWindows(now)

        assertEquals(now, windows.last().end)
        assertTrue(windows.last().end < windows.last().start + 366L * 24 * 60 * 60 * 1000)
    }
    @Test
    fun parsesBinanceIpBanExpiryFrom418Message() {
        assertEquals(1795534036482L, parseBinanceIpBanUntilMillis(
            "Way too many requests; IP(152.56.15.155) banned until 1795534036482. Please use websocket."
        ))
    }

    @Test
    fun missingBanExpiryReturnsNull() {
        assertEquals(null, parseBinanceIpBanUntilMillis("Way too many requests"))
    }
    @Test
    fun parsesRetryAfterSeconds() {
        assertEquals(15_000L, parseBinanceRetryAfterMillis("15", 0L))
    }

    @Test
    fun parsesRetryAfterHttpDate() {
        assertEquals(
            utcMillis("2026-11-25 03:55:00"),
            parseBinanceRetryAfterMillis("Wed, 25 Nov 2026 03:55:00 GMT", utcMillis("2026-11-25 03:50:00"))
        )
    }

    @Test
    fun ignoresExpiredRetryAfterHttpDate() {
        assertEquals(
            null,
            parseBinanceRetryAfterMillis("Tue, 24 Nov 2026 03:55:00 GMT", utcMillis("2026-11-25 03:50:00"))
        )
    }

}
