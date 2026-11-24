package com.risktracker.app

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class AppErrorMessageTest {
    @Test fun mapsBinanceIpBanToShortMessage() {
        assertEquals(
            "Binance has temporarily blocked requests. Please wait before retrying.",
            AppErrorMessage.from(IllegalStateException("Binance Futures (418): banned until 123456"), "Fallback")
        )
    }

    @Test fun mapsRateLimitToShortMessage() {
        assertEquals(
            "Too many requests. Please wait a moment and try again.",
            AppErrorMessage.from(IllegalStateException("HTTP 429: too many requests"), "Fallback")
        )
    }

    @Test fun mapsNetworkFailures() {
        assertEquals(
            "No internet connection or DNS unavailable. Check your connection and retry.",
            AppErrorMessage.from(UnknownHostException("private host detail"), "Fallback")
        )
        assertEquals(
            "The request timed out. Please try again.",
            AppErrorMessage.from(SocketTimeoutException("private timeout detail"), "Fallback")
        )
    }

    @Test fun doesNotExposeRawServerMessageForUnknownFailures() {
        assertEquals(
            "Could not load data. Try again.",
            AppErrorMessage.from(IOException("raw server payload and internal details"), "Could not load data. Try again.")
        )
    }
    @Test fun mapsAuthorizationAndServerErrorsWithoutRawDetails() {
        assertEquals(
            "Access was denied. Check your read-only API credentials and permissions.",
            AppErrorMessage.from(IllegalStateException("HTTP 403: private response body"), "Fallback")
        )
        assertEquals(
            "The service is temporarily unavailable. Please try again shortly.",
            AppErrorMessage.from(IllegalStateException("HTTP 503: upstream diagnostic"), "Fallback")
        )
    }

}
