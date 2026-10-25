package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class TradeJournalAnalyticsTest {
    private fun fill(
        id: String, side: String, qty: Double, price: Double, fee: Double = 0.0,
        feeAsset: String = "USDT", time: Long, market: String = "Spot", pnl: Double? = null
    ) = BinanceJournalFill(
        id = id, exchange = "Binance", market = market, symbol = "BTCUSDT",
        side = side, quantity = qty, price = price, quoteAmount = qty * price,
        fee = fee, feeAsset = feeAsset, realizedPnl = pnl, time = time, orderId = id
    )

    @Test
    fun fifoSpotSaleReportsRealizedPnlAfterQuoteFees() {
        val fills = listOf(
            fill("buy", "BUY", 1.0, 100.0, fee = 1.0, time = 1),
            fill("sell", "SELL", 0.5, 150.0, fee = 0.5, time = 2)
        )
        val sale = TradeJournalAnalytics.summarize(fills).spotClosedSales.single()
        assertTrue(sale.basisComplete)
        assertEquals(24.0, sale.netPnlQuote!!, 1e-9) // proceeds 75 - FIFO basis 50.5 - fee .5
        assertEquals(24.5, sale.grossPnlQuote!!, 1e-9)
    }

    @Test
    fun saleWithoutEnoughImportedBuyBasisIsNotGivenInventedPnl() {
        val sale = TradeJournalAnalytics.summarize(
            listOf(fill("sell", "SELL", 0.5, 150.0, time = 2))
        ).spotClosedSales.single()
        assertFalse(sale.basisComplete)
        assertNull(sale.grossPnlQuote)
        assertNull(sale.netPnlQuote)
    }

    @Test
    fun baseAssetFeeReducesBuyInventoryAndIncreasesSellDisposal() {
        val fills = listOf(
            fill("buy", "BUY", 1.0, 100.0, fee = 0.1, feeAsset = "BTC", time = 1),
            fill("sell", "SELL", 0.9, 120.0, fee = 0.0, time = 2)
        )
        val sale = TradeJournalAnalytics.summarize(fills).spotClosedSales.single()
        assertTrue(sale.basisComplete)
        assertEquals(8.0, sale.netPnlQuote!!, 1e-9)
    }

    @Test
    fun nonQuoteFeeDoesNotGetSilentlyConverted() {
        val sale = TradeJournalAnalytics.summarize(
            listOf(
                fill("buy", "BUY", 1.0, 100.0, time = 1),
                fill("sell", "SELL", 1.0, 120.0, fee = 0.01, feeAsset = "BNB", time = 2)
            )
        ).spotClosedSales.single()
        assertEquals(20.0, sale.grossPnlQuote!!, 1e-9)
        assertNull(sale.netPnlQuote)
        assertTrue(sale.feeConversionNeeded)
    }

    @Test
    fun futuresSummarySubtractsKnownQuoteFees() {
        val summary = TradeJournalAnalytics.summarize(
            listOf(fill("f1", "BUY", 1.0, 100.0, fee = 0.2, time = 1, market = "USDⓈ-M Futures", pnl = 10.0))
        )
        val usdt = summary.futuresPnlByQuoteAsset.getValue("USDT")
        assertEquals(10.0, usdt.first, 1e-9)
        assertEquals(0.2, usdt.second, 1e-9)
        assertEquals(9.8, usdt.first - usdt.second, 1e-9)
    }
}
