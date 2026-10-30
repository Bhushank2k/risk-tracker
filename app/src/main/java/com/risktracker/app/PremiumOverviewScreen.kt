package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DashboardGreen = Color(0xFF21C58E)
private val DashboardRed = Color(0xFFFF6B75)

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
    var showAdd by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val snapshot = remember { BinanceAccountSnapshotStore(context).load() }
    val accountEquity = snapshot?.let { it.spot.valueUsdt + it.futures.totalEquity }
    val syncStatus = remember { BinanceAutoJournalStore.syncStatus(context) }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val daily = remember(entries, importedPnl, trades) { reconcileDailyPnl(entries, importedPnl, trades).sortedBy { it.date } }
    val todayRow = daily.lastOrNull { it.date == today }
    val todayPnl = todayRow?.selectedPnl
    val netPnl = daily.sumOf { it.selectedPnl }
    val wins = trades.count { it.pnl > 0.0 }
    val losses = trades.count { it.pnl < 0.0 }
    val closedTrades = wins + losses
    val winRate = if (closedTrades > 0) wins.toDouble() / closedTrades * 100.0 else null
    val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
    val grossLoss = -trades.filter { it.pnl < 0.0 }.sumOf { it.pnl }
    val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else null
    val latestBalance = entries.sortedBy { reportDate(it.date)?.time ?: Long.MIN_VALUE }.lastOrNull()?.closing
    val maxDrawdown = run {
        var peak = 0.0
        var worst = 0.0
        entries.sortedBy { reportDate(it.date)?.time ?: Long.MIN_VALUE }.forEach { row ->
            peak = maxOf(peak, row.closing)
            if (peak > 0.0) worst = maxOf(worst, (peak - row.closing) / peak * 100.0)
        }
        worst
    }
    val lossUsed = if (maxDailyLoss > 0.0 && todayPnl != null) (-todayPnl / maxDailyLoss).coerceIn(0.0, 1.0).toFloat() else null
    val balancePoints = entries.sortedBy { reportDate(it.date)?.time ?: Long.MIN_VALUE }.takeLast(30).map { it.closing }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TRADING WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("RISK FIRST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10251F), contentColor = Color(0xFFF3FFF9))
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TODAY'S NET P&L", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB0D5C7), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Surface(color = Color.White.copy(alpha = 0.10f), shape = RoundedCornerShape(50)) {
                            Text(
                                when {
                                    todayRow == null -> "NO DATA"
                                    maxDailyLoss > 0.0 && todayPnl!! <= -maxDailyLoss -> "LIMIT HIT"
                                    maxDailyLoss > 0.0 && todayPnl!! <= -maxDailyLoss * 0.8 -> "NEAR LIMIT"
                                    else -> "TRACKING"
                                },
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (todayPnl != null && maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss) Color(0xFFFF9298) else Color(0xFFB8F4D9),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        todayPnl?.let { (if (it > 0.0) "+" else "") + it.usd() } ?: "—",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = when { todayPnl == null -> Color.White; todayPnl > 0.0 -> DashboardGreen; todayPnl < 0.0 -> Color(0xFFFF9298); else -> Color.White }
                    )
                    Text(
                        if (todayRow == null) "No reconciled result recorded for today"
                        else "Source: " + todayRow.source.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0D5C7)
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DashboardHeroStat("WINNING DAYS", daily.count { it.selectedPnl > 0.0 }.toString())
                        DashboardHeroStat("LOSING DAYS", daily.count { it.selectedPnl < 0.0 }.toString())
                        DashboardHeroStat("WIN-DAY RATE", if (daily.isEmpty()) "—" else daily.count { it.selectedPnl > 0.0 }.toDouble().div(daily.size).times(100).pct(0))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetricCard("ACCOUNT EQUITY", accountEquity?.usd() ?: "Unavailable", "Last saved Binance snapshot", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                DashboardMetricCard("LEDGER BALANCE", latestBalance?.usd() ?: "Not set", "Last recorded closing balance", Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetricCard("NET P&L", (if (netPnl > 0.0) "+" else "") + netPnl.usd(), "${daily.size} reconciled days", Modifier.weight(1f), if (netPnl >= 0.0) DashboardGreen else DashboardRed)
                DashboardMetricCard("MAX DRAWDOWN", if (entries.isEmpty()) "—" else maxDrawdown.pct(), "Peak-to-trough ledger", Modifier.weight(1f), if (maxDrawdown >= 10.0) DashboardRed else MaterialTheme.colorScheme.onSurface)
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Performance snapshot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Closed trade journal · estimates after entered fees", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DashboardInlineMetric("CLOSED TRADES", closedTrades.toString())
                        DashboardInlineMetric("WIN RATE", winRate?.pct(1) ?: "—")
                        DashboardInlineMetric("PROFIT FACTOR", profitFactor?.let { it.num(2) } ?: "—")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Performance figures use closed journal trades only. Funding, slippage and exchange contract multipliers may not be included.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Capital curve", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Manual balance ledger · last 30 entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onNavigate(8) }) { Text("Analytics") }
                    }
                    if (balancePoints.size >= 2) {
                        Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                            val low = balancePoints.minOrNull() ?: 0.0
                            val high = balancePoints.maxOrNull() ?: 0.0
                            val span = (high - low).takeIf { it > 0.0 } ?: 1.0
                            val line = Path()
                            balancePoints.forEachIndexed { index, value ->
                                val x = size.width * index / (balancePoints.size - 1).coerceAtLeast(1)
                                val y = size.height * (1.0 - (value - low) / span).toFloat()
                                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
                            }
                            drawLine(MaterialTheme.colorScheme.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                            drawPath(line, DashboardGreen, style = Stroke(width = 3.dp.toPx()))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(balancePoints.first().usd(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(balancePoints.last().usd(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("Add at least two balance-ledger entries to build the capital curve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Daily risk guard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Advisory limit based on recorded daily P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Remaining loss budget", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                maxDailyLoss <= 0.0 -> "Not configured"
                                todayPnl == null -> "Unknown"
                                else -> (maxDailyLoss + todayPnl).coerceIn(0.0, maxDailyLoss).usd()
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (todayPnl != null && maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss) DashboardRed else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (lossUsed != null) {
                        LinearProgressIndicator(progress = { lossUsed }, modifier = Modifier.fillMaxWidth(), color = if (lossUsed >= 1f) DashboardRed else MaterialTheme.colorScheme.primary)
                        Text("${(lossUsed * 100).toInt()}% of configured daily loss limit used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Record today's result and configure a daily loss limit to activate this monitor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onNavigate(11) }, modifier = Modifier.fillMaxWidth()) { Text("Open Trade Risk Gate") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Exchange data health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(syncStatus.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(if (syncStatus.lastSuccess > 0L) "Last successful sync: " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(syncStatus.lastSuccess)) else "No fully successful automatic execution-history sync recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("A successful rolling sync is not proof of complete account history. Reconcile older periods with Binance exports.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onNavigate(18) }) { Text("View sync health", modifier = Modifier) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Recent trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Closed journal activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onNavigate(1) }) { Text("View journal") }
            }
        }
        if (trades.isEmpty()) {
            item { Text("No closed trades yet. Add your first trade to start measuring execution quality.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(trades.sortedByDescending { reportDate(it.date)?.time ?: Long.MIN_VALUE }.take(4), key = { it.id }) { trade ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(trade.symbol.ifBlank { "Trade" }, fontWeight = FontWeight.SemiBold)
                            Text("${trade.side.uppercase(Locale.ROOT)} · ${trade.date}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (trade.pnl > 0.0) "+" else "") + trade.pnl.usd(), fontWeight = FontWeight.Bold, color = if (trade.pnl >= 0.0) DashboardGreen else DashboardRed)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add daily result")
                }
                OutlinedButton(onClick = { onNavigate(8) }, modifier = Modifier.weight(1f)) {
                    Text("Full analytics")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        }
        item {
            Text("Data trust: account equity is the last saved Binance snapshot; capital curve uses the manual balance ledger; daily P&L is reconciled with source precedence; journal statistics use closed trades only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showAdd) {
        AddEntryDialog(entries.lastOrNull(), onDismiss = { showAdd = false }) { entry ->
            onChange(entries + entry)
            showAdd = false
        }
    }
}

@Composable
private fun DashboardHeroStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB0D5C7))
        Text(value, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DashboardMetricCard(label: String, value: String, detail: String, modifier: Modifier, valueColor: Color) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardInlineMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
