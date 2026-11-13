package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

private data class PairAnalytics(
    val symbol: String,
    val realizedPnl: Double,
    val realizedOrders: Int
)

@Composable
fun TradingAnalyticsScreen(
    fills: List<BinanceJournalFill>,
    syncStatus: BinanceJournalSyncStatus,
    dailyEntries: List<PnlEntry> = emptyList(),
    manualTrades: List<TradeEntry> = emptyList(),
    importedDailyPnl: List<BinanceDailyResult> = emptyList()
) {
    var periodDays by remember { mutableStateOf<Int?>(null) }
    val now = System.currentTimeMillis()
    val filtered = remember(fills, periodDays, now) {
        val start = periodDays?.let { days ->
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }.timeInMillis
        }
        fills.filter { start == null || it.time >= start }
    }
    val validFills = filtered.filter {
        it.quoteAmount.isFinite() && it.quoteAmount >= 0.0 &&
            it.quantity.isFinite() && it.quantity >= 0.0 &&
            it.price.isFinite() && it.price >= 0.0 &&
            it.fee.isFinite() &&
            (it.realizedPnl == null || it.realizedPnl.isFinite())
    }
    val invalidFillCount = filtered.size - validFills.size
    val realizedFillCount = validFills.count { it.realizedPnl?.isFinite() == true }
    val realizedCoverage = if (validFills.isEmpty()) null else realizedFillCount * 100.0 / validFills.size
    val futuresFills = validFills.filter { it.market.contains("Futures", true) }
    val futuresRealizedFills = futuresFills.count { it.realizedPnl?.isFinite() == true }
    val futuresRealizedCoverage = if (futuresFills.isEmpty()) null else futuresRealizedFills * 100.0 / futuresFills.size
    val staleSync = syncStatus.lastSuccess <= 0L ||
        now - syncStatus.lastSuccess > 24L * 60L * 60L * 1000L
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
    val orderAnalytics = remember(validFills) { summarizeExchangeOrders(validFills) }
    val realizedOrders = orderAnalytics.realizedOrders
    val winning = realizedOrders.filter { it > 0.0 }
    val losing = realizedOrders.filter { it < 0.0 }
    val grossProfit = orderAnalytics.grossProfit
    val grossLoss = orderAnalytics.grossLoss
    val netPnl = grossProfit - grossLoss
    val decisiveCount = winning.size + losing.size
    val winRate = orderAnalytics.winRatePercent
    val lossRate = if (decisiveCount > 0) losing.size * 100.0 / decisiveCount else null
    val profitFactor = orderAnalytics.profitFactor
    val pairStats = validFills
        .filter { it.realizedPnl?.isFinite() == true }
        .groupBy { it.symbol.ifBlank { "Unknown pair" } }
        .map { (symbol, rows) ->
            val orderCount = rows.groupBy { fill ->
                "${fill.exchange}|${fill.market}|" +
                    (fill.orderId.takeIf { it.isNotBlank() } ?: fill.id)
            }.size
            PairAnalytics(symbol, rows.sumOf { it.realizedPnl ?: 0.0 }, orderCount)
        }
        .sortedByDescending { it.realizedPnl }
    val bestPair = pairStats.firstOrNull { it.realizedPnl > 0.0 }
    val worstPair = pairStats.lastOrNull { it.realizedPnl < 0.0 }
    val feeTotals = validFills.filter { it.fee.isFinite() && it.fee != 0.0 }
        .groupBy { it.feeAsset.ifBlank { "Unknown asset" } }
        .mapValues { (_, rows) -> rows.sumOf { it.fee } }
        .toList().sortedByDescending { it.second }
    val reconciledDaily = remember(dailyEntries, manualTrades, importedDailyPnl, periodDays, now) {
        val all = reconcileDailyPnl(dailyEntries, importedDailyPnl, manualTrades)
        val window = filterPnlEntriesForPeriod(
            all.map { PnlEntry(it.date, 0.0, it.selectedPnl) }, periodDays, now
        ).map { it.date }.toSet()
        all.filter { it.date in window }
    }
    val dailySnapshot = remember(reconciledDaily, dailyEntries, periodDays, now) {
        val visibleDates = filterPnlEntriesForPeriod(dailyEntries, periodDays, now).mapNotNull {
            canonicalPnlDate(it.date)
        }.toSet()
        buildPerformanceSnapshot(
            pnlValues = reconciledDaily.map { it.selectedPnl },
            closingBalances = dailyEntries.filter { canonicalPnlDate(it.date) in visibleDates }.map { it.balance },
            tradeCount = manualTrades.size
        )
    }

    val futuresIncomeTotals = remember(importedDailyPnl, reconciledDaily) {
        summarizeFuturesIncomeByType(importedDailyPnl, reconciledDaily.map { it.date }.toSet())
    }
    val rMultipleSummary = remember(manualTrades) { summarizeTradeRMultiples(manualTrades) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                    volumeByQuoteAsset.forEach { (asset, amount) ->
                        Text(amount.num() + " " + asset, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
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
                Text("Source-reconciled daily results. Binance takes precedence over manual daily P&L, then manual-journal estimates; overlapping sources are not added.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Futures daily P&L includes Binance realized P&L, commissions, funding and insurance adjustments. Spot P&L is FIFO-estimated; third-asset commissions are not historically converted, so the combined total is best-known rather than a fully converted account-wide net.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Selected daily P&L", dailySnapshot.netPnl.usd(), Modifier.weight(1f),
                        if (dailySnapshot.netPnl >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    AnalyticsMetric("Average daily P&L", dailySnapshot.averagePnl.usd(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Best day", dailySnapshot.bestDay?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                    AnalyticsMetric("Worst day", dailySnapshot.worstDay?.usd() ?: "—", Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                AnalyticsMetric("Peak-to-trough drawdown", dailySnapshot.maxDrawdownPercent.pct(2), Modifier.fillMaxWidth(),
                    MaterialTheme.colorScheme.error, "Based on the separate manual balance ledger")
                Text("${reconciledDaily.size} reconciled days · ${reconciledDaily.count { it.source == DailyPnlSource.BINANCE }} Binance · ${reconciledDaily.count { it.source == DailyPnlSource.MANUAL }} manual · ${reconciledDaily.count { it.source == DailyPnlSource.JOURNAL }} journal estimates",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reconciledDaily.takeLast(7).asReversed().forEach { day ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(day.date, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(when (day.source) {
                                DailyPnlSource.BINANCE -> "Binance-imported"
                                DailyPnlSource.MANUAL -> "Manual daily P&L"
                                DailyPnlSource.JOURNAL -> "Manual journal estimate"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("$decisiveCount realized close orders · wins: ${winning.size} · losses: ${losing.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Expectancy & consistency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Expectancy / close order", orderAnalytics.expectancyPerOrder?.usd() ?: "—",
                        Modifier.weight(1f), orderAnalytics.expectancyPerOrder?.let { if (it >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error } ?: MaterialTheme.colorScheme.onSurface,
                        "Average realized P&L before fees")
                    AnalyticsMetric("Average win / loss", orderAnalytics.averageWinLossRatio?.let { "%.2f×".format(Locale.US,it) } ?: "—",
                        Modifier.weight(1f), supporting = "Average win ÷ absolute average loss")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsMetric("Current streak", if (orderAnalytics.currentStreakCount == 0) "—" else "${orderAnalytics.currentStreakKind} × ${orderAnalytics.currentStreakCount}",
                        Modifier.weight(1f))
                    AnalyticsMetric("Longest W / L streak", "${orderAnalytics.longestWinStreak} / ${orderAnalytics.longestLossStreak}",
                        Modifier.weight(1f))
                }
                AnalyticsMetric("Realized-order drawdown", orderAnalytics.maxOrderDrawdown.usd(),
                    Modifier.fillMaxWidth(), MaterialTheme.colorScheme.error,
                    "Peak-to-trough on cumulative realized order P&L")
                Text("Realized order equity curve", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (orderAnalytics.cumulativePnl.size < 2) {
                    Text("At least two realized orders are needed to draw an equity curve.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val curveColor = if ((orderAnalytics.cumulativePnl.lastOrNull() ?: 0.0) >= 0.0)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    val zeroLineColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                        val values = orderAnalytics.cumulativePnl
                        val minValue = minOf(0.0, values.minOrNull() ?: 0.0)
                        val maxValue = maxOf(0.0, values.maxOrNull() ?: 0.0)
                        val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                        val points = values.mapIndexed { index, value ->
                            Offset(
                                x = if (values.size <= 1) 0f else size.width * index / (values.size - 1),
                                y = size.height - (((value - minValue) / range).toFloat() * size.height)
                            )
                        }
                        val zeroY = size.height - (((0.0 - minValue) / range).toFloat() * size.height)
                        drawLine(zeroLineColor, Offset(0f,zeroY), Offset(size.width,zeroY), 1.dp.toPx())
                        points.zipWithNext().forEach { (from,to) ->
                            drawLine(curveColor, from,to,strokeWidth=3.dp.toPx())
                        }
                    }
                }
                Text("Chronological realized P&L grouped by exchange order. Expectancy excludes fees; fees in non-stable assets are not converted. Drawdown is in quote-currency units, not a percentage of account equity.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text("${pair.realizedOrders} realized orders",
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
            val monthly = reconciledDaily.groupBy { it.date.take(7) }
                .toSortedMap(compareByDescending { it })
            if (monthly.isNotEmpty()) {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Monthly reconciled P&L", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        monthly.entries.take(12).forEach { (month, days) ->
                            val net = days.sumOf { it.selectedPnl }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(month, fontWeight = FontWeight.Medium)
                                    Text("${days.size} days · ${days.count { it.selectedPnl > 0 }} up · ${days.count { it.selectedPnl < 0 }} down",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text((if(net > 0) "+" else "") + net.usd(), fontWeight = FontWeight.SemiBold,
                                    color = pnlColor(net))
                            }
                        }
                    }
                }
            }
        }
        item {
            if (futuresIncomeTotals.isNotEmpty()) {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Futures income breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val labels = listOf(
                            "REALIZED_PNL" to "Realized trading P&L",
                            "COMMISSION" to "Trading commissions",
                            "FUNDING_FEE" to "Funding fees",
                            "INSURANCE_CLEAR" to "Insurance adjustments"
                        )
                        labels.forEach { (type, label) ->
                            val amount = futuresIncomeTotals[type] ?: 0.0
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text((if (amount > 0.0) "+" else "") + amount.usd(),
                                    color = pnlColor(amount), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        val ledgerTotal = futuresIncomeTotals.values.sum()
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Net Futures income")
                            Text((if (ledgerTotal > 0.0) "+" else "") + ledgerTotal.usd(), fontWeight = FontWeight.Bold,
                                color = pnlColor(ledgerTotal))
                        }
                        Text("Official Binance USDⓈ-M income history. Components are already included in the Futures daily P&L total above; do not add this breakdown again. Transfers are excluded.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("R-multiple analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (rMultipleSummary.sampleCount == 0) {
                        Text("R-multiple is unavailable: record a positive Risk USD amount for each journal trade. Exchange fills do not contain your original stop-loss risk, so R cannot be inferred safely.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnalyticsMetric("Average R / trade", rMultipleSummary.averageR?.let { "%+.2fR".format(Locale.US, it) } ?: "—",
                                Modifier.weight(1f), rMultipleSummary.averageR?.let { if (it >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error } ?: MaterialTheme.colorScheme.onSurface,
                                "Average net P&L ÷ recorded initial cash risk")
                            AnalyticsMetric("Total R", rMultipleSummary.totalR?.let { "%+.2fR".format(Locale.US, it) } ?: "—",
                                Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnalyticsMetric("R win rate", rMultipleSummary.winRatePercent?.let { "%.1f%%".format(Locale.US, it) } ?: "—",
                                Modifier.weight(1f))
                            AnalyticsMetric("Valid R samples", rMultipleSummary.sampleCount.toString(),
                                Modifier.weight(1f))
                        }
                        if (rMultipleSummary.missingRiskCount > 0) {
                            Text("${rMultipleSummary.missingRiskCount} journal trade(s) excluded because Risk USD is missing or invalid.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Text("R uses the journal's recorded initial cash risk and after-fee trade P&L. It is not calculated from leverage or margin.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Data quality checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val syncColor = if (staleSync || syncStatus.symbolsFailed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Text("Sync: ${syncStatus.state}", color = syncColor, style = MaterialTheme.typography.bodySmall)
                    Text(if(syncStatus.lastSuccess > 0L) "Last successful sync: " + SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(syncStatus.lastSuccess))
                        else "No successful Binance sync recorded.", style = MaterialTheme.typography.bodySmall)
                    if(staleSync) Text("Check: data may be stale (last success over 24 hours ago or never synced).", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    if(syncStatus.symbolsFailed > 0) Text("Check: ${syncStatus.symbolsFailed} symbol request(s) failed in the last attempt; imported history may be incomplete.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Realized P&L coverage: ${realizedCoverage?.let { "%.1f%%".format(Locale.US,it) } ?: "No fill data"} ($realizedFillCount / ${validFills.size} fills)",
                        style = MaterialTheme.typography.bodySmall)
                    if(invalidFillCount > 0) Text("Check: ${invalidFillCount} malformed fill row(s) excluded from analytics.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if(decisiveCount < 30) Text("Suggestion: treat win rate and expectancy as provisional until you have at least 30 realized close orders.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    val discrepancies = reconciledDaily.filter { (it.discrepancy ?: 0.0) > 1.0 }
                    if(discrepancies.isNotEmpty()) {
                        Text("P&L source mismatches over USD 1: ${discrepancies.size} day(s). Review the manual-vs-Binance records before trusting totals.",
                            color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                        discrepancies.takeLast(3).asReversed().forEach { row ->
                            Text("${row.date}: Binance ${row.binancePnl?.usd()} vs manual ${row.manualPnl?.usd()}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Futures realized-P&L coverage: ${futuresRealizedCoverage?.let { "%.1f%%".format(Locale.US,it) } ?: "No Futures fills"} ($futuresRealizedFills / ${futuresFills.size} Futures fills)",
                        style = MaterialTheme.typography.bodySmall)
                    if(futuresRealizedCoverage != null && futuresRealizedCoverage < 50.0) Text("Suggestion: check Futures income/realized-P&L imports; less than half of Futures fills currently carry realized P&L.",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val metricIcon = when {
                    label.contains("win", true) || label.contains("profit factor", true) -> Icons.Filled.TrendingUp
                    label.contains("loss", true) || label.contains("drawdown", true) || label.contains("worst", true) -> Icons.Filled.TrendingDown
                    label.contains("fee", true) || label.contains("order", true) || label.contains("volume", true) -> Icons.Filled.Receipt
                    label.contains("daily", true) || label.contains("P&L", true) || label.contains("pair", true) -> Icons.Filled.ShowChart
                    label.contains("balance", true) -> Icons.Filled.AccountBalanceWallet
                    else -> Icons.Filled.Percent
                }
                Icon(metricIcon, contentDescription = null, tint = valueColor, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
