package com.risktracker.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceMarkPriceStreamTest {

    @Test
    fun fundingRateMustBeFiniteAndFreshEvenWithoutOrderBook() {
        assertEquals(0.0001, freshFundingRate(0.0001, 100_000L, 160_000L)!!, 0.0)
        assertNull(freshFundingRate(0.0001, 99_999L, 160_000L))
        assertNull(freshFundingRate(Double.NaN, 100_000L, 100_001L))
        assertNull(freshFundingRate(Double.POSITIVE_INFINITY, 100_000L, 100_001L))
        assertNull(freshFundingRate(0.0001, 100_001L, 100_000L))
    }


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
    fun sequentialSymbolUpdatesPreserveTheLatestPricesForOtherPositions() {
        val btc = BinanceLiveTrade(
            id = "futures:BTCUSDT:BOTH", symbol = "BTCUSDT", market = "USDⓈ-M Futures",
            side = "LONG", quantity = 0.01, entryPrice = 100_000.0, markPrice = 100_000.0,
            unrealizedPnl = 0.0, leverage = 10, liquidationPrice = 90_000.0, status = "OPEN"
        )
        val eth = BinanceLiveTrade(
            id = "futures:ETHUSDT:BOTH", symbol = "ETHUSDT", market = "USDⓈ-M Futures",
            side = "SHORT", quantity = 0.1, entryPrice = 3_000.0, markPrice = 3_000.0,
            unrealizedPnl = 0.0, leverage = 5, liquidationPrice = 3_600.0, status = "OPEN"
        )

        val afterBtc = listOf(btc, eth).withStreamedMarkPrice("BTCUSDT", 101_000.0)
        val afterEth = afterBtc.withStreamedMarkPrice("ETHUSDT", 2_900.0)

        assertEquals(101_000.0, afterEth.first().markPrice, 0.0)
        assertEquals(10.0, afterEth.first().unrealizedPnl, 1e-9)
        assertEquals(2_900.0, afterEth.last().markPrice, 0.0)
        assertEquals(10.0, afterEth.last().unrealizedPnl, 1e-9)
    }

    @Test
    fun streamUpdateDoesNotRevalueNonUsdMarginedPositions() {
        val spot = BinanceLiveTrade(
            id = "spot:BTCUSDT", symbol = "BTCUSDT", market = "Spot",
            side = "LONG", quantity = 0.01, entryPrice = 100_000.0, markPrice = 100_000.0,
            unrealizedPnl = 0.0, leverage = 1, liquidationPrice = 0.0, status = "OPEN"
        )

        val updated = listOf(spot).withStreamedMarkPrice("BTCUSDT", 101_000.0).single()

        assertEquals(100_000.0, updated.markPrice, 0.0)
        assertEquals(0.0, updated.unrealizedPnl, 0.0)
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
        assertEquals(null, binanceBanExpiry("IP temporarily rate limited"))
        assertEquals(null, binanceBanExpiry("IP temporarily rate limited"))
    }
}
