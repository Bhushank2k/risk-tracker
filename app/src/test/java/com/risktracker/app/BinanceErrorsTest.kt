package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ExecutionException

class BinanceErrorsTest {
    @Test
    fun missingBodyReadsAsEmptyInsteadOfThrowing() {
        assertEquals("", readBinanceBody(null))
        assertEquals("{}", readBinanceBody(ByteArrayInputStream("{}".toByteArray())))
    }

    @Test
    fun prefersJsonMessageFromBinance() {
        val body = """{"code":-1021,"msg":"Timestamp for this request is outside of the recvWindow."}"""
        assertEquals("Timestamp for this request is outside of the recvWindow.", binanceMessage(body))
    }

    @Test
    fun usesShortPlainTextButNeverHtml() {
        assertEquals("Service unavailable from a restricted location", binanceMessage("Service unavailable from a restricted location"))
        assertNull(binanceMessage("<html><body>Forbidden</body></html>"))
        assertNull(binanceMessage("   "))
    }

    @Test
    fun describesUnwrappedCauseAndNeverReturnsBlank() {
        assertEquals("boom", describeBinanceError(ExecutionException(IOException("boom"))))
        assertEquals("RuntimeException with no details", describeBinanceError(RuntimeException()))
    }

    @Test
    fun detectsClockSkewMessages() {
        assertTrue(isBinanceTimestampError("Timestamp for this request was 1000ms ahead of the server's time."))
        assertTrue(isBinanceTimestampError("Timestamp for this request is outside of the recvWindow."))
        assertFalse(isBinanceTimestampError("Invalid API-key, IP, or permissions for action."))
        assertFalse(isBinanceTimestampError(null))
    }
}
