package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsDomainTest {
    @Test fun groupsPartialFillsAndSubtractsOnlyStablecoinFees() {
        val fills = listOf(
            fill("1", "BUY", 6.0, 0.0, "USDT", "order-a"),
            fill("2", "BUY", 4.0, 0.0, "USDT", "order-a"),
            fill("3", "SELL", -5.0, 0.0, "BNB", "order-b"),
            fill("4", "SELL", 0.0, 0.25, "USDT", "order-b"),
            fill("5", "SELL", 0.0, 0.1, "BNB", "order-b")
        )
        val result = summarizeExchangeOrders(fills)
        assertEquals(2, result.realizedOrders.size)
        assertEquals(10.0, result.grossProfit, 0.0001)
        assertEquals(5.0, result.grossLoss, 0.0001)
        assertEquals(0.25, result.knownStableFees, 0.0001)
        assertEquals(4.75, result.netAfterKnownStableFees, 0.0001)
        assertEquals(0.1, result.feesInOtherAssets["BNB"], 0.0001)
        assertEquals(50.0, result.winRatePercent!!, 0.0001)
        assertEquals(2.0, result.profitFactor!!, 0.0001)
    }

    @Test fun emptyOrderHistoryHasNoWinRateOrProfitFactor() {
        val result = summarizeExchangeOrders(emptyList())
        assertNull(result.winRatePercent)
        assertNull(result.profitFactor)
        assertNull(result.averageWin)
        assertNull(result.averageLoss)
        assertEquals(0.0, result.netAfterKnownStableFees, 0.0)
    }

    private fun fill(id: String, side: String, pnl: Double, fee: Double, asset: String, order: String) =
        BinanceJournalFill(id, "Binance", "USDⓈ-M Futures", "BTCUSDT", side,
            1.0, 100.0, 100.0, fee, asset, pnl, 1_700_000_000_000L, order)
}
