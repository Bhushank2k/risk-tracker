package com.risktracker.app

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

enum class DailyPnlSource { MANUAL, BINANCE, JOURNAL }

data class ReconciledDailyPnl(
    val date: String,
    val manualPnl: Double?,
    val binancePnl: Double?,
    val journalPnl: Double?,
    val selectedPnl: Double,
    val source: DailyPnlSource,
    val discrepancy: Double?
)

/** Normalizes supported ledger date formats to ISO dates so the same day cannot be counted twice. */
fun canonicalPnlDate(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    for (pattern in listOf("yyyy-MM-dd", "d/M/yyyy", "dd/MM/yyyy", "M/d/yyyy")) {
        val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val date = parser.parse(trimmed, position)
        if (date != null && position.index == trimmed.length) {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        }
    }
    return null
}

/**
 * Creates one P&L row per calendar day. Binance history takes precedence when available; manual records are fallback values;
 * journal trades fill dates absent from both.
 * Overlap discrepancies are retained for transparency rather than silently added together.
 */
fun reconcileDailyPnl(
    entries: List<PnlEntry>,
    imported: List<BinanceDailyResult>,
    trades: List<TradeEntry>
): List<ReconciledDailyPnl> {
    val manualByDate = entries.mapNotNull { row ->
        canonicalPnlDate(row.date)?.let { it to row.pnl }
    }.toMap()
    val binanceByDate = imported.mapNotNull { row ->
        canonicalPnlDate(row.date)?.let { it to row.total }
    }.toMap()
    val journalByDate = trades.mapNotNull { trade ->
        val pnl = trade.pnl
        if (!pnl.isFinite()) null else canonicalPnlDate(trade.date)?.let { it to pnl }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.sum() }

    return (manualByDate.keys + binanceByDate.keys + journalByDate.keys).sorted().map { date ->
        val manual = manualByDate[date]
        val binance = binanceByDate[date]
        val journal = journalByDate[date]
        val source = when {
            binance != null -> DailyPnlSource.BINANCE
            manual != null -> DailyPnlSource.MANUAL
            else -> DailyPnlSource.JOURNAL
        }
        ReconciledDailyPnl(
            date = date,
            manualPnl = manual,
            binancePnl = binance,
            journalPnl = journal,
            selectedPnl = when (source) {
                DailyPnlSource.MANUAL -> manual!!
                DailyPnlSource.BINANCE -> binance!!
                DailyPnlSource.JOURNAL -> journal ?: 0.0
            },
            source = source,
            discrepancy = if (manual != null && binance != null) kotlin.math.abs(manual - binance) else null
        )
    }
}
