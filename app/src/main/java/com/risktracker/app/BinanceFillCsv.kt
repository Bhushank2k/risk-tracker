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
        val tradeIdIndex = index("trade id", "tradeid", "execution id", "executionid", "fill id", "fillid")
        require(timeIndex >= 0 && symbolIndex >= 0 && sideIndex >= 0 && priceIndex >= 0 && qtyIndex >= 0) {
            "Expected Binance trade columns for date/time, symbol/pair, side, price and quantity/executed."
        }
        val seenTradeIds = mutableSetOf<String>()
        val fallbackOccurrences = mutableMapOf<String, Int>()
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
            val rawSide = cell(sideIndex).uppercase(Locale.ROOT)
            require(rawSide.isNotBlank()) { "Missing side on CSV row $rowNo." }
            val side = when (rawSide) {
                "BUY", "LONG" -> "BUY"
                "SELL", "SHORT" -> "SELL"
                else -> { ignored++; return@forEachIndexed }
            }
            fun number(value: String, allowAssetSuffix: Boolean = false, expectedAsset: String = ""): Double? {
                val raw = value.trim()
                if (raw.isEmpty()) return null
                val numeric = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
                val pattern = if (allowAssetSuffix) Regex("^($numeric)(?:\\s*([A-Za-z]{2,10}))?$") else Regex("^($numeric)$")
                val match = pattern.matchEntire(raw) ?: return null
                val suffix = match.groupValues.getOrNull(2).orEmpty()
                if (suffix.isNotEmpty() && expectedAsset.isNotBlank() &&
                    !suffix.equals(expectedAsset.trim(), ignoreCase = true)
                ) return null
                return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            val price = number(cell(priceIndex)) ?: error("Invalid price on CSV row $rowNo.")
            val qty = number(cell(qtyIndex)) ?: error("Invalid quantity on CSV row $rowNo.")
            require(price > 0 && qty > 0) { "Price and quantity must be positive on CSV row $rowNo." }
            val quoteRaw = cell(quoteIndex)
            val quote = if (quoteRaw.isBlank()) qty * price else
                (number(quoteRaw) ?: error("Invalid quote amount on CSV row $rowNo."))
            require(quote > 0 && quote.isFinite()) {
                "Quote amount must be positive and finite on CSV row $rowNo."
            }
            val feeRaw = cell(feeIndex)
            val declaredFeeAsset = cell(feeAssetIndex)
            val fee = if (feeRaw.isBlank()) 0.0 else
                number(feeRaw, allowAssetSuffix = true, expectedAsset = declaredFeeAsset)
                    ?: error("Invalid fee on CSV row $rowNo.")
            val feeAsset = declaredFeeAsset.ifBlank {
                Regex("[A-Za-z]{2,10}\\s*$").find(feeRaw)?.value?.trim()?.uppercase(Locale.ROOT) ?: ""
            }
            val realizedRaw = cell(realizedIndex)
            val realized = if (realizedRaw.isBlank()) null else
                number(realizedRaw) ?: error("Invalid realized P&L on CSV row $rowNo.")
            val exchangeTradeId = cell(tradeIdIndex)
            val fingerprintInput = if (exchangeTradeId.isNotBlank()) {
                listOf(market, symbol, exchangeTradeId).joinToString("|")
            } else {
                listOf(market, symbol, rawTime, side, price.toString(), qty.toString(), quote.toString(),
                    feeRaw, cell(realizedIndex), cell(orderIndex)).joinToString("|")
            }
            val baseFingerprint = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintInput.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            // Only an exchange execution ID proves two rows are the same fill. Identical
            // no-ID rows can be separate partial executions, so preserve each occurrence.
            if (exchangeTradeId.isNotBlank() && !seenTradeIds.add(baseFingerprint)) {
                duplicates++
                return@forEachIndexed
            }
            val occurrence = fallbackOccurrences.getOrDefault(baseFingerprint, 0)
            fallbackOccurrences[baseFingerprint] = occurrence + 1
            val fingerprint = if (exchangeTradeId.isNotBlank()) baseFingerprint else "$baseFingerprint:$occurrence"
            fills += BinanceJournalFill(
                id = "binance:csv:${if (market == "Spot") "spot" else "futures"}:$symbol:$fingerprint",
                exchange = "Binance", market = market, symbol = symbol, side = side,
                quantity = qty, price = price, quoteAmount = quote, fee = fee, feeAsset = feeAsset,
                realizedPnl = realized, time = time, orderId = cell(orderIndex)
            )
        }
        require(fills.isNotEmpty()) { "No valid trade rows were found in this CSV." }
        return BinanceFillCsvReport(fills.sortedByDescending { it.time }, duplicates, ignored)
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
