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

const val SPOT_ESTIMATE_DEFAULT_ENABLED = false

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

data class CryptoTaxImportIssue(
    val fileName: String,
    val rowNumber: Int?,
    val reason: String,
    val sample: String
)

data class CryptoTaxCsvResult(
    val trades: List<HistoricalCryptoTrade>,
    val issues: List<CryptoTaxImportIssue>
)

data class CryptoTaxImportPreview(
    val trades: List<HistoricalCryptoTrade>,
    val newRecordCount: Int,
    val duplicateRecordCount: Int,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    val issues: List<CryptoTaxImportIssue> = emptyList()
)

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

data class CostBasisReview(
    val estimatedMatchedCostInr: Double,
    val unmatchedSellQuantity: Double,
    val sellRowsWithoutPriorBuys: Int,
    val possibleDuplicateRows: Int,
    val warnings: List<String>
)

object CryptoTaxHistory {
    private const val PREFS = "crypto_tax_history_v1"
    private const val KEY = "trades_json"

    fun load(context: Context): List<HistoricalCryptoTrade> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val imported = (0 until arr.length()).mapNotNull { i ->
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
        val journal = BinanceAutoJournalStore.load(context).map { fill ->
            HistoricalCryptoTrade(
                id = fill.id, timestamp = fill.time, exchange = fill.exchange,
                market = if (fill.market.contains("Futures", true)) "Futures" else "Spot",
                symbol = fill.symbol, side = fill.side, quantity = fill.quantity,
                price = fill.price, quoteAmount = fill.quoteAmount, fee = fill.fee,
                feeAsset = fill.feeAsset, realizedPnl = fill.realizedPnl,
                source = if (fill.id.contains(":csv:")) "Journal CSV import" else "Journal API sync"
            )
        }
        return mergeJournalHistory(imported, journal)
    }

    /** Adds saved API/CSV journal fills to the report without collapsing separate same-source partial fills. */
    internal fun mergeJournalHistory(
        imported: List<HistoricalCryptoTrade>,
        journal: List<HistoricalCryptoTrade>
    ): List<HistoricalCryptoTrade> {
        val merged = imported.toMutableList()
        journal.forEach { fill ->
            val exact = merged.indexOfFirst { it.id == fill.id }
            if (exact >= 0) {
                merged[exact] = fill
            } else {
                val matchingImported = merged.indexOfFirst { saved ->
                    !saved.source.startsWith("Journal ") &&
                        sameHistoricalExecution(saved, fill)
                }
                if (matchingImported >= 0) {
                    if (fill.source == "Binance API sync") merged[matchingImported] = fill
                } else {
                    merged += fill
                }
            }
        }
        return merged.sortedBy { it.timestamp }
    }

    private fun sameHistoricalExecution(a: HistoricalCryptoTrade, b: HistoricalCryptoTrade): Boolean {
        val marketA = if (a.market.contains("Futures", true)) "Futures" else "Spot"
        val marketB = if (b.market.contains("Futures", true)) "Futures" else "Spot"
        if (marketA != marketB || a.symbol != b.symbol || a.side != b.side) return false
        return kotlin.math.abs(a.timestamp - b.timestamp) <= 1_000L &&
            kotlin.math.abs(a.quantity - b.quantity) <= maxOf(1e-10, kotlin.math.abs(a.quantity) * 1e-8) &&
            kotlin.math.abs(a.price - b.price) <= maxOf(1e-8, kotlin.math.abs(a.price) * 1e-8)
    }

