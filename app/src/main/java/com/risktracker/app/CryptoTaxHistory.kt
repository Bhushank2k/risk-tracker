package com.risktracker.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Imported exchange records are kept separately from manual journal entries.
 * This is a transaction summary aid, not a substitute for a tax professional's determination.
 */
data class HistoricalCryptoTrade(
    val id: String,
    val timestamp: Long,
    val exchange: String,
    val market: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val quoteAmount: Double,
    val fee: Double,
    val feeAsset: String,
    val realizedPnl: Double?,
    val source: String
) {
    /** Indian financial year start (e.g. 2025 represents FY 2025-26). */
    val year: Int get() {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = timestamp }
        val y = calendar.get(java.util.Calendar.YEAR)
        return if (calendar.get(java.util.Calendar.MONTH) >= java.util.Calendar.APRIL) y else y - 1
    }
}

data class TaxDataQuality(
    val score: Int,
    val transactionCount: Int,
    val missingRealizedPnl: Int,
    val unknownSide: Int,
    val invalidQuantityOrPrice: Int,
    val missingFeeAsset: Int,
    val warnings: List<String>
)

data class IndiaTaxYearSummary(
    /** Financial-year start year: 2025 means FY 2025-26. */
    val year: Int,
    val trades: Int,
    val realizedPnlInr: Double,
    val feesInr: Double,
    val proceedsInr: Double,
    val costBasisInr: Double,
    val missingPnlRecords: Int
)

object CryptoTaxHistory {
    private const val PREFS = "crypto_tax_history_v1"
    private const val KEY = "trades_json"

