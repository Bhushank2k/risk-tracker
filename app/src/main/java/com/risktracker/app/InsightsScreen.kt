package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PerformanceSnapshot(
    val netPnl: Double,
    val winRatePercent: Double,
    val winningDays: Int,
    val losingDays: Int,
    val averagePnl: Double,
    val bestDay: Double?,
    val worstDay: Double?,
    val maxDrawdownPercent: Double,
    val tradeCount: Int
)

fun buildPerformanceSnapshot(pnlValues: List<Double>, closingBalances: List<Double>, tradeCount: Int): PerformanceSnapshot {
    val decisive = pnlValues.filter { it != 0.0 && it.isFinite() }
    val wins = decisive.count { it > 0.0 }
    var peak = 0.0
    var maxDrawdown = 0.0
    closingBalances.filter { it.isFinite() && it >= 0.0 }.forEach { balance ->
        if (balance > peak) peak = balance
        if (peak > 0.0) maxDrawdown = maxOf(maxDrawdown, (peak - balance) / peak * 100.0)
    }
    val cleanPnl = pnlValues.filter { it.isFinite() }
    return PerformanceSnapshot(
        netPnl = cleanPnl.sum(),
        winRatePercent = if (decisive.isEmpty()) 0.0 else wins * 100.0 / decisive.size,
        winningDays = cleanPnl.count { it > 0.0 },
        losingDays = cleanPnl.count { it < 0.0 },
        averagePnl = if (cleanPnl.isEmpty()) 0.0 else cleanPnl.average(),
        bestDay = cleanPnl.maxOrNull(),
        worstDay = cleanPnl.minOrNull(),
        maxDrawdownPercent = maxDrawdown,
        tradeCount = tradeCount.coerceAtLeast(0)
    )
}

private val INSIGHTS_DATE_FORMATS = listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy")

private fun parseInsightsDate(value: String): Date? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    for (pattern in INSIGHTS_DATE_FORMATS) {
        val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val parsed = format.parse(trimmed, position)
        if (parsed != null && position.index == trimmed.length) return parsed
    }
    return null
}

/** Filters daily P&L rows by local calendar days, including today. A null window means all history. */
fun filterPnlEntriesForPeriod(entries: List<PnlEntry>, days: Int?, nowMillis: Long): List<PnlEntry> {
    if (days == null) return entries
    if (days <= 0) return emptyList()

    val today = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }
    val endExclusive = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    return entries.filter { entry ->
        val time = parseInsightsDate(entry.date)?.time
        time != null && time >= start.timeInMillis && time < endExclusive.timeInMillis
    }
}

@Composable
fun InsightsScreen(entries: List<PnlEntry>, trades: List<TradeEntry>, importedPnl: List<BinanceDailyResult> = emptyList(), onNavigate: (Int) -> Unit = {}) {
    var periodDays by remember { mutableStateOf<Int?>(null) }
    val nowMillis = System.currentTimeMillis()
    val filteredEntries = remember(entries, periodDays, nowMillis) {
        filterPnlEntriesForPeriod(entries, periodDays, nowMillis)
    }
    val filteredImportedPnl = remember(importedPnl, periodDays, nowMillis) {
        if (periodDays == null) importedPnl else {
            val today = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(periodDays!! - 1)) }
            val endExclusive = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            importedPnl.filter { row ->
                val time = parseInsightsDate(row.date)?.time
                time != null && time >= start.timeInMillis && time < endExclusive.timeInMillis
            }
        }
    }
    val filteredTrades = remember(trades, periodDays, nowMillis) {
        if (periodDays == null) trades else {
            val today = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(periodDays!! - 1)) }
            val endExclusive = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            trades.filter { trade ->
                val time = parseInsightsDate(trade.date)?.time
                time != null && time >= start.timeInMillis && time < endExclusive.timeInMillis
            }
        }
    }
    val reconciledPnl = remember(filteredEntries, filteredImportedPnl, filteredTrades) {
        reconcileDailyPnl(filteredEntries, filteredImportedPnl, filteredTrades)
    }
    val resultPnl = reconciledPnl.map { it.selectedPnl }
    val manualDays = reconciledPnl.count { it.source == DailyPnlSource.MANUAL }
    val binanceDays = reconciledPnl.count { it.source == DailyPnlSource.BINANCE }
    val journalDays = reconciledPnl.count { it.source == DailyPnlSource.JOURNAL }
    val overlapCount = reconciledPnl.count { it.discrepancy != null }
    val resultBalances = if (filteredEntries.isNotEmpty()) filteredEntries.map { it.closing } else emptyList()
    val snapshot = remember(filteredEntries, filteredImportedPnl, filteredTrades, resultPnl, resultBalances, trades.size) {
        buildPerformanceSnapshot(resultPnl, resultBalances, trades.size)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PERFORMANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Your trading results", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Review recorded P&L, consistency and drawdown in one place.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(onClick = { onNavigate(14) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Trade reviews", fontWeight = FontWeight.SemiBold)
                        Text("Compare trade plans with closed-trade outcomes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(onClick = { onNavigate(13) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Advanced risk scenarios", fontWeight = FontWeight.SemiBold)
                        Text("Explore loss streaks and historical risk scenarios.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Daily P&L period", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<Pair<String, Int?>>("All" to null, "7 days" to 7, "30 days" to 30, "90 days" to 90).forEach { (label, days) ->
                        FilterChip(
                            selected = periodDays == days,
                            onClick = { periodDays = days },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    if (periodDays == null) "Showing all recorded daily results."
                    else "Showing the last $periodDays calendar days, including today. Journal trade count remains all-time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NET RECORDED P&L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(snapshot.netPnl.usd(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${snapshot.winningDays} positive days · ${snapshot.losingDays} negative days",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Source mix: $manualDays manual · $binanceDays Binance-only · $journalDays journal-only days. Binance imported P&L takes precedence on overlap, then manual daily P&L, then estimated journal P&L.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    if (overlapCount > 0) {
                        Text("$overlapCount overlapping dates are available for reconciliation. Open Dashboard to compare manual and Binance amounts.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Win rate", snapshot.winRatePercent.pct(1), Modifier.weight(1f))
                MetricCard(
                    if (resultBalances.size >= 2) "Manual balance drawdown" else "Balance drawdown",
                    if (resultBalances.size >= 2) snapshot.maxDrawdownPercent.pct(1) else "—",
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Average recorded day", snapshot.averagePnl.usd(), Modifier.weight(1f))
                MetricCard("Journal trades · all time", snapshot.tradeCount.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Best and worst results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    InsightRow("Best recorded day", snapshot.bestDay?.usd() ?: "—")
                    InsightRow("Worst recorded day", snapshot.worstDay?.usd() ?: "—")
                    InsightRow("Reconciled P&L days", reconciledPnl.size.toString())
                }
            }
        }
        item {
            if (filteredEntries.size >= 2) BalanceChart(filteredEntries)
            else Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Balance trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("The balance trend uses manually recorded balance entries. Add at least two daily records to see it; Binance and journal-only P&L remain included in the reconciled totals above.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Text(
                "P&L metrics normalize dates and select one value per day: Binance imported P&L first, manual daily entry second, then estimated closed-journal P&L. Overlaps are not added twice; compare them on Dashboard. Balance trend and percentage drawdown require at least two manual daily balance records; drawdown is not inferred from P&L-only sources. Journal trade count is all-time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InsightRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
