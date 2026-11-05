package com.risktracker.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds a transaction-level CSV for a single Indian financial year without dropping source fields. */
fun historicalTradesToCsv(trades: List<HistoricalCryptoTrade>, financialYearStart: Int): String {
    val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return buildString {
        appendLine("id,timestamp_utc,financial_year,exchange,market,symbol,side,quantity,price,quote_amount,fee,fee_asset,exchange_realized_pnl,source")
        trades.asSequence()
            .filter { it.year == financialYearStart }
            .sortedBy { it.timestamp }
            .forEach { trade ->
                val values = listOf(
                    trade.id,
                    timestampFormat.format(Date(trade.timestamp)),
                    "${trade.year}-${(trade.year + 1).toString().takeLast(2)}",
                    trade.exchange,
                    trade.market,
                    trade.symbol,
                    trade.side,
                    trade.quantity.toString(),
                    trade.price.toString(),
                    trade.quoteAmount.toString(),
                    trade.fee.toString(),
                    trade.feeAsset,
                    trade.realizedPnl?.toString().orEmpty(),
                    trade.source
                )
                appendLine(values.joinToString(",") { it.toCsvCell() })
            }
    }
}

private fun String.toCsvCell(): String {
    // Spreadsheets run a cell that starts with = + - @ as a formula. Prefix such text with an
    // apostrophe so it is shown as text. Real negative numbers are left alone.
    val safe = if (isNotEmpty() && first() in "=+-@\t\r" && toDoubleOrNull() == null) "'$this" else this
    if (safe.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return safe
    return "\"" + safe.replace("\"", "\"\"") + "\""
}
