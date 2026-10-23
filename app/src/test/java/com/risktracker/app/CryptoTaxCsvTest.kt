package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CryptoTaxCsvTest {
    private fun trade(
        id: String,
        timestamp: String,
        symbol: String = "BTCUSDT",
        source: String = "spot,export.csv",
        pnl: Double? = null
    ) = HistoricalCryptoTrade(
        id = id,
        timestamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(timestamp)!!.time,
        exchange = "Binance",
        market = "Spot",
        symbol = symbol,
        side = "SELL",
        quantity = 0.5,
        price = 100.0,
        quoteAmount = 50.0,
        fee = 0.1,
        feeAsset = "USDT",
        realizedPnl = pnl,
        source = source
    )

    @Test fun exportsOnlySelectedFinancialYearAndKeepsAllRowsAndHeaders() {
        val csv = historicalTradesToCsv(
            listOf(
                trade("later", "2026-04-01"),
                trade("selected", "2026-03-31", pnl = 12.5),
                trade("earlier", "2025-03-31")
            ),
            2025
        )

        val lines = csv.trimEnd().lines()
        assertEquals("id,timestamp_utc,financial_year,exchange,market,symbol,side,quantity,price,quote_amount,fee,fee_asset,exchange_realized_pnl,source", lines.first())
        assertEquals(2, lines.size)
        assertEquals(true, lines[1].startsWith("selected,2026-03-31T00:00:00Z,2025-26,"))
        assertEquals(true, lines[1].endsWith(",12.5,\"spot,export.csv\""))
    }

    @Test fun escapesQuotesAndCommasAndLeavesMissingPnlBlank() {
        val csv = historicalTradesToCsv(listOf(trade("id,\"1", "2025-05-01", symbol = "COIN,\"X")), 2025)
        val row = csv.trimEnd().lines()[1]
        assertEquals(true, row.startsWith("\"id,\"\"1\","))
        assertEquals(true, row.contains("\"COIN,\"\"X\""))
        assertEquals(true, row.endsWith(",USDT,," + "\"spot,export.csv\""))
    }
}
