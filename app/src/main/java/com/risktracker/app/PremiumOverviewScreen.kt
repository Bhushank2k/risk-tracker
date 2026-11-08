package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
fun RiskOverviewScreen(
    entries: List<PnlEntry>,
    importedPnl: List<BinanceDailyResult>,
    trades: List<TradeEntry>,
    maxDailyLoss: Double,
    onNavigate: (Int) -> Unit,
    onChange: (List<PnlEntry>) -> Unit
) {
    val context = LocalContext.current
    val positionsStore = remember { BinanceLivePositionsStore(context) }
    var snapshot by remember { mutableStateOf(BinanceAccountSnapshotStore(context).load()) }
    var balanceRefreshing by remember { mutableStateOf(false) }
    var balanceStatus by remember { mutableStateOf("") }
    var hasBinanceCredentials by remember { mutableStateOf(BinanceCredentialStore(context).read() != null) }
    var liveImportedPnl by remember { mutableStateOf(importedPnl) }
    var livePositions by remember { mutableStateOf(positionsStore.load()) }
    var positionsLoading by remember { mutableStateOf(false) }
    var positionsStatus by remember {
        mutableStateOf(if (hasBinanceCredentials) "Showing saved positions; live prices connect over WebSocket." else "Connect read-only Binance credentials in Binance Sync to see account data.")
    }
    var markPriceStatus by remember { mutableStateOf("Starting mark-price stream…") }
    var lastPositionRestAttempt by remember { mutableLongStateOf(0L) }
    var lastBalanceRestAttempt by remember { mutableLongStateOf(0L) }
    val positionsScope = rememberCoroutineScope()

    fun refreshLivePositions() {
        if (positionsLoading) return
        val now = System.currentTimeMillis()
        val blockedUntil = positionsStore.restBlockedUntil()
        if (blockedUntil > now) {
            positionsStatus = "Binance REST rate limit active until " +
                SimpleDateFormat("HH:mm:ss 'IST'", Locale.getDefault()).format(Date(blockedUntil))
            return
        }
        if (now - lastPositionRestAttempt < 60_000L) {
            positionsStatus = "Position REST sync is limited to once per minute; live prices continue over WebSocket."
            return
        }
        val credentials = BinanceCredentialStore(context).read()
        if (credentials == null) {
            positionsStatus = "Connect read-only Binance credentials in Binance Sync."
            return
        }
        lastPositionRestAttempt = now
        positionsLoading = true
        positionsScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    BinanceApi(credentials.first, credentials.second).futuresPositions()
                }
                livePositions = rows
                positionsStore.save(rows)
                positionsStatus = if (rows.isEmpty()) "No open USDⓈ-M Futures positions." else "Position snapshot updated; mark prices stream live."
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val expiry = binanceBanExpiry(message)
                if (expiry != null) {
                    positionsStore.blockRestUntil(expiry)
                    positionsStatus = "Binance HTTP 418: REST paused until " +
                        SimpleDateFormat("HH:mm:ss 'IST'", Locale.getDefault()).format(Date(expiry)) +
                        ". WebSocket mark prices remain active for cached positions."
                } else {
                    positionsStatus = "Position sync failed · showing saved positions" +
                        (message.takeIf { it.isNotBlank() }?.let { ": " + it } ?: "")
                }
            } finally {
                positionsLoading = false
            }
        }
    }

    val balanceScope = rememberCoroutineScope()
    fun refreshAccountBalance() {
        if (balanceRefreshing) return
        val now = System.currentTimeMillis()
        if (now - lastBalanceRestAttempt < 60_000L) return
        val blockedUntil = positionsStore.restBlockedUntil()
        if (blockedUntil > System.currentTimeMillis()) {
            balanceStatus = "Binance REST is paused until " +
                SimpleDateFormat("HH:mm:ss 'IST'", Locale.getDefault()).format(Date(blockedUntil))
            return
        }
        if (!hasBinanceCredentials) {
            balanceStatus = "Connect Binance read-only API credentials in Binance Sync."
            return
        }
        lastBalanceRestAttempt = now
        balanceRefreshing = true
        balanceStatus = "Refreshing account equity…"
        balanceScope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { BinanceAccountSnapshotRunner.refresh(context) }
                balanceStatus = "Updated from Binance"
            } catch (e: Exception) {
                val expiry = binanceBanExpiry(e.message.orEmpty())
                if (expiry != null) {
                    positionsStore.blockRestUntil(expiry)
                    balanceStatus = "Binance HTTP 418 · REST paused until " +
                        SimpleDateFormat("HH:mm:ss 'IST'", Locale.getDefault()).format(Date(expiry))
                } else {
                    balanceStatus = "Refresh failed · showing last saved balance"
                }
            } finally {
                balanceRefreshing = false
            }
        }
    }

    LaunchedEffect(importedPnl) {
        liveImportedPnl = importedPnl
    }

    // WorkManager updates SharedPreferences in the background; observe the latest P&L
    // snapshot even when the dashboard was opened before Binance credentials were connected.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            val latestPnl = withContext(Dispatchers.IO) { BinanceSyncData.load(context) }
            if (latestPnl != liveImportedPnl) liveImportedPnl = latestPnl
            if (!hasBinanceCredentials && BinanceCredentialStore(context).read() != null) {
                hasBinanceCredentials = true
            }
        }
    }

    LaunchedEffect(hasBinanceCredentials) {
        if (hasBinanceCredentials) {
            BinanceDailyWorker.schedule(context)
            refreshLivePositions()
            refreshAccountBalance()
            while (true) {
                delay(60_000L)
                refreshLivePositions()
                refreshAccountBalance()
            }
        }
    }

    val streamSymbols = livePositions.map { it.symbol }.distinct().sorted().joinToString(",")
    val currentLivePositions by rememberUpdatedState(livePositions)
    DisposableEffect(streamSymbols) {
        val symbols = streamSymbols.split(",").filter { it.isNotBlank() }
        val stream = BinanceMarkPriceStream(
            symbols = symbols,
            onMarkPrice = { symbol, price ->
                val updated = currentLivePositions.withStreamedMarkPrice(symbol, price)
                livePositions = updated
                positionsStore.save(updated)
            },
            onStatus = { markPriceStatus = it }
        )
        stream.start()
        onDispose { stream.close() }
    }



    val accountEquity = snapshot?.let { it.spot.valueUsdt + it.futures.totalEquity }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val daily = remember(entries, liveImportedPnl, trades) {
        reconcileDailyPnl(entries, liveImportedPnl, trades).sortedBy { it.date }
    }
    val todayPnl = daily.lastOrNull { it.date == today }?.selectedPnl
    val netPnl = daily.sumOf { it.selectedPnl }
    val wins = trades.count { it.pnl > 0.0 }
    val closedTrades = wins + trades.count { it.pnl < 0.0 }
    val winRate = if (closedTrades > 0) wins.toDouble() / closedTrades * 100.0 else null
    val latestBalance = entries.sortedBy { dashboardReportDate(it.date)?.time ?: Long.MIN_VALUE }.lastOrNull()?.closing
    val recentTrades = trades.sortedByDescending { dashboardReportDate(it.date)?.time ?: Long.MIN_VALUE }.take(5)
    val riskRemaining = if (maxDailyLoss > 0.0 && todayPnl != null) (maxDailyLoss + todayPnl).coerceIn(0.0, maxDailyLoss) else null
    val riskUsed = if (maxDailyLoss > 0.0 && todayPnl != null) (-todayPnl / maxDailyLoss).coerceIn(0.0, 1.0).toFloat() else null
    val totalPositionPnl = livePositions.sumOf { it.unrealizedPnl }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = RiskTrackerDesign.pagePadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Portfolio-first hero: actual account equity and today's recorded result.
        item {
            RiskCard(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
    Text("TOTAL ACCOUNT EQUITY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}
                            Text(accountEquity?.usd() ?: "Not connected", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text(
                                snapshot?.let { "Spot " + it.spot.valueUsdt.usd() + " · Futures " + it.futures.totalEquity.usd() } ?: "Connect read-only Binance credentials to load live equity",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Icon(if ((todayPnl ?: 0.0) >= 0.0) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown, contentDescription = null, modifier = Modifier.size(14.dp))
    Text("Today's recorded P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
}
                            Text(
                                todayPnl?.let { (if (it > 0.0) "+" else "") + it.usd() } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    todayPnl == null -> MaterialTheme.colorScheme.onPrimaryContainer
                                    todayPnl > 0.0 -> profitColor()
                                    todayPnl < 0.0 -> lossColor()
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Icon(Icons.Filled.ShowChart, contentDescription = null, modifier = Modifier.size(14.dp))
    Text("Live unrealized P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
}
                            Row(
                                modifier = Modifier.clickable(
                                    onClick = { onNavigate(16) },
                                    role = androidx.compose.ui.semantics.Role.Button
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    (if (totalPositionPnl > 0.0) "+" else "") + totalPositionPnl.usd(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (totalPositionPnl >= 0.0) profitColor() else lossColor()
                                )
                                Text("↗", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }

                        }
                    }
                    if (balanceStatus.isNotBlank()) {
                        val balanceFailed = balanceStatus.startsWith("Refresh failed") || balanceStatus.contains("418")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (balanceFailed) Icons.Filled.Error else Icons.Filled.CheckCircle, contentDescription = null,
                                tint = if (balanceFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp))
                            Text(balanceStatus, style = MaterialTheme.typography.labelSmall,
                                color = if (balanceFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                        }
                    }
                }
            }
        }

        // The daily loss budget is the most actionable number, so it sits right under the equity card.
        item {
            RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Daily loss budget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (riskRemaining != null) Text(riskRemaining.usd() + " left", fontWeight = FontWeight.Bold, color = if (riskUsed != null && riskUsed >= 1f) lossColor() else MaterialTheme.colorScheme.onSurface)
                    }
                    if (riskRemaining != null && riskUsed != null) {
                        LinearProgressIndicator(progress = { riskUsed }, modifier = Modifier.fillMaxWidth(), color = if (riskUsed >= 1f) lossColor() else MaterialTheme.colorScheme.primary)
                        Text((riskUsed * 100).toInt().toString() + "% of your daily loss limit used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (riskUsed >= 1f) Text("Daily loss limit reached. Pause and review risk before taking another trade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Set a daily loss limit to see your remaining budget here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Compact KPI strip: keep the two most actionable portfolio metrics together.
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SimpleMetricCard(
                    "Net recorded P&L",
                    (if (netPnl > 0.0) "+" else "") + netPnl.usd(),
                    daily.size.toString() + " recorded days",
                    Modifier.weight(1f),
                    if (netPnl >= 0.0) profitColor() else lossColor(),
                    Icons.Filled.ShowChart
                )
                SimpleMetricCard(
                    "Open positions",
                    livePositions.size.toString(),
                    if (positionsStatus.contains("failed") || positionsStatus.contains("418")) "Cached · live prices" else "Futures · live prices",
                    Modifier.weight(1f),
                    icon = Icons.Filled.AccountBalanceWallet
                )
            }
            if (winRate != null || latestBalance != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (winRate != null) CompactOverviewMeta("Win rate", winRate.pct(1) + " · " + closedTrades + " closed")
                    if (latestBalance != null) CompactOverviewMeta("Last recorded balance", latestBalance.usd())
                }
            }
        }

        item {
            SectionHeading("Active positions", "Live exposure and unrealized results")
        }
        item {
            RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(livePositions.size.toString() + " open Futures position" + if (livePositions.size == 1) "" else "s", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            // Routine sync timing stays out of view; only a failed sync is shown.
                            if (positionsStatus.contains("418") || positionsStatus.contains("failed")) Text(positionsStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Text(markPriceStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (positionsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (livePositions.isEmpty()) {
                        Text(
                            when {
                                !hasBinanceCredentials -> "Add read-only API credentials in Binance Sync to see open positions here."
                                positionsStatus.contains("418") -> "Position snapshot unavailable while Binance REST is rate-limited. Cached positions will continue receiving WebSocket prices when available."
                                positionsStatus.startsWith("No open") -> "No open USDⓈ-M Futures positions in the latest saved snapshot."
                                else -> "No saved position snapshot yet. Use Sync once after the Binance rate limit clears."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Combined unrealized P&L", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text((if (totalPositionPnl > 0.0) "+" else "") + totalPositionPnl.usd(), fontWeight = FontWeight.Bold, color = if (totalPositionPnl >= 0.0) profitColor() else lossColor())
                        }
                        HorizontalDivider()
                        livePositions.forEachIndexed { index, position ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(position.symbol + " · " + position.side + " · " + position.leverage + "×", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text((if (position.unrealizedPnl > 0.0) "+" else "") + position.unrealizedPnl.usd(), fontWeight = FontWeight.Bold, color = if (position.unrealizedPnl >= 0.0) profitColor() else lossColor())
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Entry " + position.entryPrice.usd(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Mark " + position.markPrice.usd(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Qty " + position.quantity.num(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (position.liquidationPrice > 0.0) {
                                    val distancePct = if (position.markPrice > 0.0) kotlin.math.abs(position.liquidationPrice - position.markPrice) / position.markPrice * 100.0 else null
                                    Text("Liq. " + position.liquidationPrice.usd(4) + (distancePct?.let { " · " + "%.0f".format(Locale.US, it) + "% from mark" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (index < livePositions.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        if (hasBinanceCredentials) {
            item {
                PortfolioRiskCard(
                    positions = livePositions,
                    futuresBalance = snapshot?.futures,
                    positionsRefreshFailed = positionsStatus.startsWith("Refresh failed") || positionsStatus.contains("418")
                )
            }
        }

        item {
            RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Portfolio performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Cumulative recorded P&L · latest 14 recorded days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val values = daily.takeLast(14).map { it.selectedPnl }
                    if (values.size >= 2) {
                        SimplePnlChart(values, Modifier.fillMaxWidth().height(112.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(daily.takeLast(14).first().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(daily.last().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Performance trend appears after at least two recorded daily results are available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SimpleHeroStat("Winning days", daily.count { it.selectedPnl > 0.0 }.toString())
                        SimpleHeroStat("Losing days", daily.count { it.selectedPnl < 0.0 }.toString())
                        SimpleHeroStat("Days tracked", daily.size.toString())
                    }
                    TextButton(onClick = { onNavigate(20) }, modifier = Modifier.align(Alignment.End)) { Text("Open performance report") }
                }
            }
        }

        if (recentTrades.isNotEmpty()) {
            item {
                SectionHeading("Recent trades", "Latest closed trades") {
                    onNavigate(1)
                }
            }
            items(recentTrades, key = { it.id }) { trade ->
                RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(trade.symbol.ifBlank { "Trade" }, fontWeight = FontWeight.SemiBold)
                            Text(trade.side.uppercase(Locale.ROOT) + " · " + trade.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (trade.pnl > 0.0) "+" else "") + trade.pnl.usd(), fontWeight = FontWeight.Bold, color = if (trade.pnl >= 0.0) profitColor() else lossColor())
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { onNavigate(8) }, modifier = Modifier.fillMaxWidth()) {
                Text("View full reports")
            }
        }
    }
}

@Composable
private fun CompactOverviewMeta(label: String, value: String) {
    Column(Modifier.fillMaxWidth(0.5f).padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (label.contains("win", true)) Icons.Filled.Percent else Icons.Filled.AccountBalanceWallet,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String, action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = action) { Text(if (title == "Active positions") "Refresh" else "See all") }
    }
}

@Composable
private fun SimpleHeroStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SimpleMetricCard(
    label: String, value: String, detail: String, modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector
) {
    RiskCard(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SimplePnlChart(values: List<Double>, modifier: Modifier = Modifier) {
    // Show cumulative recorded P&L, not account equity: this is derived only
    // from the daily results already available to the Overview screen.
    val cumulative = values.runningFold(0.0) { total, value -> total + value }.drop(1)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier) {
        if (cumulative.isEmpty()) return@Canvas

        val low = minOf(0.0, cumulative.minOrNull() ?: 0.0)
        val high = maxOf(0.0, cumulative.maxOrNull() ?: 0.0)
        val rawRange = high - low
        val padding = (rawRange * 0.12).coerceAtLeast(1.0)
        val minValue = low - padding
        val maxValue = high + padding
        val range = (maxValue - minValue).coerceAtLeast(1.0)
        fun yFor(value: Double): Float =
            size.height * (1f - ((value - minValue) / range).toFloat())

        // Quiet horizontal guides; the zero line is slightly stronger.
        for (index in 0..3) {
            val value = minValue + range * index / 3.0
            val y = yFor(value)
            drawLine(
                color = if (kotlin.math.abs(value) < range / 1000.0) lineColor.copy(alpha = 0.55f) else gridColor,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = if (kotlin.math.abs(value) < range / 1000.0) 1.4.dp.toPx() else 0.7.dp.toPx()
            )
        }

        val points = cumulative.mapIndexed { index, value ->
            androidx.compose.ui.geometry.Offset(
                if (cumulative.size == 1) size.width / 2f else size.width * index / (cumulative.size - 1).toFloat(),
                yFor(value)
            )
        }
        val curve = androidx.compose.ui.graphics.Path().apply {
            moveTo(points.first().x, points.first().y)
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val current = points[index]
                val midX = (previous.x + current.x) / 2f
                quadraticTo(midX, previous.y, current.x, current.y)
            }
        }
        val zeroY = yFor(0.0)
        val area = androidx.compose.ui.graphics.Path().apply {
            addPath(curve)
            lineTo(points.last().x, zeroY)
            lineTo(points.first().x, zeroY)
            close()
        }
        drawPath(area, lineColor.copy(alpha = 0.12f))
        drawPath(
            path = curve,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
        val last = points.last()
        drawCircle(lineColor.copy(alpha = 0.18f), radius = 6.dp.toPx(), center = last)
        drawCircle(lineColor, radius = 3.dp.toPx(), center = last)
    }
}

private fun dashboardReportDate(value: String): Date? {
    listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy").forEach { pattern ->
        try { return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) } catch (_: Exception) { }
    }
    return null
}
