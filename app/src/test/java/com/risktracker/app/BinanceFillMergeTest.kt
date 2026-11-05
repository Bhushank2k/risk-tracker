package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceFillMergeTest {
    private fun fill(id: String, orderId: String = "order-1") = BinanceJournalFill(
        id = id,
        exchange = "Binance",
        market = "Spot",
        symbol = "BTCUSDT",
        side = "BUY",
        quantity = 0.01,
        price = 65000.0,
        quoteAmount = 650.0,
        fee = 0.1,
        feeAsset = "USDT",
        realizedPnl = null,
        time = 1_700_000_000_000L,
        orderId = orderId
    )

    @Test
    fun distinctApiTradeIdsRemainDistinctEvenWhenOneOrderHasIdenticalPartialFills() {
        assertFalse(areEquivalentCsvAndApiFills(
            fill("binance:spot:BTCUSDT:trade-101"),
            fill("binance:spot:BTCUSDT:trade-102")
        ))
    }

    @Test
    fun matchingCsvAndApiRowsCanStillBeReconciled() {
        assertTrue(areEquivalentCsvAndApiFills(
            fill("binance:csv:spot:BTCUSDT:fingerprint"),
            fill("binance:spot:BTCUSDT:trade-101")
        ))
    }

    @Test
    fun differentOrdersAreNeverMatchedAsTheSameExecution() {
        assertFalse(areEquivalentCsvAndApiFills(
            fill("binance:csv:spot:BTCUSDT:fingerprint", "order-1"),
            fill("binance:spot:BTCUSDT:trade-101", "order-2")
        ))
    }

    @Test
    fun twoDifferentCsvRowsAreNotCollapsedByApproximateMatching() {
        assertFalse(areEquivalentCsvAndApiFills(
            fill("binance:csv:spot:BTCUSDT:fingerprint-a"),
            fill("binance:csv:spot:BTCUSDT:fingerprint-b")
        ))
    }
}
