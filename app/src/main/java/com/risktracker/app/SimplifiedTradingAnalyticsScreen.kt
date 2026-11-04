package com.risktracker.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun SimplifiedTradingAnalyticsScreen(
    fills: List<BinanceJournalFill>,
    syncStatus: BinanceJournalSyncStatus,
    dailyEntries: List<PnlEntry> = emptyList(),
    manualTrades: List<TradeEntry> = emptyList(),
    importedDailyPnl: List<BinanceDailyResult> = emptyList()
) {
    var periodDays by remember { mutableStateOf<Int?>(30) }
    var showDetailedAnalytics by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val start = remember(periodDays, now) {
        periodDays?.let { days -> now - days.toLong() * 86_400_000L }
    }
    val visibleFills = remember(fills, start) {
        fills.filter { (start == null || it.time >= start) && it.time <= now }
            .filter { it.quoteAmount.isFinite() && it.quoteAmount >= 0.0 &&
                it.quantity.isFinite() && it.price.isFinite() }
    }
    val orderStats = remember(visibleFills) { summarizeExchangeOrders(visibleFills) }
    val wins = orderStats.realizedOrders.count { it > 0.0 }
    val losses = orderStats.realizedOrders.count { it < 0.0 }
    val netPnl = orderStats.grossProfit - orderStats.grossLoss
    val quoteVolume = visibleFills.sumOf { it.quoteAmount }
    val quoteAssets = visibleFills.map { fill ->
        listOf("USDT", "USDC", "BUSD", "FDUSD", "BTC", "ETH", "BNB", "EUR", "USD")
            .firstOrNull { fill.symbol.uppercase(Locale.ROOT).endsWith(it) } ?: "mixed"
    }.distinct()
    val volumeLabel = if (quoteAssets.size == 1) quoteVolume.num() + " " + quoteAssets.first()
        else if (quoteAssets.isEmpty()) "—" else "Multiple assets"

    if (showDetailedAnalytics) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDetailedAnalytics = false }) {
                    Text("← Simple overview")
                }
            }
            Box(Modifier.weight(1f)) {
                TradingAnalyticsScreen(fills, syncStatus, dailyEntries, manualTrades, importedDailyPnl)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("A clear snapshot of your exchange performance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Binance sync", fontWeight = FontWeight.SemiBold)
                        Text(syncStatus.state, style = MaterialTheme.typography.labelMedium,
                            color = if (syncStatus.state.startsWith("Up to date"))
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (syncStatus.lastSuccess > 0L) "Last successful sync is available"
                        else "No successful automatic sync recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<String, Int?>>("7D" to 7, "30D" to 30, "90D" to 90, "All" to null)
                    .forEach { (label, days) ->
                        FilterChip(selected = periodDays == days, onClick = { periodDays = days },
                            label = { Text(label) })
                    }
            }
            Text("Performance snapshot", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsSummaryMetric("Realized P&L", netPnl.usd(), Modifier.weight(1f),
                    if (netPnl >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                AnalyticsSummaryMetric("Win rate", orderStats.winRatePercent?.pct(1) ?: "—",
                    Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsSummaryMetric("Winning orders", wins.toString(), Modifier.weight(1f))
                AnalyticsSummaryMetric("Losing orders", losses.toString(), Modifier.weight(1f),
                    MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsSummaryMetric("Profit factor",
                    orderStats.profitFactor?.let { if (it.isInfinite()) "∞" else "%.2f".format(Locale.US, it) } ?: "—",
                    Modifier.weight(1f))
                AnalyticsSummaryMetric("Average win", orderStats.averageWin?.usd() ?: "—",
                    Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsSummaryMetric("Average loss", orderStats.averageLoss?.usd() ?: "—",
                    Modifier.weight(1f), MaterialTheme.colorScheme.error)
                AnalyticsSummaryMetric("Net after stable fees", orderStats.netAfterKnownStableFees.usd(),
                    Modifier.weight(1f),
                    if (orderStats.netAfterKnownStableFees >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Executed volume", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(volumeLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${visibleFills.size} saved executions · quote amounts are not converted between assets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What these numbers mean", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text("P&L and win rate use exchange-reported realized results. Open positions and orders without realized P&L are excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Wins and losses are grouped by exchange order, so partial fills are not counted as separate trades.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { showDetailedAnalytics = true }, Modifier.fillMaxWidth()) {
                Text("Explore detailed analytics")
            }
        }
    }
}

@Composable
private fun AnalyticsSummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    RiskCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}
