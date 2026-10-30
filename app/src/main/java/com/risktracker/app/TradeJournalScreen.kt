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
import androidx.compose.material.icons.filled.Edit
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
    onUpdate: (TradeEntry) -> Unit,
    onDelete: (TradeEntry) -> Unit,
    maxTradeRisk: Double = 5.0,
    exchangeFills: List<BinanceJournalFill> = emptyList(),
    onSyncExchange: () -> Unit = {}
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var editingTrade by remember { mutableStateOf<TradeEntry?>(null) }
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
                    Text("Manual / external trades only · estimates from recorded prices and fees. Do not re-enter Binance-imported executions.", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("External")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Manual / external performance", style = MaterialTheme.typography.titleMedium)
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
            val weekly = summarizeTradeTimeline(filteredTrades)
            val streak = currentTradeStreak(filteredTrades)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Weekly performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (streak.count == 0) "Current streak: no wins or losses recorded yet"
                        else "Current streak: ${streak.count} ${streak.kind.lowercase(Locale.getDefault())}${if (streak.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (weekly.isEmpty()) {
                        Text("Add trades with a recognized date to see weekly results.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        weekly.forEach { week ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Week of ${week.weekLabel}", fontWeight = FontWeight.Medium)
                                    Text("${week.tradeCount} closed trade${if (week.tradeCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(week.netPnl.usd(), color = pnlColor(week.netPnl), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text("Based on manually recorded closed trades currently matching your filters. Exchange fills are not included as closed trades.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item { BinancePositionJournalSection(exchangeFills, onSyncExchange) }
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
                        Text("No manual / external trades", style = MaterialTheme.typography.titleMedium)
                        Text("Binance executions are grouped automatically above. Add a trade here only if it happened outside the connected Binance account.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showAdd = true }) { Text("Add external trade") }
                    }
                }
            }
        } else {
            item { Text("Manual / external closed trades", style = MaterialTheme.typography.titleMedium) }
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
                            Row {
                                IconButton(onClick = { editingTrade = trade }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit trade")
                                }
                                IconButton(onClick = { onDelete(trade) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete trade")
                                }
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
    if (editingTrade != null) {
        AddTradeDialog(maxTradeRisk, initialTrade = editingTrade, onDismiss = { editingTrade = null }) { trade ->
            onUpdate(trade)
            editingTrade = null
        }
    } else if (showAdd) {
        AddTradeDialog(maxTradeRisk, onDismiss = { showAdd = false }) { trade ->
            onAdd(trade)
            showAdd = false
        }
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
private fun AddTradeDialog(
    maxTradeRisk: Double,
    initialTrade: TradeEntry? = null,
    onDismiss: () -> Unit,
    onAdd: (TradeEntry) -> Unit
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var date by remember(initialTrade) { mutableStateOf(initialTrade?.date ?: today) }
    var symbol by remember(initialTrade) { mutableStateOf(initialTrade?.symbol ?: "") }
    var side by remember(initialTrade) { mutableStateOf(initialTrade?.side ?: "Long") }
    var entry by remember(initialTrade) { mutableStateOf(initialTrade?.entry?.toString() ?: "") }
    var exit by remember(initialTrade) { mutableStateOf(initialTrade?.exit?.toString() ?: "") }
    var quantity by remember(initialTrade) { mutableStateOf(initialTrade?.quantity?.toString() ?: "1") }
    var fees by remember(initialTrade) { mutableStateOf(initialTrade?.fees?.toString() ?: "0") }
    var notes by remember(initialTrade) { mutableStateOf(initialTrade?.notes ?: "") }
    var strategy by remember(initialTrade) { mutableStateOf(initialTrade?.strategy ?: "") }
    var mistake by remember(initialTrade) { mutableStateOf(initialTrade?.mistake ?: "") }
    var riskUsd by remember(initialTrade) { mutableStateOf(initialTrade?.riskUsd?.toString() ?: "") }
    var screenshotUri by remember(initialTrade) { mutableStateOf(initialTrade?.screenshotUri ?: "") }
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
        title = { Text(if (initialTrade == null) "Record closed trade" else "Edit closed trade") },
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
                    id = initialTrade?.id ?: "${System.currentTimeMillis()}-${symbol.trim().uppercase(Locale.ROOT)}",
                    date = date.trim(), symbol = symbol.trim().uppercase(Locale.ROOT), side = side,
                    entry = e!!, exit = x!!, quantity = q!!, fees = fee!!, notes = notes.trim(),
                    strategy = strategy.trim(), mistake = mistake.trim(), riskUsd = plannedRisk, screenshotUri = screenshotUri
                ))
            }) { Text(if (initialTrade == null) "Save trade" else "Save changes") }
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
