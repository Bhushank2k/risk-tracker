package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale

private data class PairAnalytics(
    val symbol: String,
    val realizedPnl: Double,
    val realizedExecutions: Int
)

@Composable
fun TradingAnalyticsScreen(fills: List<BinanceJournalFill>) {
    var periodDays by remember { mutableStateOf<Int?>(null) }
    val now = System.currentTimeMillis()
    val filtered = remember(fills, periodDays, now) {
        val start = periodDays?.let { days ->
            if (days == 1) {
                Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else now - days.toLong() * 24L * 60L * 60L * 1000L
        }
        fills.filter { start == null || it.time >= start }
    }
    val validFills = filtered.filter {
        it.quoteAmount.isFinite() && it.quoteAmount >= 0.0 &&
            it.quantity.isFinite() && it.price.isFinite()
    }
    fun quoteAsset(symbol: String): String {
        val normalized = symbol.uppercase(Locale.ROOT)
        return listOf("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "USD")
            .firstOrNull { normalized.endsWith(it) } ?: "QUOTE"
    }
    val volumeByQuoteAsset = validFills.groupBy { quoteAsset(it.symbol) }
        .mapValues { (_, rows) -> rows.sumOf { it.quoteAmount } }
        .toList().sortedByDescending { it.second }
    val volumeSummary = when (volumeByQuoteAsset.size) {
        0 -> "—"
        1 -> volumeByQuoteAsset.first().second.num() + " " + volumeByQuoteAsset.first().first
        else -> "${volumeByQuoteAsset.size} quote assets"
    }
    val realized = validFills.mapNotNull { it.realizedPnl?.takeIf(Double::isFinite) }
    val winning = realized.filter { it > 0.0 }
    val losing = realized.filter { it < 0.0 }
    val grossProfit = winning.sum()
    val grossLoss = -losing.sum()
    val netPnl = grossProfit - grossLoss
    val decisiveCount = winning.size + losing.size
    val winRate = if (decisiveCount > 0) winning.size * 100.0 / decisiveCount else null
    val lossRate = if (decisiveCount > 0) losing.size * 100.0 / decisiveCount else null
    val profitFactor = when {
        grossLoss > 0.0 -> grossProfit / grossLoss
        grossProfit > 0.0 -> Double.POSITIVE_INFINITY
        else -> null
    }
    val pairStats = validFills
        .filter { it.realizedPnl?.isFinite() == true }
        .groupBy { it.symbol.ifBlank { "Unknown pair" } }
        .map { (symbol, rows) ->
            PairAnalytics(symbol, rows.sumOf { it.realizedPnl ?: 0.0 }, rows.size)
        }
        .sortedByDescending { it.realizedPnl }
    val bestPair = pairStats.firstOrNull { it.realizedPnl > 0.0 }
    val worstPair = pairStats.lastOrNull { it.realizedPnl < 0.0 }
    val feeTotals = validFills.filter { it.fee.isFinite() && it.fee > 0.0 }
        .groupBy { it.feeAsset.ifBlank { "Unknown asset" } }
        .mapValues { (_, rows) -> rows.sumOf { it.fee } }
        .toList().sortedByDescending { it.second }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("EXCHANGE PERFORMANCE", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Trading analytics", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("Automatically calculated from saved Binance executions. No manual entries are used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Pair<String, Int?>>("Today" to 1, "7D" to 7, "30D" to 30, "90D" to 90, "All" to null)
                    .forEach { (label, days) ->
                        FilterChip(
                            selected = periodDays == days,
                            onClick = { periodDays = days },
                            label = { Text(label) }
                        )
                    }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("TOTAL EXECUTED VOLUME", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(volumeSummary, style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${validFills.size} fetched executions · executed quote amount, counted once per fill",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                }
                volumeByQuoteAsset.forEach { (asset, amount) ->
                    Text(amount.num() + " " + asset, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Profit & loss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Gross profit", grossProfit.usd(), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                    AnalyticsMetric("Gross loss", grossLoss.usd(), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Net realized P&L", (if (netPnl > 0) "+" else "") + netPnl.usd(),
                        Modifier.weight(1f), if (netPnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    AnalyticsMetric("Profit factor", profitFactor?.let { if (it.isInfinite()) "∞" else "%.2f".format(Locale.US, it) } ?: "—",
                        Modifier.weight(1f))
                }
                Text("P&L includes only exchange fills with a reported realized P&L value. Open or non-realizing executions are excluded from win/loss calculations.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Win / loss ratio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(winRate?.pct(1) ?: "—", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Win ratio", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(lossRate?.pct(1) ?: "—", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Text("Loss ratio", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (decisiveCount > 0) {
                        LinearProgressIndicator(
                            progress = { (winRate!! / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                        )
                    } else {
                        Text("No realized winning or losing executions in this period.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("$decisiveCount realized executions · wins: ${winning.size} · losses: ${losing.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Pair performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsMetric("Top profitable pair", bestPair?.symbol ?: "—", Modifier.weight(1f),
                    MaterialTheme.colorScheme.primary, bestPair?.realizedPnl?.usd() ?: "No realized profit")
                AnalyticsMetric("Highest loss pair", worstPair?.symbol ?: "—", Modifier.weight(1f),
                    MaterialTheme.colorScheme.error, worstPair?.realizedPnl?.usd() ?: "No realized loss")
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Realized P&L by pair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (pairStats.isEmpty()) {
                        Text("Pair results will appear when Binance provides realized P&L for executions.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        pairStats.take(12).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(pair.symbol, fontWeight = FontWeight.SemiBold)
                                    Text("${pair.realizedExecutions} realized executions",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text((if (pair.realizedPnl > 0) "+" else "") + pair.realizedPnl.usd(),
                                    color = if (pair.realizedPnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            if (pair != pairStats.take(12).last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trading fees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (feeTotals.isEmpty()) {
                        Text("No fee amounts available for this period.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        feeTotals.forEach { (asset, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(asset, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.8f".format(Locale.US, amount), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text("Fees are grouped by their original asset. They are not converted into USD without a verified historical conversion rate.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("Data scope: saved Binance Spot and USDⓈ-M Futures fills available on this device. Volume is the sum of executed quote amounts (buy and sell turnover), grouped by detected quote asset; no conversion to USD is assumed. Pair P&L is summed from exchange-reported realized P&L. Historical completeness depends on the symbols and date ranges successfully imported or synced.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalyticsMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    supporting: String? = null
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