    /** Makes a confirmed tax-report CSV import visible in the shared journal and P&L calendar. */
    fun mergeIntoJournal(context: Context, trades: List<HistoricalCryptoTrade>): Int {
        val fallbackOccurrences = mutableMapOf<String, Int>()
        val fills = trades.map { trade ->
            val market = if (trade.market.contains("Futures", true)) "USDⓈ-M Futures" else "Spot"
            val id = if (trade.id.startsWith("binance:")) trade.id else {
                val occurrence = fallbackOccurrences.getOrDefault(trade.id, 0)
                fallbackOccurrences[trade.id] = occurrence + 1
                "binance:csv:tax:${trade.id}:$occurrence"
            }
            BinanceJournalFill(
                id = id, exchange = trade.exchange, market = market, symbol = trade.symbol,
                side = trade.side, quantity = trade.quantity, price = trade.price,
                quoteAmount = trade.quoteAmount, fee = trade.fee, feeAsset = trade.feeAsset,
                realizedPnl = trade.realizedPnl, time = trade.timestamp, orderId = ""
            )
        }
        // Re-importing the same no-ID CSV through the second importer should not add a second copy.
        val existingCsv = BinanceAutoJournalStore.load(context).filter { it.id.contains(":csv:") }
        val consumed = mutableSetOf<String>()
        val additions = fills.filterNot { incoming ->
            val duplicate = existingCsv.firstOrNull { saved ->
                saved.id !in consumed && saved.market == incoming.market &&
                    saved.symbol == incoming.symbol && saved.side == incoming.side &&
                    kotlin.math.abs(saved.time - incoming.time) <= 1_000L &&
                    kotlin.math.abs(saved.quantity - incoming.quantity) <= maxOf(1e-10, kotlin.math.abs(saved.quantity) * 1e-8) &&
                    kotlin.math.abs(saved.price - incoming.price) <= maxOf(1e-8, kotlin.math.abs(saved.price) * 1e-8)
            }
            if (duplicate != null) consumed += duplicate.id
            duplicate != null
        }
        return BinanceAutoJournalStore.merge(context, additions)
    }

    fun clear(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Could not clear imported crypto trade history."
        }
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

    /** Parses Binance-style CSV files and retains diagnostics for rows that cannot be imported. */
    fun parseCsv(text: String, source: String): List<HistoricalCryptoTrade> =
        parseCsvWithDiagnostics(text, source).trades