    fun load(context: Context): List<HistoricalCryptoTrade> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoricalCryptoTrade(
                    id = o.getString("id"), timestamp = o.getLong("timestamp"),
                    exchange = o.optString("exchange", "Binance"), market = o.optString("market", "Spot"),
                    symbol = o.optString("symbol"), side = o.optString("side"),
                    quantity = o.optDouble("quantity"), price = o.optDouble("price"),
                    quoteAmount = o.optDouble("quoteAmount"), fee = o.optDouble("fee"),
                    feeAsset = o.optString("feeAsset"), realizedPnl = if (o.isNull("realizedPnl")) null else o.optDouble("realizedPnl"),
                    source = o.optString("source")
                )
            }.getOrNull()
        }.sortedBy { it.timestamp }
    }

    fun save(context: Context, trades: List<HistoricalCryptoTrade>) {
        val arr = JSONArray()
        trades.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("timestamp", t.timestamp).put("exchange", t.exchange)
                .put("market", t.market).put("symbol", t.symbol).put("side", t.side)
                .put("quantity", t.quantity).put("price", t.price).put("quoteAmount", t.quoteAmount)
                .put("fee", t.fee).put("feeAsset", t.feeAsset).put("realizedPnl", t.realizedPnl ?: JSONObject.NULL)
                .put("source", t.source))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Parses Binance-style CSV files by header names, not column positions. */
    fun parseCsv(text: String, source: String): List<HistoricalCryptoTrade> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val header = csvRow(lines.first()).map { it.trim().lowercase(Locale.US).replace(" ", "").replace("_", "") }
        fun index(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n.lowercase(Locale.US).replace(" ", "").replace("_", "")).takeIf { it >= 0 } } ?: -1
        val timeIdx = index("time", "timestamp", "datetime", "date(utc)", "date")
        val symbolIdx = index("symbol", "pair", "market")
        val sideIdx = index("side", "type", "order side")
        val qtyIdx = index("executed", "qty", "quantity", "amount", "baseasset")
        val priceIdx = index("price", "averageprice")
        val quoteIdx = index("quoteqty", "quoteamount", "total", "funds")
        val feeIdx = index("fee", "commission")
        val feeAssetIdx = index("feeasset", "commissionasset")
        val pnlIdx = index("realizedpnl", "realizedprofit", "pnl")
        val tradeIdIdx = index("tradeid", "id", "executionid")
        if (timeIdx < 0 || symbolIdx < 0) throw IllegalArgumentException("CSV needs date/time and symbol/pair columns. Choose a Binance trade-history export.")
        return lines.drop(1).mapIndexedNotNull { rowNo, line ->
            val c = csvRow(line)
            fun value(i: Int) = if (i in c.indices) c[i].trim() else ""
            val timestamp = parseTimestamp(value(timeIdx)) ?: return@mapIndexedNotNull null
            val symbol = value(symbolIdx).uppercase(Locale.US)
            if (symbol.isBlank()) return@mapIndexedNotNull null
            val qty = value(qtyIdx).replace(",", "").toDoubleOrNull() ?: 0.0
            val price = value(priceIdx).replace(",", "").toDoubleOrNull() ?: 0.0
            val quote = value(quoteIdx).replace(",", "").toDoubleOrNull().takeIf { it != null && it > 0 } ?: qty * price
            val fee = value(feeIdx).replace(",", "").toDoubleOrNull() ?: 0.0
            val sideRaw = value(sideIdx).uppercase(Locale.US)
            val side = when { sideRaw.contains("BUY") -> "BUY"; sideRaw.contains("SELL") -> "SELL"; else -> sideRaw.ifBlank { "UNKNOWN" } }
            val pnl = value(pnlIdx).replace(",", "").toDoubleOrNull()
            HistoricalCryptoTrade(
                id = "csv:" + sha256("$source|$timestamp|$symbol|$side|$qty|$price|$quote|$fee|$rowNo"),
                timestamp = timestamp, exchange = "Binance", market = if (source.contains("futures", true)) "Futures" else "Spot",
                symbol = symbol, side = side, quantity = qty, price = price, quoteAmount = quote,
                fee = fee, feeAsset = value(feeAssetIdx), realizedPnl = pnl, source = source
            )
        }
    }

    fun importUri(context: Context, uri: Uri): List<HistoricalCryptoTrade> {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "import.csv"
        } ?: "import.csv"
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open selected file.")
        val imported = mutableListOf<HistoricalCryptoTrade>()
        input.use { stream ->
            if (name.endsWith(".zip", true)) {
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".csv", true)) {
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count <= 0) break
                                bytes.write(buffer, 0, count)
                            }
                            val text = bytes.toString(Charsets.UTF_8.name())
                            runCatching { imported += parseCsv(text, entry.name) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } else {
                imported += parseCsv(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText(), name)
            }
        }
        if (imported.isEmpty()) throw IllegalArgumentException("No supported trade rows found. Export Binance trade history as CSV or ZIP.")
        return imported
    }

    /**
     * Heuristic data-quality indicator only; it is not a legal/tax correctness score.
     * Flags records that need review before a tax estimate can be relied on.
     */
    fun dataQuality(trades: List<HistoricalCryptoTrade>): TaxDataQuality {
        val missingPnl = trades.count { it.side == "SELL" && it.realizedPnl == null }
        val unknownSide = trades.count { it.side !in setOf("BUY", "SELL") }
        val invalidAmounts = trades.count { !it.quantity.isFinite() || it.quantity <= 0.0 || !it.price.isFinite() || it.price <= 0.0 }
        val missingFeeAsset = trades.count { it.fee > 0.0 && it.feeAsset.isBlank() }
        val warnings = buildList {
            if (missingPnl > 0) add("$missingPnl records have no exchange-reported realized P&L; do not treat them as zero.")
            if (unknownSide > 0) add("$unknownSide records have an unrecognized buy/sell side.")
            if (invalidAmounts > 0) add("$invalidAmounts records have missing or non-positive quantity/price.")
            if (missingFeeAsset > 0) add("$missingFeeAsset records have fees but no fee asset.")
            if (trades.isEmpty()) add("Import transaction history to assess data completeness.")
        }
        val penalty = if (trades.isEmpty()) 100 else (
            missingPnl * 35.0 / trades.size +
            unknownSide * 25.0 / trades.size +
            invalidAmounts * 25.0 / trades.size +
            missingFeeAsset * 15.0 / trades.size
        )
        return TaxDataQuality((100.0 - penalty).toInt().coerceIn(0, 100), trades.size, missingPnl, unknownSide, invalidAmounts, missingFeeAsset, warnings)
    }

    /** All money amounts are explicitly converted to INR by the caller; no implicit USD/USDT=INR assumption. */
    fun summaries(trades: List<HistoricalCryptoTrade>, inrPerUsdt: Double): List<IndiaTaxYearSummary> {
        require(inrPerUsdt > 0.0 && inrPerUsdt.isFinite()) { "Enter a valid INR per USDT conversion rate." }
        return trades.groupBy { it.year }.toSortedMap().map { (year, rows) ->
            val knownPnl = rows.mapNotNull { it.realizedPnl }
            IndiaTaxYearSummary(year, rows.size, knownPnl.sum() * inrPerUsdt,
                rows.sumOf { it.fee } * inrPerUsdt, rows.filter { it.side == "SELL" }.sumOf { it.quoteAmount } * inrPerUsdt,
                rows.filter { it.side == "BUY" }.sumOf { it.quoteAmount } * inrPerUsdt, rows.count { it.side == "SELL" && it.realizedPnl == null })
        }
    }

    fun deduplicate(existing: List<HistoricalCryptoTrade>, incoming: List<HistoricalCryptoTrade>): Pair<List<HistoricalCryptoTrade>, Int> {
        val seen = existing.map { it.id }.toHashSet()
        val fresh = incoming.filter { seen.add(it.id) }
        return (existing + fresh).sortedBy { it.timestamp } to fresh.size
    }

    private fun parseTimestamp(raw: String): Long? {
        raw.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000L else it }
        val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")
        for (pattern in formats) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun csvRow(line: String): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += cell.toString(); cell.setLength(0) }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun sha256(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
