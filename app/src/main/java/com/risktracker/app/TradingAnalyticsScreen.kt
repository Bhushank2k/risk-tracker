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
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/** Per-pair totals. Order counts use the same grouping as the headline win/loss figures. */
private data class PairAnalytics(
    val symbol: String,
    val realizedPnl: Double,
    val closedOrders: Int,
    val wins: Int,
    val losses: Int
)

private fun plural(count: Int, one: String, many: String): String =
    "$count " + if (count == 1) one else many

private fun analyticsDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(millis))

/** Fees under one coin are shown with more decimals so small amounts do not read as zero. */
private fun feeAmount(value: Double): String =
    if (kotlin.math.abs(value) >= 1.0) "%,.2f".format(Locale.US, value) else "%.6f".format(Locale.US, value)

@Composable
fun TradingAnalyticsScreen(
    fills: List<BinanceJournalFill>,
    syncStatus: BinanceJournalSyncStatus,
    importedDailyPnl: List<BinanceDailyResult> = emptyList()
) {
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
    val coverage = validFills.minOfOrNull { it.time }?.let { first ->
        "Executions from ${analyticsDate(first)} to ${analyticsDate(validFills.maxOf { it.time })}"
    } ?: "No executions in this period"
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
        else -> plural(volumeByQuoteAsset.size, "quote asset", "quote assets")
    }
    val orderAnalytics = remember(validFills) { summarizeExchangeOrders(validFills) }
    val realizedOrders = orderAnalytics.realizedOrders
    val winning = realizedOrders.filter { it > 0.0 }
    val losing = realizedOrders.filter { it < 0.0 }
    val grossProfit = orderAnalytics.grossProfit
    val grossLoss = orderAnalytics.grossLoss
    val decisiveCount = winning.size + losing.size
    val winRate = orderAnalytics.winRatePercent
    val lossRate = if (decisiveCount > 0) losing.size * 100.0 / decisiveCount else null
    val profitFactor = orderAnalytics.profitFactor
    val pairStats = validFills
        .filter { it.realizedPnl?.isFinite() == true }
        .groupBy { it.symbol.ifBlank { "Unknown pair" } }
        .map { (symbol, rows) ->
            val orders = summarizeExchangeOrders(rows).realizedOrders
            PairAnalytics(
                symbol = symbol,
                realizedPnl = rows.sumOf { it.realizedPnl ?: 0.0 },
                closedOrders = orders.size,
                wins = orders.count { it > 0.0 },
                losses = orders.count { it < 0.0 }
            )
        }
        .sortedByDescending { it.realizedPnl }
    val bestPair = pairStats.firstOrNull { it.realizedPnl > 0.0 }
    val worstPair = pairStats.lastOrNull { it.realizedPnl < 0.0 }
    val feeTotals = validFills.filter { it.fee.isFinite() && it.fee > 0.0 }
        .groupBy { it.feeAsset.ifBlank { "Unknown asset" } }
        .mapValues { (_, rows) -> rows.sumOf { it.fee } }
        .toList().sortedByDescending { it.second }

    // Binance-only: daily results come solely from imported Binance records.
    val reconciledDaily = remember(importedDailyPnl, periodDays, now) {
        val all = reconcileDailyPnl(emptyList(), importedDailyPnl, emptyList())
        val window = filterPnlEntriesForPeriod(
            all.map { PnlEntry(it.date, 0.0, it.selectedPnl) }, periodDays, now
        ).map { it.date }.toSet()
        all.filter { it.date in window }
    }
    val dailySnapshot = remember(reconciledDaily) {
        buildPerformanceSnapshot(
            pnlValues = reconciledDaily.map { it.selectedPnl },
            closingBalances = emptyList(),
            tradeCount = 0
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Binance sync", fontWeight = FontWeight.SemiBold)
                            Text(syncStatus.state, color = if (syncStatus.state.startsWith("Up to date"))
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            if (syncStatus.lastSuccess > 0L)
                                "Last successful sync: " + SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(syncStatus.lastSuccess))
                            else "No successful automatic sync recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Background refresh runs about every 6 hours. To refresh now, use Binance Sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!syncStatus.state.startsWith("Up to date") && syncStatus.detail.isNotBlank()) {
                            Text(syncStatus.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
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
            Text(coverage, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        item {
            RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TOTAL EXECUTED VOLUME", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(volumeSummary, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${validFills.size} fetched executions · executed quote amount, counted once per fill",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                    // Only break the volume down when there is more than one quote asset.
                    if (volumeByQuoteAsset.size > 1) {
                        volumeByQuoteAsset.forEach { (asset, amount) ->
                            Text(amount.num() + " " + asset, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                        }
                    }
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
                    AnalyticsMetric("Net after USDT/USDC fees", (if (orderAnalytics.netAfterKnownStableFees > 0) "+" else "") + orderAnalytics.netAfterKnownStableFees.usd(),
                        Modifier.weight(1f), if (orderAnalytics.netAfterKnownStableFees >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        "Only stablecoin fees subtracted")
                    AnalyticsMetric("Profit factor", profitFactor?.let { if (it.isInfinite()) "∞" else "%.2f".format(Locale.US, it) } ?: "—",
                        Modifier.weight(1f))
                }
                Text("P&L includes only exchange fills with reported realized P&L. Partial fills from the same exchange order are combined before classifying a win or loss; open or non-realizing orders are excluded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Average win", orderAnalytics.averageWin?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                    AnalyticsMetric("Average loss", orderAnalytics.averageLoss?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                Text("Fees in other assets are not converted or subtracted; see Trading fees below. The net figure subtracts only reported USDT/USDC fees from reported realized P&L.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Daily performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Daily totals come from Binance's daily income records, so they can differ from the execution-level figures above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Daily net P&L", dailySnapshot.netPnl.usd(), Modifier.weight(1f),
                        if (dailySnapshot.netPnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    AnalyticsMetric("Average daily P&L", dailySnapshot.averagePnl.usd(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Best day", dailySnapshot.bestDay?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                    AnalyticsMetric("Worst day", dailySnapshot.worstDay?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                Text("${plural(reconciledDaily.size, "reconciled day", "reconciled days")} · all Binance-imported",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reconciledDaily.takeLast(7).asReversed().forEach { day ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(day.date, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text("Binance-imported", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (day.selectedPnl > 0.0) "+" else "") + day.selectedPnl.usd(),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            color = pnlColor(day.selectedPnl))
                    }
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                    Text("${plural(decisiveCount, "closed order", "closed orders")} · wins: ${winning.size} · losses: ${losing.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Sample size is small when there are few closed orders; treat rates as descriptive, not predictive.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            RiskCard(Modifier.fillMaxWidth()) {
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
                                    Text("${plural(pair.closedOrders, "closed order", "closed orders")} · " +
                                        "${plural(pair.wins, "win", "wins")} · ${plural(pair.losses, "loss", "losses")}",
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
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trading fees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (feeTotals.isEmpty()) {
                        Text("No fee amounts available for this period.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        feeTotals.forEach { (asset, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(asset, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(feeAmount(amount), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text("Fees are grouped by their original asset. They are not converted into USD without a verified historical conversion rate.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("Data scope: saved Binance Spot and USDⓈ-M Futures fills available on this device. Volume is the sum of executed quote amounts (buy and sell turnover), grouped by detected quote asset; no conversion to USD is assumed. Pair P&L is summed from exchange-reported realized P&L. Win/loss ratios aggregate partial fills by exchange order. Historical completeness depends on the symbols and date ranges successfully imported or synced.",
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
    RiskCard(modifier) {
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