    fun parseCsvWithDiagnostics(text: String, source: String): CryptoTaxCsvResult {
        val lines = text.lineSequence().toList()
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerIndex < 0 || lines.drop(headerIndex + 1).none { it.isNotBlank() }) return CryptoTaxCsvResult(emptyList(), listOf(
            CryptoTaxImportIssue(source, null, "File has no transaction rows.", "")
        ))
        val header = csvRow(lines[headerIndex]).map { it.trim().lowercase(Locale.US).replace(" ", "").replace("_", "") }
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
        val parsed = mutableListOf<HistoricalCryptoTrade>()
        val issues = mutableListOf<CryptoTaxImportIssue>()
        lines.drop(headerIndex + 1).forEachIndexed { rowNo, line ->
            if (line.isBlank()) return@forEachIndexed
            val c = csvRow(line)
            fun value(i: Int) = if (i in c.indices) c[i].trim() else ""
            val rowNumber = headerIndex + rowNo + 2
            val timestamp = parseTimestamp(value(timeIdx))
            if (timestamp == null) {
                issues += CryptoTaxImportIssue(source, rowNumber, "Missing or unrecognized date/time.", line.take(180))
                return@forEachIndexed
            }
            val symbol = value(symbolIdx).uppercase(Locale.US)
            if (symbol.isBlank()) {
                issues += CryptoTaxImportIssue(source, rowNumber, "Missing trading symbol/pair.", line.take(180))
                return@forEachIndexed
            }
            val qty = value(qtyIdx).replace(",", "").toDoubleOrNull() ?: 0.0
            val price = value(priceIdx).replace(",", "").toDoubleOrNull() ?: 0.0
            val quote = value(quoteIdx).replace(",", "").toDoubleOrNull().takeIf { it != null && it > 0 } ?: qty * price
            val rawFee = value(feeIdx).replace(",", "")
            val declaredFeeAsset = value(feeAssetIdx).uppercase(Locale.US)
            val feePattern = Regex("^([+-]?(?:[0-9]+(?:\\\\.[0-9]*)?|\\\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(?:\\\\s*([A-Za-z]{2,10}))?$")
            val feeMatch = if (rawFee.isBlank()) null else feePattern.matchEntire(rawFee)
            if (rawFee.isNotBlank() && feeMatch == null) {
                issues += CryptoTaxImportIssue(source, rowNumber, "Invalid fee amount.", line.take(180))
                return@forEachIndexed
            }
            val attachedFeeAsset = feeMatch?.groupValues?.getOrNull(2).orEmpty().uppercase(Locale.US)
            if (attachedFeeAsset.isNotBlank() && declaredFeeAsset.isNotBlank() &&
                attachedFeeAsset != declaredFeeAsset
            ) {
                issues += CryptoTaxImportIssue(source, rowNumber, "Attached fee asset conflicts with Fee Asset column.", line.take(180))
                return@forEachIndexed
            }
            val fee = feeMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val sideRaw = value(sideIdx).uppercase(Locale.US)
            val side = when { sideRaw.contains("BUY") -> "BUY"; sideRaw.contains("SELL") -> "SELL"; else -> sideRaw.ifBlank { "UNKNOWN" } }
            val pnl = value(pnlIdx).replace(",", "").toDoubleOrNull()
            val feeAsset = declaredFeeAsset.ifBlank { attachedFeeAsset }
            val market = if (source.contains("futures", true)) "Futures" else "Spot"
            val exchangeTradeId = value(tradeIdIdx)
            val identity = if (exchangeTradeId.isNotBlank()) {
                "Binance|$market|$symbol|$exchangeTradeId"
            } else {
                listOf("Binance", market, timestamp.toString(), symbol, side, qty.toString(),
                    price.toString(), quote.toString(), fee.toString(), feeAsset).joinToString("|")
            }
            val stableId = if (exchangeTradeId.isNotBlank()) {
                "binance:${if (market == "Futures") "futures" else "spot"}:$symbol:$exchangeTradeId"
            } else "csv:" + sha256(identity)
            parsed += HistoricalCryptoTrade(
                id = stableId,
                timestamp = timestamp, exchange = "Binance", market = market,
                symbol = symbol, side = side, quantity = qty, price = price, quoteAmount = quote,
                fee = fee, feeAsset = feeAsset, realizedPnl = pnl, source = source
            )
        }
        return CryptoTaxCsvResult(parsed, issues)
    }

    /**
     * Parse an import without changing stored history. The caller must explicitly confirm
     * before merging the returned records into the saved ledger.
     */
    fun previewUri(context: Context, uri: Uri, existing: List<HistoricalCryptoTrade>): CryptoTaxImportPreview {
        val result = importUriWithDiagnostics(context, uri)
        val incoming = result.trades
        val (_, added) = deduplicate(existing, incoming)
        return CryptoTaxImportPreview(
            trades = incoming,
            newRecordCount = added,
            duplicateRecordCount = incoming.size - added,
            firstTimestamp = incoming.minOfOrNull { it.timestamp },
            lastTimestamp = incoming.maxOfOrNull { it.timestamp },
            issues = result.issues
        )
    }

    fun importUri(context: Context, uri: Uri): List<HistoricalCryptoTrade> =
        importUriWithDiagnostics(context, uri).trades

    fun importUriWithDiagnostics(context: Context, uri: Uri): CryptoTaxCsvResult {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "import.csv"
        } ?: "import.csv"
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open selected file.")
        val imported = mutableListOf<HistoricalCryptoTrade>()
        val issues = mutableListOf<CryptoTaxImportIssue>()
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
                            val entryName = entry.name
                            val result = runCatching {
                                parseCsvWithDiagnostics(bytes.toString(Charsets.UTF_8.name()), entryName)
                            }.getOrElse { error ->
                                CryptoTaxCsvResult(emptyList(), listOf(CryptoTaxImportIssue(
                                    entryName, null, error.message ?: "Could not parse CSV file.", ""
                                )))
                            }
                            imported += result.trades
                            issues += result.issues
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } else {
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                val result = parseCsvWithDiagnostics(text, name)
                imported += result.trades
                issues += result.issues
            }
        }
        if (imported.isEmpty() && issues.isEmpty()) throw IllegalArgumentException(
            "No CSV transaction files were found. Export Binance trade history as CSV or ZIP."
        )
        return CryptoTaxCsvResult(imported, issues)
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
        val penalty = if (trades.isEmpty()) 100.0 else (
            missingPnl * 35.0 / trades.size +
            unknownSide * 25.0 / trades.size +
            invalidAmounts * 25.0 / trades.size +
            missingFeeAsset * 15.0 / trades.size
        )
        return TaxDataQuality((100.0 - penalty).toInt().coerceIn(0, 100), trades.size, missingPnl, unknownSide, invalidAmounts, missingFeeAsset, warnings)
    }

    /**
     * Review-only FIFO matching within each exact symbol string. Assumes BUY/SELL quantity units
     * are consistent and quoteAmount is in the currency represented by the manually entered rate.
     * It deliberately does not claim to calculate Indian taxable VDA income.
     */
    fun costBasisReview(trades: List<HistoricalCryptoTrade>, inrPerQuote: Double): CostBasisReview {
        require(inrPerQuote > 0.0 && inrPerQuote.isFinite()) { "Enter a valid INR conversion rate." }
        data class Lot(var remaining: Double, val unitCostInr: Double)
        val lots = mutableMapOf<String, MutableList<Lot>>()
        var matchedCost = 0.0
        var unmatchedQty = 0.0
        var unmatchedRows = 0
        trades.sortedBy { it.timestamp }.forEach { trade ->
            if (trade.side == "BUY" && trade.quantity > 0.0 && trade.quantity.isFinite() &&
                trade.quoteAmount >= 0.0 && trade.quoteAmount.isFinite()) {
                val unitCost = trade.quoteAmount * inrPerQuote / trade.quantity
                lots.getOrPut(trade.symbol) { mutableListOf() }.add(Lot(trade.quantity, unitCost))
            } else if (trade.side == "SELL" && trade.quantity > 0.0 && trade.quantity.isFinite()) {
                var needed = trade.quantity
                val symbolLots = lots.getOrPut(trade.symbol) { mutableListOf() }
                for (lot in symbolLots) {
                    if (needed <= 1e-10) break
                    if (lot.remaining <= 1e-10) continue
                    val used = minOf(needed, lot.remaining)
                    matchedCost += used * lot.unitCostInr
                    lot.remaining -= used
                    needed -= used
                }
                if (needed > 1e-8) {
                    unmatchedQty += needed
                    unmatchedRows++
                }
            }
        }
        val duplicateCount = trades.groupBy {
            listOf(it.timestamp, it.exchange, it.market, it.symbol, it.side,
                it.quantity.toString(), it.price.toString(), it.quoteAmount.toString(),
                it.fee.toString(), it.feeAsset.uppercase(Locale.US)).joinToString("|")
        }.values.sumOf { group -> (group.size - 1).coerceAtLeast(0) }
        val warnings = buildList {
            if (unmatchedRows > 0) add("$unmatchedRows sell rows have quantity with no earlier imported BUY lot; import earlier history or review transfers.")
            if (duplicateCount > 0) add("$duplicateCount possible duplicate rows share matching trade details. Review them; records were not deleted.")
            add("FIFO matching is an indicative data review only, not a determination of Indian tax cost basis.")
            add("The conversion rate is a single manual estimate and is applied to all quote amounts.")
        }
        return CostBasisReview(matchedCost, unmatchedQty, unmatchedRows, duplicateCount, warnings)
    }

    /**
     * Content for a printable review report. Labels assumptions and missing values explicitly;
     * this output is not a determination of taxable income or filing-ready tax advice.
     */
    fun printableReportLines(trades: List<HistoricalCryptoTrade>, inrPerQuote: Double): List<String> {
        val yearly = summaries(trades, inrPerQuote)
        val quality = dataQuality(trades)
        val costReview = costBasisReview(trades, inrPerQuote)
        fun n(value: Double) = String.format(Locale.US, "%.8f", value)
        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        return buildList {
            add("RISK TRACKER — INDIA CRYPTO TAX REVIEW")
            add("Review aid only. Not a tax-filing determination. Not tax advice.")
            add("FX assumption: one manual INR-per-quote-currency rate of ${n(inrPerQuote)} applied to every row.")
            add("Exchange-reported realized P&L is not automatically the taxable VDA income.")
            add("Imported records: ${trades.size}")
            add("Data-quality heuristic: ${quality.score}/100 (not a tax-accuracy guarantee)")
            quality.warnings.forEach { add("Data warning: $it") }
            add(" ")
            add("YEAR-WISE SUMMARY (Indian financial year: 1 April–31 March)")
            if (yearly.isEmpty()) add("No historical trade records imported.")
            yearly.forEach { row ->
                add("FY ${row.year}-${(row.year + 1).toString().takeLast(2)}: ${row.trades} records")
                add("Known exchange-reported realized P&L (INR): ${n(row.realizedPnlInr)}")
                add("Recorded fees (INR): ${n(row.feesInr)}")
                add("Sell proceeds (INR): ${n(row.proceedsInr)}")
                add("Buy value, not tax cost basis (INR): ${n(row.costBasisInr)}")
                add("Sell rows missing realized P&L: ${row.missingPnlRecords}")
            }
            add(" ")
            add("INDICATIVE FIFO / COST-BASIS REVIEW")
            add("Matched historical buy cost estimate (INR): ${n(costReview.estimatedMatchedCostInr)}")
            add("Sell quantity without earlier imported buys: ${n(costReview.unmatchedSellQuantity)}")
            add("Sell rows needing earlier-history review: ${costReview.sellRowsWithoutPriorBuys}")
            add("Possible duplicate rows: ${costReview.possibleDuplicateRows}")
            costReview.warnings.forEach { add("Cost-basis warning: $it") }
            add(" ")
            add("TRANSACTION DETAILS")
            add("Timestamp UTC | Exchange / Market | Symbol | Side | Quantity | Price | Quote amount | Fee / asset | Realized P&L | Source")
            trades.sortedBy { it.timestamp }.forEach { trade ->
                val pnl = trade.realizedPnl?.let(::n) ?: "MISSING"
                add("${utcFormat.format(java.util.Date(trade.timestamp))} | ${trade.exchange} / ${trade.market} | ${trade.symbol} | ${trade.side} | qty ${n(trade.quantity)} | price ${n(trade.price)} | quote ${n(trade.quoteAmount)} | fee ${n(trade.fee)} ${trade.feeAsset.ifBlank { "ASSET UNKNOWN" }} | realized P&L $pnl | source ${trade.source}")
            }
            add(" ")
            add("Verify the original exchange statements, transaction coverage, fee assets, transfers, and date-specific INR values with a qualified tax professional.")
        }
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

    /**
     * Merge imports as a multiset, not a set. Identical executions can legitimately share a
     * fallback fingerprint when the exchange export omits trade IDs (or timestamps are rounded).
     * Keeping the maximum observed multiplicity makes repeated imports idempotent without
     * collapsing repeated identical rows within a single export or ZIP bundle.
     */
    fun deduplicate(existing: List<HistoricalCryptoTrade>, incoming: List<HistoricalCryptoTrade>): Pair<List<HistoricalCryptoTrade>, Int> {
        val existingCounts = existing.groupingBy { it.id }.eachCount()
        val incomingCounts = mutableMapOf<String, Int>()
        val fresh = mutableListOf<HistoricalCryptoTrade>()
        incoming.forEach { trade ->
            val occurrence = (incomingCounts[trade.id] ?: 0) + 1
            incomingCounts[trade.id] = occurrence
            if (occurrence > (existingCounts[trade.id] ?: 0)) fresh += trade
        }
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
