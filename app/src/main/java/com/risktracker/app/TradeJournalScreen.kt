package com.risktracker.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TradeJournalScreen(
    trades: List<TradeEntry>,
    onAdd: (TradeEntry) -> Unit,
    onDelete: (TradeEntry) -> Unit,
    maxTradeRisk: Double = 5.0,
    exchangeFills: List<BinanceJournalFill> = emptyList(),
    onSyncExchange: () -> Unit = {}
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var sideFilter by remember { mutableStateOf("All") }
    var resultFilter by remember { mutableStateOf("All") }
    var fromDate by remember { mutableStateOf("") }
    var toDate by remember { mutableStateOf("") }
    val filteredTrades = trades.filter { t ->
        (search.isBlank() || t.symbol.contains(search, true) || t.strategy.contains(search, true) || t.mistake.contains(search, true)) &&
        (sideFilter == "All" || t.side.equals(sideFilter, true)) &&
        (resultFilter == "All" || (resultFilter == "Wins" && t.pnl > 0) || (resultFilter == "Losses" && t.pnl < 0)) &&
        (fromDate.isBlank() || t.date >= fromDate) && (toDate.isBlank() || t.date <= toDate)
    }
    val stats = computeTradeStats(filteredTrades)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Trade journal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Closed trades · estimates from your recorded prices and fees", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Performance", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        JournalMetric("Net P&L", stats.netPnl.usd(), pnlColor(stats.netPnl))
                        JournalMetric("Win rate", stats.winRate.pct(1), MaterialTheme.colorScheme.primary)
                        JournalMetric("Trades", stats.count.toString(), MaterialTheme.colorScheme.onSurface)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        JournalMetric("Wins / losses", "${stats.wins} / ${stats.losses}", MaterialTheme.colorScheme.onSurface)
                        JournalMetric("Profit factor", if (stats.profitFactor.isInfinite()) "∞" else "%.2f".format(stats.profitFactor), MaterialTheme.colorScheme.onSurface)
                        JournalMetric("Max drawdown", stats.maxDrawdown.usd(), MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            val insights = analyzeTradeBehavior(filteredTrades)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Personalized journal insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (insights.tradeCount < 3) {
                        Text("Record at least 3 trades to start comparing patterns. Current sample: ${insights.tradeCount}.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val strongest = insights.strategies.firstOrNull { it.sampleSize >= 3 }
                        if (strongest != null) {
                            Text("Top net strategy: ${strongest.strategy}", fontWeight = FontWeight.SemiBold)
                            Text("${strongest.sampleSize} trades · Net ${strongest.netPnl.usd()} · Win rate ${strongest.winRate.pct(1)}")
                        } else {
                            Text("Add the same strategy tag to at least 3 trades to compare strategy results.")
                        }
                        val commonMistake = insights.mistakeCounts.entries.firstOrNull()
                        if (commonMistake != null) {
                            Text("Most-recorded mistake: ${commonMistake.key} (${commonMistake.value} ${if (commonMistake.value == 1) "trade" else "trades"}).")
                        } else {
                            Text("No mistake tags recorded yet. Tagging mistakes can reveal recurring patterns.")
                        }
                        insights.largestLoss?.let { loss ->
                            Text("Largest recorded loss: ${loss.symbol} on ${loss.date} (${loss.pnl.usd()}).", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("Descriptive patterns from the trades currently shown by your filters; small samples are not reliable predictions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Exchange execution journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${exchangeFills.size} imported fills · Binance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onSyncExchange) { Text("Sync now") }
                    }
                    Text("Exchange fills are stored separately from manually recorded closed trades. Each row retains its exchange trade ID, timestamp and fee. Futures realized P&L is reported by Binance; Spot fills are not assigned an invented closed-trade P&L.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (exchangeFills.isEmpty()) {
                        Text("No exchange fills imported yet. Connect Binance under Tools → Binance trade history, then preview a historical range or wait for automatic sync.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        exchangeFills.take(100).forEach { fill ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${fill.symbol} · ${fill.side}", fontWeight = FontWeight.SemiBold)
                                    Text("${fill.market} · " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(fill.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Qty ${fill.quantity.num(6)} @ ${fill.price.num(6)} · Notional ${fill.quoteAmount.usd()}", style = MaterialTheme.typography.bodySmall)
                                    Text("Fee ${fill.fee.num(6)} ${fill.feeAsset} · Order ${fill.orderId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    fill.realizedPnl?.let { Text(it.usd(), color = pnlColor(it), fontWeight = FontWeight.SemiBold) }
                                    Text("Trade ID " + fill.id.substringAfterLast(":"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (exchangeFills.size > 100) Text("Showing the latest 100 of ${exchangeFills.size} fills. Export support for exchange fills can be added separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val csv = tradesToCsv(trades)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "Risk Tracker trade journal")
                        putExtra(Intent.EXTRA_TEXT, csv)
                    }
                    context.startActivity(Intent.createChooser(send, "Export trade journal CSV"))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export journal CSV") }
        }
        item {
            OutlinedTextField(search, { search = it }, label = { Text("Search symbol, strategy or mistake") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Long", "Short").forEach { v -> FilterChip(selected = sideFilter == v, onClick = { sideFilter = v }, label = { Text(v) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Wins", "Losses").forEach { v -> FilterChip(selected = resultFilter == v, onClick = { resultFilter = v }, label = { Text(v) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fromDate, { fromDate = it }, label = { Text("From date") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(toDate, { toDate = it }, label = { Text("To date") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
        if (filteredTrades.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your journal starts here", style = MaterialTheme.typography.titleMedium)
                        Text("Add a closed trade to see net P&L, win rate, profit factor and drawdown.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showAdd = true }) { Text("Add first trade") }
                    }
                }
            }
        } else {
            item { Text("Closed trades", style = MaterialTheme.typography.titleMedium) }
            items(filteredTrades, key = { it.id }) { trade ->
                val tradePnl = trade.pnl
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${trade.symbol.uppercase(Locale.ROOT)} · ${trade.side}", fontWeight = FontWeight.Bold)
                            Text("${trade.date} · Entry ${trade.entry.num()} → Exit ${trade.exit.num()}", style = MaterialTheme.typography.bodySmall)
                            Text("Qty ${trade.quantity.num(4)} · Fees ${trade.fees.usd()}", style = MaterialTheme.typography.bodySmall)
                            if (trade.strategy.isNotBlank()) Text("Strategy: " + trade.strategy, style = MaterialTheme.typography.bodySmall)
                            if (trade.mistake.isNotBlank()) Text("Mistake: " + trade.mistake, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (trade.riskUsd > 0) Text("Planned risk: " + trade.riskUsd.usd(), style = MaterialTheme.typography.bodySmall)
                            if (trade.screenshotUri.isNotBlank()) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(trade.screenshotUri))) } }) { Text("View chart screenshot") }
                            if (trade.notes.isNotBlank()) Text(trade.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (tradePnl.isFinite()) tradePnl.usd() else "P&L unavailable (invalid side)",
                                color = if (tradePnl.isFinite()) pnlColor(tradePnl) else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { onDelete(trade) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete trade")
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Journal records are stored locally on this device and are separate from Daily PNL / Google Sheets sync. P&L excludes funding, slippage and exchange-specific contract rules.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showAdd) AddTradeDialog(maxTradeRisk, onDismiss = { showAdd = false }) { trade ->
        onAdd(trade)
        showAdd = false
    }
}

@Composable
private fun JournalMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.widthIn(min = 72.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddTradeDialog(maxTradeRisk: Double, onDismiss: () -> Unit, onAdd: (TradeEntry) -> Unit) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var date by remember { mutableStateOf(today) }
    var symbol by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("Long") }
    var entry by remember { mutableStateOf("") }
    var exit by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var fees by remember { mutableStateOf("0") }
    var notes by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("") }
    var mistake by remember { mutableStateOf("") }
    var riskUsd by remember { mutableStateOf("") }
    var screenshotUri by remember { mutableStateOf("") }
    var riskAcknowledged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            screenshotUri = uri.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record closed trade") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    JournalField("Date (YYYY-MM-DD)", date, { date = it })
                    JournalField("Symbol (e.g. BTCUSDT)", symbol, { symbol = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = side == "Long", onClick = { side = "Long" }, label = { Text("Long") })
                        FilterChip(selected = side == "Short", onClick = { side = "Short" }, label = { Text("Short") })
                    }
                    JournalField("Entry price", entry, { entry = it }, true)
                    JournalField("Exit price", exit, { exit = it }, true)
                    JournalField("Quantity / contracts", quantity, { quantity = it }, true)
                    JournalField("Fees (quote currency)", fees, { fees = it }, true)
                    JournalField("Strategy tag", strategy, { strategy = it })
                    JournalField("Mistake tag", mistake, { mistake = it })
                    JournalField("Planned risk (USD)", riskUsd, { riskUsd = it }, true)
                    OutlinedButton(onClick = { screenshotPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (screenshotUri.isBlank()) "Attach chart screenshot" else "Chart screenshot selected")
                    }
                    JournalField("Notes (optional)", notes, { notes = it })
                }
                val plannedRisk = riskUsd.toDoubleOrNull() ?: 0.0
                if (plannedRisk > maxTradeRisk) {
                    Text("Risk warning: " + plannedRisk.usd() + " exceeds your " + maxTradeRisk.usd() + " limit.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = riskAcknowledged, onCheckedChange = { riskAcknowledged = it })
                        Text("I understand and want to save")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = entry.toDoubleOrNull()
                val x = exit.toDoubleOrNull()
                val q = quantity.toDoubleOrNull()
                val fee = fees.toDoubleOrNull()
                val plannedRisk = riskUsd.toDoubleOrNull() ?: 0.0
                error = when {
                    symbol.isBlank() -> "Enter a symbol."
                    date.isBlank() -> "Enter a date."
                    e == null || x == null || e <= 0 || x <= 0 -> "Entry and exit must be positive prices."
                    q == null || q <= 0 -> "Quantity must be greater than zero."
                    fee == null || fee < 0 -> "Fees cannot be negative."
                    riskUsd.isNotBlank() && (riskUsd.toDoubleOrNull() == null || riskUsd.toDoubleOrNull()!! < 0) -> "Planned risk must be zero or greater."
                    plannedRisk > maxTradeRisk && !riskAcknowledged -> "Confirm the risk warning to save."
                    else -> null
                }
                if (error == null) onAdd(TradeEntry(
                    id = "${System.currentTimeMillis()}-${symbol.trim().uppercase(Locale.ROOT)}",
                    date = date.trim(), symbol = symbol.trim().uppercase(Locale.ROOT), side = side,
                    entry = e!!, exit = x!!, quantity = q!!, fees = fee!!, notes = notes.trim(),
                    strategy = strategy.trim(), mistake = mistake.trim(), riskUsd = plannedRisk, screenshotUri = screenshotUri
                ))
            }) { Text("Save trade") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun JournalField(label: String, value: String, onValue: (String) -> Unit, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}
