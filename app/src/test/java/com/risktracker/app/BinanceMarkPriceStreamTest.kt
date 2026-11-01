package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BinanceMarkPriceStreamTest {
    @Test
    fun longPositionPnlUsesStreamedMarkPrice() {
        val position = BinanceLiveTrade(
            id = "futures:BTCUSDT:BOTH", symbol = "BTCUSDT", market = "USDⓈ-M Futures",
            side = "LONG", quantity = 0.01, entryPrice = 100_000.0, markPrice = 100_000.0,
            unrealizedPnl = 0.0, leverage = 10, liquidationPrice = 90_000.0, status = "OPEN"
        )

        val updated = position.withMarkPrice(101_000.0)

        assertEquals(101_000.0, updated.markPrice, 0.0)
        assertEquals(10.0, updated.unrealizedPnl, 1e-9)
    }

    @Test
    fun shortPositionPnlUsesStreamedMarkPrice() {
        val position = BinanceLiveTrade(
            id = "futures:BTCUSDT:BOTH", symbol = "BTCUSDT", market = "USDⓈ-M Futures",
            side = "SHORT", quantity = 0.01, entryPrice = 100_000.0, markPrice = 100_000.0,
            unrealizedPnl = 0.0, leverage = 10, liquidationPrice = 110_000.0, status = "OPEN"
        )

        val updated = position.withMarkPrice(99_000.0)

        assertEquals(99_000.0, updated.markPrice, 0.0)
        assertEquals(10.0, updated.unrealizedPnl, 1e-9)
    }

    @Test
    fun parsesBinanceIpBanExpiry() {
        assertEquals(
            1_793_546_458_047L,
            binanceBanExpiry("Way too many requests; IP(152.56.13.57) banned until 1793546458047")
        )
    }

    @Test
    fun missingBanExpiryReturnsNull() {
        assertNotNull(binanceBanExpiry("IP temporarily rate limited"))
        assertEquals(null, binanceBanExpiry("IP temporarily rate limited"))
    }
}
