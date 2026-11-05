package com.risktracker.app

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BinanceFillCsvReport(
    val fills: List<BinanceJournalFill>,
    val duplicateRows: Int,
    val ignoredRows: Int
)

/** Imports Binance Spot or USDⓈ-M Futures execution-history CSVs without changing saved records until confirmed. */
object BinanceFillCsvImporter {
    fun parse(input: InputStream, market: String): BinanceFillCsvReport {
        require(market == "Spot" || market == "USDⓈ-M Futures") { "Choose Spot or USDⓈ-M Futures." }
        val lines = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readLines() }
            .filter { it.isNotBlank() }
        require(lines.size >= 2) { "The CSV must contain a header and at least one trade row." }
        val headers = splitCsvLine(lines.first()).map { it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT) }
        fun index(vararg names: String) = headers.indexOfFirst { h -> names.any { h == it } }
        val timeIndex = index("date(utc)", "date", "time", "timestamp", "create time", "created time", "transacttime")
        val symbolIndex = index("symbol", "pair", "market")
        val sideIndex = index("side", "type")
        val priceIndex = index("price", "average price")
        val qtyIndex = index("quantity", "qty", "executed", "filled", "base quantity", "position amount")
        val quoteIndex = index("amount", "quote quantity", "quote qty", "total", "quote amount")
        val feeIndex = index("fee", "commission", "commission amount")
        val feeAssetIndex = index("fee asset", "commission asset", "fee currency")
        val realizedIndex = index("realized pnl", "realized profit", "realizedpnl", "realized p&l", "realized profit (usdt)")
        val orderIndex = index("order id", "orderid", "order no.")
        require(timeIndex >= 0 && symbolIndex >= 0 && sideIndex >= 0 && priceIndex >= 0 && qtyIndex >= 0) {
            "Expected Binance trade columns for date/time, symbol/pair, side, price and quantity/executed."
        }
        val seen = mutableSetOf<String>()
        val fills = mutableListOf<BinanceJournalFill>()
        var duplicates = 0
        var ignored = 0
        lines.drop(1).forEachIndexed { offset, line ->
            val rowNo = offset + 2
            val cols = splitCsvLine(line)
            require(cols.size == headers.size) { "Malformed CSV row $rowNo: expected ${headers.size} columns, found ${cols.size}." }
            fun cell(i: Int) = if (i >= 0) cols[i].trim() else ""
            val rawTime = cell(timeIndex)
            val time = parseTime(rawTime) ?: error("Could not parse date/time on CSV row $rowNo.")
            val rawSymbol = cell(symbolIndex).uppercase(Locale.ROOT).replace("/", "").replace("-", "").replace("_", "")
            val symbol = rawSymbol.removeSuffix("PERP")
            require(symbol.matches(Regex("[A-Z0-9]{4,30}"))) { "Invalid symbol on CSV row $rowNo." }
            val side = when (cell(sideIndex).uppercase(Locale.ROOT)) {
                "BUY", "LONG" -> "BUY"
                "SELL", "SHORT" -> "SELL"
                else -> { ignored++; return@forEachIndexed }
            }
            fun number(value: String): Double? = Regex("^[+-]?[0-9,]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                .find(value.trim())?.value?.replace(",", "")?.toDoubleOrNull()
            val price = number(cell(priceIndex)) ?: error("Invalid price on CSV row $rowNo.")
            val qty = number(cell(qtyIndex)) ?: error("Invalid quantity on CSV row $rowNo.")
            require(price > 0 && qty > 0 && price.isFinite() && qty.isFinite()) { "Price and quantity must be positive on CSV row $rowNo." }
            val quote = number(cell(quoteIndex))?.takeIf { it > 0 } ?: qty * price
            val feeRaw = cell(feeIndex)
            val fee = number(feeRaw) ?: 0.0
            val feeAsset = cell(feeAssetIndex).ifBlank {
                Regex("[A-Za-z]{2,10}\\s*$").find(feeRaw)?.value?.trim()?.uppercase(Locale.ROOT) ?: ""
            }
            val realized = number(cell(realizedIndex))
            val fingerprintInput = listOf(market, symbol, rawTime, side, price.toString(), qty.toString(), quote.toString(), feeRaw, cell(realizedIndex), cell(orderIndex)).joinToString("|")
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintInput.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (!seen.add(fingerprint)) { duplicates++; return@forEachIndexed }
            fills += BinanceJournalFill(
                id = "binance:csv:${if (market == "Spot") "spot" else "futures"}:$symbol:$fingerprint",
                exchange = "Binance", market = market, symbol = symbol, side = side,
                quantity = qty, price = price, quoteAmount = quote, fee = fee, feeAsset = feeAsset,
                realizedPnl = realized, time = time, orderId = cell(orderIndex)
            )
        }
        require(fills.isNotEmpty()) { "No valid trade rows were found in this CSV." }
        return BinanceFillCsvReport(fills.distinctBy { it.id }.sortedByDescending { it.time }, duplicates, ignored)
    }

    private fun parseTime(value: String): Long? {
        value.toLongOrNull()?.let { return if (it in 1L..99_999_999_999L) it * 1000L else it }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss' UTC'", "yyyy-MM-dd"
        )
        patterns.forEach { pattern ->
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = format.parse(value.trim(), position)
            if (parsed != null && position.index == value.trim().length) return parsed.time
        }
        return null
    }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { fields += field.toString(); field.setLength(0) }
                else -> field.append(ch)
            }
            i++
        }
        require(!quoted) { "an unclosed quoted field was found." }
        fields += field.toString()
        return fields
    }
}
