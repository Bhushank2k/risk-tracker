package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


@Composable
fun RiskOverviewScreen(
    entries: List<PnlEntry>,
    importedPnl: List<BinanceDailyResult>,
    trades: List<TradeEntry>,
    maxDailyLoss: Double,
    onNavigate: (Int) -> Unit,
    onChange: (List<PnlEntry>) -> Unit,
    onImportedPnlChange: (List<BinanceDailyResult>) -> Unit
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
    var dailyPnlStatus by remember { mutableStateOf("") }
    var dailyPnlRefreshing by remember { mutableStateOf(false) }
    var lastDailyPnlRefresh by remember { mutableLongStateOf(BinanceSyncData.lastFastRefresh(context)) }
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
                    positionsStatus = "Position sync failed · showing saved positions · " +
                        AppErrorMessage.from(e, "Try again later.")
                }
            } finally {
                positionsLoading = false
            }
        }
    }

    val dailyPnlScope = rememberCoroutineScope()
    fun refreshRecentDailyPnl(force: Boolean = false) {
        if (dailyPnlRefreshing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDailyPnlRefresh < 5L * 60L * 1000L) return
        if (BinanceCredentialStore(context).read() == null) return
        dailyPnlRefreshing = true
        dailyPnlStatus = "Refreshing today's realized P&L…"
        dailyPnlScope.launch {
            try {
                BinanceSyncRunner.refreshRecentFuturesPnl(context)
                val refreshedAt = System.currentTimeMillis()
                BinanceSyncData.markFastRefresh(context)
                lastDailyPnlRefresh = refreshedAt
                val latest = withContext(Dispatchers.IO) { BinanceSyncData.load(context) }
                liveImportedPnl = latest
                onImportedPnlChange(latest)
                dailyPnlStatus = "Realized P&L updated from Binance"
            } catch (e: Exception) {
                dailyPnlStatus = "P&L refresh unavailable · showing saved result"
            } finally {
                dailyPnlRefreshing = false
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
            refreshRecentDailyPnl()
            var lastFastAttempt = System.currentTimeMillis()
            while (true) {
                delay(60_000L)
                refreshLivePositions()
                refreshAccountBalance()
                if (System.currentTimeMillis() - lastFastAttempt >= 5L * 60L * 1000L) {
                    lastFastAttempt = System.currentTimeMillis()
                    refreshRecentDailyPnl()
                }
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
                    if (dailyPnlStatus.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                dailyPnlStatus,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dailyPnlStatus.contains("unavailable")) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                            )
                            TextButton(
                                onClick = { refreshRecentDailyPnl(force = true) },
                                enabled = !dailyPnlRefreshing
                            ) {
                                Text(if (dailyPnlRefreshing) "Updating…" else "Refresh P&L")
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { refreshRecentDailyPnl(force = true) },
                            enabled = !dailyPnlRefreshing
                        ) { Text(if (dailyPnlRefreshing) "Updating P&L…" else "Refresh P&L") }
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

        item { PortfolioPerformanceCard(daily = daily, entries = entries, onOpenReport = { onNavigate(20) }) }

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

private data class PortfolioChartPoint(val date: String, val value: Double, val dailyPnl: Double, val benchmarkValue: Double? = null)

private enum class PortfolioRange(val label: String, val days: Int?) {
    WEEK("1W", 7), MONTH("1M", 30), THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180), YEAR("1Y", 365), ALL("ALL", null)
}

@Composable
private fun PortfolioPerformanceCard(daily: List<ReconciledDailyPnl>, entries: List<PnlEntry>, onOpenReport: () -> Unit) {
    var selectedRange by remember { mutableStateOf(PortfolioRange.MONTH) }
    var selectedPoint by remember { mutableIntStateOf(-1) }
    var niftyCloses by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var niftyStatus by remember { mutableStateOf("Loading NIFTY 50 benchmark…") }

    LaunchedEffect(Unit) {
        try {
            val closes = withContext(Dispatchers.IO) { loadNifty50DailyCloses() }
            niftyCloses = closes
            niftyStatus = if (closes.isEmpty()) "NIFTY 50 benchmark temporarily unavailable" else "NIFTY 50 · Yahoo Finance"
        } catch (_: Exception) {
            niftyStatus = "NIFTY 50 benchmark unavailable · portfolio data is unaffected"
        }
    }

    val orderedEntries = remember(entries) {
        entries.mapNotNull { entry -> canonicalPnlDate(entry.date)?.let { it to entry } }.sortedBy { it.first }
    }
    val startingBalance = orderedEntries.firstOrNull()?.second?.balance?.takeIf { it > 0.0 }
    val allPoints = remember(daily, startingBalance, niftyCloses) {
        var cumulative = 0.0
        val rows = daily.sortedBy { it.date }.map { row ->
            cumulative += row.selectedPnl
            PortfolioChartPoint(row.date, (startingBalance ?: 100.0) + cumulative, row.selectedPnl)
        }
        val firstBenchmark = rows.firstNotNullOfOrNull { niftyCloses[it.date] }
        if (firstBenchmark == null || firstBenchmark <= 0.0) rows
        else rows.map { point ->
            point.copy(benchmarkValue = niftyCloses[point.date]?.let { (startingBalance ?: 100.0) * it / firstBenchmark })
        }
    }
    val filteredPoints = remember(allPoints, selectedRange) {
        val days = selectedRange.days
        if (days == null || allPoints.isEmpty()) allPoints else {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            allPoints.filter { point ->
                val date = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(point.date)?.time } catch (_: Exception) { null }
                date != null && date >= cutoff
            }
        }
    }
    LaunchedEffect(filteredPoints.size, selectedRange) { selectedPoint = -1 }

    val totalReturn = daily.sumOf { it.selectedPnl }
    val currentValue = startingBalance?.plus(totalReturn)
    val returnPct = if (startingBalance != null && startingBalance > 0.0) totalReturn / startingBalance * 100.0 else null
    val positiveStart = Color(0xFF22D3EE)
    val positiveEnd = Color(0xFF22C55E)
    val negativeStart = Color(0xFFFB923C)
    val negativeEnd = Color(0xFFEF4444)
    val selected = filteredPoints.getOrNull(selectedPoint)

    RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Portfolio performance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your recorded portfolio growth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ShowChart, contentDescription = null, tint = if (totalReturn >= 0) positiveEnd else negativeEnd, modifier = Modifier.size(22.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PortfolioRange.values().forEach { range ->
                    val active = selectedRange == range
                    Surface(
                        modifier = Modifier.weight(1f).clickable { selectedRange = range },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f) else Color.Transparent,
                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null
                    ) {
                        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(range.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (selected != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selected.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected.value.usd(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text((if (selected.dailyPnl >= 0) "+" else "") + selected.dailyPnl.usd() + " daily P&L",
                        style = MaterialTheme.typography.labelMedium, color = if (selected.dailyPnl >= 0) positiveEnd else negativeEnd)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("CURRENT RECORDED VALUE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Text(currentValue?.usd() ?: (allPoints.lastOrNull()?.value?.usd() ?: "—"),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    val returnText = (if (totalReturn >= 0) "+" else "") + totalReturn.usd() +
                        (returnPct?.let { "  (" + (if (it >= 0) "+" else "") + "%.2f".format(Locale.US, it) + "%)" } ?: "")
                    Text(returnText, style = MaterialTheme.typography.labelMedium,
                        color = if (totalReturn >= 0) positiveEnd else negativeEnd)
                }
            }

            if (filteredPoints.size >= 2) {
                PortfolioPerformanceCanvas(
                    points = filteredPoints, selectedIndex = selectedPoint, onSelect = { selectedPoint = it },
                    positiveStart = positiveStart, positiveEnd = positiveEnd, negativeStart = negativeStart, negativeEnd = negativeEnd,
                    modifier = Modifier.fillMaxWidth().height(184.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(filteredPoints.first().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(filteredPoints.last().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Add at least two recorded daily P&L entries to see the interactive performance trend.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(width = 17.dp, height = 3.dp)) {
                        drawLine(Brush.horizontalGradient(listOf(positiveStart, positiveEnd)), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 3.dp.toPx())
                    }
                    Text("Portfolio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(width = 17.dp, height = 3.dp)) {
                        drawLine(Color(0xFF9CA3AF), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                            1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    }
                    Text("NIFTY 50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(niftyStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortfolioSummaryMetric("Invested amount", startingBalance?.usd() ?: "—", Modifier.weight(1f))
                PortfolioSummaryMetric("Current value", currentValue?.usd() ?: "—", Modifier.weight(1f))
                PortfolioSummaryMetric("Total returns", (if (totalReturn > 0) "+" else "") + totalReturn.usd(),
                    Modifier.weight(1f), if (totalReturn >= 0) positiveEnd else negativeEnd)
            }
            if (startingBalance == null) {
                Text("Invested amount and current value need a recorded opening balance. The chart currently shows cumulative P&L from a neutral baseline.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SimpleHeroStat("Winning days", daily.count { it.selectedPnl > 0.0 }.toString())
                SimpleHeroStat("Losing days", daily.count { it.selectedPnl < 0.0 }.toString())
                SimpleHeroStat("Days tracked", daily.size.toString())
            }
            TextButton(onClick = onOpenReport, modifier = Modifier.align(Alignment.End)) { Text("Open performance report") }
        }
    }
}

@Composable
private fun PortfolioSummaryMetric(label: String, value: String, modifier: Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PortfolioPerformanceCanvas(
    points: List<PortfolioChartPoint>, selectedIndex: Int, onSelect: (Int) -> Unit,
    positiveStart: Color, positiveEnd: Color, negativeStart: Color, negativeEnd: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val benchmarkColor = Color(0xFF9CA3AF)
    Canvas(modifier
        .pointerInput(points) {
            detectTapGestures { offset ->
                if (points.isNotEmpty()) onSelect((offset.x / size.width * (points.size - 1)).roundToInt().coerceIn(points.indices))
            }
        }
        .pointerInput(points) {
            detectDragGestures(
                onDragStart = { offset ->
                    if (points.isNotEmpty()) onSelect((offset.x / size.width * (points.size - 1)).roundToInt().coerceIn(points.indices))
                },
                onDrag = { change, _ ->
                    if (points.isNotEmpty()) onSelect((change.position.x / size.width * (points.size - 1)).roundToInt().coerceIn(points.indices))
                }
            )
        }
    ) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.value } + points.mapNotNull { it.benchmarkValue }
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        val padding = ((high - low) * 0.16).coerceAtLeast(1.0)
        val minValue = low - padding
        val maxValue = high + padding
        val range = (maxValue - minValue).coerceAtLeast(1.0)
        fun y(value: Double) = size.height * (1f - ((value - minValue) / range).toFloat())
        fun x(index: Int) = size.width * index / (points.size - 1).toFloat()

        for (i in 0..3) {
            val yy = size.height * i / 3f
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, yy), androidx.compose.ui.geometry.Offset(size.width, yy), 0.7.dp.toPx())
        }

        val portfolioPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(0), y(points[0].value))
            for (i in 1 until points.size) lineTo(x(i), y(points[i].value))
        }
        val fillPath = androidx.compose.ui.graphics.Path().apply {
            addPath(portfolioPath)
            lineTo(x(points.lastIndex), size.height)
            lineTo(x(0), size.height)
            close()
        }
        val overallPositive = points.last().value >= points.first().value
        drawPath(fillPath, Brush.verticalGradient(
            if (overallPositive) listOf(positiveEnd.copy(alpha = 0.25f), positiveStart.copy(alpha = 0.015f))
            else listOf(negativeEnd.copy(alpha = 0.23f), negativeStart.copy(alpha = 0.012f))
        ))

        for (i in 1 until points.size) {
            val up = points[i].value >= points[i - 1].value
            drawLine(
                brush = Brush.linearGradient(if (up) listOf(positiveStart, positiveEnd) else listOf(negativeStart, negativeEnd),
                    start = androidx.compose.ui.geometry.Offset(x(i - 1), y(points[i - 1].value)),
                    end = androidx.compose.ui.geometry.Offset(x(i), y(points[i].value))),
                start = androidx.compose.ui.geometry.Offset(x(i - 1), y(points[i - 1].value)),
                end = androidx.compose.ui.geometry.Offset(x(i), y(points[i].value)),
                strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        val benchmarkPoints = points.mapIndexedNotNull { index, point -> point.benchmarkValue?.let { index to it } }
        if (benchmarkPoints.size >= 2) {
            val benchmarkPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(x(benchmarkPoints.first().first), y(benchmarkPoints.first().second))
                benchmarkPoints.drop(1).forEach { (index, value) -> lineTo(x(index), y(value)) }
            }
            drawPath(benchmarkPath, benchmarkColor.copy(alpha = 0.8f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
        }

        if (selectedIndex in points.indices) {
            val selectedX = x(selectedIndex)
            val selectedY = y(points[selectedIndex].value)
            drawLine(benchmarkColor.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(selectedX, 0f),
                androidx.compose.ui.geometry.Offset(selectedX, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            drawCircle(Color.White.copy(alpha = 0.18f), 9.dp.toPx(), androidx.compose.ui.geometry.Offset(selectedX, selectedY))
            drawCircle(if (points[selectedIndex].dailyPnl >= 0) positiveEnd else negativeEnd, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(selectedX, selectedY))
        } else {
            val last = points.last()
            val lastY = y(last.value)
            val color = if (last.dailyPnl >= 0) positiveEnd else negativeEnd
            drawCircle(color.copy(alpha = 0.2f), 9.dp.toPx(), androidx.compose.ui.geometry.Offset(x(points.lastIndex), lastY))
            drawCircle(color, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(x(points.lastIndex), lastY))
        }
    }
}

private fun loadNifty50DailyCloses(): Map<String, Double> {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val request = Request.Builder()
        .url("https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=5y&interval=1d")
        .header("User-Agent", "Mozilla/5.0 RiskTracker/1.0").build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("NIFTY data request failed")
        val root = JSONObject(response.body?.string().orEmpty())
        val result = root.getJSONObject("chart").getJSONArray("result").optJSONObject(0) ?: return emptyMap()
        val timestamps = result.getJSONArray("timestamp")
        val closes = result.getJSONObject("indicators").getJSONArray("quote").optJSONObject(0)?.getJSONArray("close") ?: return emptyMap()
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return buildMap {
            for (i in 0 until minOf(timestamps.length(), closes.length())) {
                if (closes.isNull(i)) continue
                val close = closes.optDouble(i, Double.NaN)
                if (close.isFinite() && close > 0.0) put(formatter.format(Date(timestamps.getLong(i) * 1000L)), close)
            }
        }
    }
}

private fun dashboardReportDate(value: String): Date? {
    listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy").forEach { pattern ->
        try { return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) } catch (_: Exception) { }
    }
    return null
}
