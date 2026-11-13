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
        assertEquals(0.1, result.feesInOtherAssets["BNB"]!!, 0.0001)
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

    @Test fun realizedOrdersAreChronologicalAndExposeExpectancyStreaksAndDrawdown() {
        val fills = listOf(
            fill("4", "SELL", 6.0, 0.0, "USDT", "d").copy(time = 40L),
            fill("2", "SELL", -3.0, 0.0, "USDT", "b").copy(time = 20L),
            fill("3", "BUY", 4.0, 0.0, "USDT", "c").copy(time = 30L),
            fill("1", "BUY", -2.0, 0.0, "USDT", "a").copy(time = 10L)
        )
        val result = summarizeExchangeOrders(fills)
        assertEquals(listOf(-2.0, -3.0, 4.0, 6.0), result.realizedOrders)
        assertEquals(1.25, result.expectancyPerOrder!!, 0.0001)
        assertEquals(2.0, result.averageWinLossRatio!!, 0.0001)
        assertEquals("Win", result.currentStreakKind)
        assertEquals(2, result.currentStreakCount)
        assertEquals(2, result.longestWinStreak)
        assertEquals(2, result.longestLossStreak)
        assertEquals(5.0, result.maxOrderDrawdown, 0.0001)
        assertEquals(listOf(-2.0, -5.0, -1.0, 5.0), result.cumulativePnl)
    }

    @Test fun rMultiplesUseRecordedInitialRiskAndAfterFeePnl() {
        val trades = listOf(
            TradeEntry("1", "2024-01-01", "BTCUSDT", "Long", 100.0, 110.0, 1.0,
                fees = 1.0, riskUsd = 10.0),
            TradeEntry("2", "2024-01-02", "ETHUSDT", "Short", 100.0, 110.0, 1.0,
                fees = 0.0, riskUsd = 20.0),
            TradeEntry("3", "2024-01-03", "SOLUSDT", "Long", 100.0, 105.0, 1.0,
                riskUsd = 0.0)
        )
        val result = summarizeTradeRMultiples(trades)
        assertEquals(2, result.sampleCount)
        assertEquals(0.2, result.averageR!!, 0.0001)
        assertEquals(0.4, result.totalR!!, 0.0001)
        assertEquals(50.0, result.winRatePercent!!, 0.0001)
        assertEquals(1, result.missingRiskCount)
    }

    @Test fun futuresIncomeBreakdownAggregatesOnlySelectedDates() {
        val rows = listOf(
            BinanceDailyResult("2024-01-01", 0.0, 97.0,
                mapOf("REALIZED_PNL" to 100.0, "COMMISSION" to -2.0, "FUNDING_FEE" to -1.0)),
            BinanceDailyResult("2024-01-02", 0.0, 48.0,
                mapOf("REALIZED_PNL" to 50.0, "COMMISSION" to -2.0)),
            BinanceDailyResult("2024-01-03", 0.0, 500.0,
                mapOf("REALIZED_PNL" to 500.0))
        )
        val result = summarizeFuturesIncomeByType(rows, setOf("2024-01-01", "2024-01-02"))
        assertEquals(150.0, result["REALIZED_PNL"]!!, 0.0001)
        assertEquals(-4.0, result["COMMISSION"]!!, 0.0001)
        assertEquals(-1.0, result["FUNDING_FEE"]!!, 0.0001)
    }

    private fun fill(id: String, side: String, pnl: Double, fee: Double, asset: String, order: String) =
        BinanceJournalFill(id, "Binance", "USDⓈ-M Futures", "BTCUSDT", side,
            1.0, 100.0, 100.0, fee, asset, pnl, 1_700_000_000_000L, order)
}
