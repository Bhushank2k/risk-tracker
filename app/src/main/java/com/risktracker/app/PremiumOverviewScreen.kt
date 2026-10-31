package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DashboardGreen = Color(0xFF16865D)
private val DashboardRed = Color(0xFFD34B55)

@Composable
fun PremiumOverviewScreen(
    entries: List<PnlEntry>,
    importedPnl: List<BinanceDailyResult>,
    trades: List<TradeEntry>,
    maxDailyLoss: Double,
    onNavigate: (Int) -> Unit,
    onChange: (List<PnlEntry>) -> Unit
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(BinanceAccountSnapshotStore(context).load()) }
    var balanceRefreshing by remember { mutableStateOf(false) }
    var balanceStatus by remember { mutableStateOf("") }
    val hasBinanceCredentials = remember { BinanceCredentialStore(context).read() != null }
    val balanceScope = rememberCoroutineScope()
    fun refreshAccountBalance() {
        if (balanceRefreshing) return
        if (!hasBinanceCredentials) {
            balanceStatus = "Connect Binance read-only API credentials in Binance Sync."
            return
        }
        balanceRefreshing = true
        balanceStatus = "Refreshing Binance account balance…"
        balanceScope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { BinanceAccountSnapshotRunner.refresh(context) }
                balanceStatus = "Updated from Binance"
            } catch (_: Exception) {
                balanceStatus = "Refresh failed · showing last saved balance"
            } finally { balanceRefreshing = false }
        }
    }
    LaunchedEffect(Unit) { refreshAccountBalance() }
    val accountEquity = snapshot?.let { it.spot.valueUsdt + it.futures.totalEquity }
    val snapshotAgeMs = snapshot?.let { (System.currentTimeMillis() - it.savedAt).coerceAtLeast(0L) }
    val snapshotAgeLabel = snapshotAgeMs?.let { age ->
        val minutes = age / 60_000L
        when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 24L * 60L -> "${minutes / 60L} hr ago"
            else -> "${minutes / (24L * 60L)} days ago"
        }
    }
    val balanceHeadline = when {
        snapshot == null && !hasBinanceCredentials -> "Connect Binance read-only API credentials in Binance Sync."
        snapshot == null && balanceRefreshing -> "Fetching your first Binance account snapshot…"
        snapshot == null -> "No account snapshot saved yet. Refresh to fetch your Spot and Futures equity."
        balanceRefreshing -> "Refreshing · showing your last saved snapshot"
        balanceStatus.startsWith("Refresh failed") -> "Refresh failed · showing saved data from ${snapshotAgeLabel ?: "an earlier sync"}"
        snapshotAgeMs != null && snapshotAgeMs >= 24L * 60L * 60L * 1000L -> "Saved account data · ${snapshotAgeLabel}"
        else -> "Binance account data · updated ${snapshotAgeLabel}"
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val daily = remember(entries, importedPnl, trades) {
        reconcileDailyPnl(entries, importedPnl, trades).sortedBy { it.date }
    }
    val todayRow = daily.lastOrNull { it.date == today }
    val todayPnl = todayRow?.selectedPnl
    val netPnl = daily.sumOf { it.selectedPnl }
    val wins = trades.count { it.pnl > 0.0 }
    val closedTrades = wins + trades.count { it.pnl < 0.0 }
    val winRate = if (closedTrades > 0) wins.toDouble() / closedTrades * 100.0 else null
    val latestBalance = entries.sortedBy { dashboardReportDate(it.date)?.time ?: Long.MIN_VALUE }.lastOrNull()?.closing
    val recentTrades = trades.sortedByDescending { dashboardReportDate(it.date)?.time ?: Long.MIN_VALUE }.take(5)
    val riskRemaining = if (maxDailyLoss > 0.0 && todayPnl != null) (maxDailyLoss + todayPnl).coerceIn(0.0, maxDailyLoss) else null
    val riskUsed = if (maxDailyLoss > 0.0 && todayPnl != null) (-todayPnl / maxDailyLoss).coerceIn(0.0, 1.0).toFloat() else null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("YOUR TRADING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TODAY'S PROFIT / LOSS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(todayPnl?.let { (if (it > 0.0) "+" else "") + it.usd() } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                        color = when { todayPnl == null -> MaterialTheme.colorScheme.onPrimaryContainer; todayPnl > 0.0 -> DashboardGreen; todayPnl < 0.0 -> DashboardRed; else -> MaterialTheme.colorScheme.onPrimaryContainer })
                    Text(if (todayRow == null) "No result recorded for today" else "Based on " + todayRow.source.name.lowercase().replaceFirstChar { it.uppercase() } + " data",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SimpleHeroStat("Winning days", daily.count { it.selectedPnl > 0.0 }.toString())
                        SimpleHeroStat("Losing days", daily.count { it.selectedPnl < 0.0 }.toString())
                        SimpleHeroStat("Days tracked", daily.size.toString())
                    }
                }
            }
        }
        item {
            Text("At a glance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SimpleMetricCard("Account balance", accountEquity?.usd() ?: "Unavailable", "Spot + Futures equity", Modifier.weight(1f))
                    SimpleMetricCard("Ledger balance", latestBalance?.usd() ?: "Unavailable", "Latest saved balance", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(balanceHeadline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (snapshot != null) {
                            Text("Spot ${snapshot.spot.valueUsdt.usd()} · Futures ${snapshot.futures.totalEquity.usd()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Snapshot saved " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(snapshot.savedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { refreshAccountBalance() }, enabled = !balanceRefreshing && hasBinanceCredentials) { Text(if (balanceRefreshing) "Refreshing…" else "Refresh") }
                }
                if (snapshot != null && snapshot.spot.unpricedAssets > 0) {
                    Text("${snapshot.spot.unpricedAssets} Spot assets could not be priced and are excluded from the displayed total.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SimpleMetricCard("Total profit / loss", (if (netPnl > 0.0) "+" else "") + netPnl.usd(), daily.size.toString() + " recorded days", Modifier.weight(1f), if (netPnl >= 0.0) DashboardGreen else DashboardRed)
                    SimpleMetricCard("Win rate", winRate?.pct(1) ?: "—", closedTrades.toString() + " closed trades", Modifier.weight(1f))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Profit trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Daily results · latest 14 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onNavigate(8) }) { Text("Details") }
                    }
                    val values = daily.takeLast(14).map { it.selectedPnl }
                    if (values.size >= 2) {
                        SimplePnlChart(values, Modifier.fillMaxWidth().height(104.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(daily.takeLast(14).first().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(daily.last().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Performance trend will appear when fetched trading data is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Daily loss limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Based on today's recorded result", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (riskRemaining != null && riskUsed != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Loss budget remaining", style = MaterialTheme.typography.bodyMedium)
                            Text(riskRemaining.usd(), fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { riskUsed }, modifier = Modifier.fillMaxWidth(), color = if (riskUsed >= 1f) DashboardRed else MaterialTheme.colorScheme.primary)
                        Text((riskUsed * 100).toInt().toString() + "% of your daily loss limit used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Risk usage will appear when a saved daily loss limit and today's result are available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Recent trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Your latest closed trades", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onNavigate(1) }) { Text("See all") }
            }
        }
        if (recentTrades.isEmpty()) {
            item { Text("No fetched trade data available yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(recentTrades, key = { it.id }) { trade ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(trade.symbol.ifBlank { "Trade" }, fontWeight = FontWeight.SemiBold)
                            Text(trade.side.uppercase(Locale.ROOT) + " · " + trade.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (trade.pnl > 0.0) "+" else "") + trade.pnl.usd(), fontWeight = FontWeight.Bold, color = if (trade.pnl >= 0.0) DashboardGreen else DashboardRed)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { onNavigate(8) }, modifier = Modifier.fillMaxWidth()) {
                Text("View full reports")
            }
        }
        item {
            Text("Account balance combines estimated Spot value in USDT with USDⓈ-M Futures equity. Unpriced Spot assets are excluded; Ledger balance is separate.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

}

@Composable
private fun SimpleHeroStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SimpleMetricCard(label: String, value: String, detail: String, modifier: Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Card(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SimplePnlChart(values: List<Double>, modifier: Modifier = Modifier) {
    val positiveColor = DashboardGreen
    val negativeColor = DashboardRed
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val minValue = minOf(0.0, values.minOrNull() ?: 0.0)
        val maxValue = maxOf(0.0, values.maxOrNull() ?: 0.0)
        val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val zeroY = size.height * (maxValue / range).toFloat()
        drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, zeroY), androidx.compose.ui.geometry.Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
        val slot = size.width / values.size
        val barWidth = (slot * 0.52f).coerceAtLeast(3.dp.toPx())
        values.forEachIndexed { index, value ->
            val x = index * slot + (slot - barWidth) / 2f
            val y = size.height * ((maxValue - value) / range).toFloat()
            val top = minOf(y, zeroY)
            val height = kotlin.math.abs(zeroY - y).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = if (value >= 0.0) positiveColor else negativeColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, top),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}

private fun dashboardReportDate(value: String): Date? {
    listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy").forEach { pattern ->
        try { return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) } catch (_: Exception) { }
    }
    return null
}
