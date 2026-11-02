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
    var filtersExpanded by remember { mutableStateOf(false) }
    var weeklyExpanded by remember { mutableStateOf(false) }
    var insightsExpanded by remember { mutableStateOf(false) }
    val expandedTrades = remember { mutableStateListOf<String>() }

    val filteredTrades = trades.filter { t ->
        (search.isBlank() || t.symbol.contains(search, true) || t.strategy.contains(search, true) || t.mistake.contains(search, true)) &&
            (sideFilter == "All" || t.side.equals(sideFilter, true)) &&
            (resultFilter == "All" || (resultFilter == "Wins" && t.pnl > 0) || (resultFilter == "Losses" && t.pnl < 0)) &&
            (fromDate.isBlank() || t.date >= fromDate) && (toDate.isBlank() || t.date <= toDate)
    }.sortedByDescending { it.date }
    val stats = computeTradeStats(filteredTrades)
    val validR = filteredTrades.filter { it.riskUsd.isFinite() && it.riskUsd > 0.0 && it.pnl.isFinite() }
    val averageR = if (validR.isEmpty()) null else validR.map { it.pnl / it.riskUsd }.average()
    val weekly = summarizeTradeTimeline(filteredTrades)
    val streak = currentTradeStreak(filteredTrades)
    val insights = analyzeTradeBehavior(filteredTrades)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Trade journal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("External trades · " + filteredTrades.size + " shown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add trade")
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Net P&L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stats.netPnl.usd(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = pnlColor(stats.netPnl))
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                            Text(stats.count.toString() + " trades", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        JournalMetric("Win rate", stats.winRate.pct(1), MaterialTheme.colorScheme.primary)
                        JournalMetric("Profit factor", if (stats.profitFactor.isInfinite()) "∞" else "%.2f".format(stats.profitFactor), MaterialTheme.colorScheme.onSurface)
                        JournalMetric("Max drawdown", stats.maxDrawdown.usd(), MaterialTheme.colorScheme.error)
                        JournalMetric("Avg. R", averageR?.let { "%.2fR".format(it) } ?: "—", averageR?.let { pnlColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Manual journal estimate after fees · Binance fills are separate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Trade history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stats.wins.toString() + " wins · " + stats.losses + " losses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { filtersExpanded = !filtersExpanded }) { Text(if (filtersExpanded) "Hide filters" else "Filter") }
                    }
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, placeholder = { Text("Search symbol, strategy or mistake") }
                    )
                    if (filtersExpanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("All", "Long", "Short").forEach { value ->
                                FilterChip(selected = sideFilter == value, onClick = { sideFilter = value }, label = { Text(value) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("All", "Wins", "Losses").forEach { value ->
                                FilterChip(selected = resultFilter == value, onClick = { resultFilter = value }, label = { Text(value) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(fromDate, { fromDate = it }, label = { Text("From YYYY-MM-DD") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(toDate, { toDate = it }, label = { Text("To YYYY-MM-DD") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        TextButton(onClick = { search = ""; sideFilter = "All"; resultFilter = "All"; fromDate = ""; toDate = "" }) { Text("Clear filters") }
                    }
                }
            }
        }
        if (filteredTrades.isEmpty()) {
            item {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No matching trades", style = MaterialTheme.typography.titleMedium)
                        Text("Clear filters or add a closed trade to start your journal.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { showAdd = true }) { Text("Add a trade") }
                    }
                }
            }
        } else {
            items(filteredTrades, key = { it.id }) { trade ->
                val isExpanded = expandedTrades.contains(trade.id)
                val tradePnl = trade.pnl
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = if (trade.side.equals("Long", true)) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(trade.side.uppercase(Locale.ROOT), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(trade.symbol.uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(trade.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(if (tradePnl.isFinite()) tradePnl.usd() else "Unavailable", color = if (tradePnl.isFinite()) pnlColor(tradePnl) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (trade.riskUsd > 0 && tradePnl.isFinite()) Text("%.2fR".format(tradePnl / trade.riskUsd), color = pnlColor(tradePnl), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Entry " + trade.entry.num() + "  →  Exit " + trade.exit.num(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { if (isExpanded) expandedTrades.remove(trade.id) else expandedTrades.add(trade.id) }) { Text(if (isExpanded) "Less" else "Details") }
                        }
                        if (isExpanded) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                JournalMetric("Quantity", trade.quantity.num(4), MaterialTheme.colorScheme.onSurface)
                                JournalMetric("Fees", trade.fees.usd(), MaterialTheme.colorScheme.onSurface)
                                JournalMetric("Planned risk", if (trade.riskUsd > 0) trade.riskUsd.usd() else "—", MaterialTheme.colorScheme.onSurface)
                            }
                            if (trade.strategy.isNotBlank()) Text("Strategy · " + trade.strategy, style = MaterialTheme.typography.bodySmall)
                            if (trade.mistake.isNotBlank()) Text("Mistake · " + trade.mistake, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (trade.notes.isNotBlank()) Text(trade.notes, style = MaterialTheme.typography.bodySmall)
                            if (trade.screenshotUri.isNotBlank()) {
                                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(trade.screenshotUri))) } }) { Text("View chart screenshot") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editingTrade = trade }) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("Edit")
                            }
                            TextButton(onClick = { onDelete(trade) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("Delete")
                            }
                        }
                    }
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Weekly performance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(if (streak.count == 0) "No current win/loss streak" else "Current streak · " + streak.count + " " + streak.kind.lowercase(Locale.getDefault()) + if (streak.count == 1) "" else "s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { weeklyExpanded = !weeklyExpanded }) { Text(if (weeklyExpanded) "Hide" else "View") }
                    }
                    if (weeklyExpanded) {
                        if (weekly.isEmpty()) Text("Add trades with a recognized date to see weekly results.", style = MaterialTheme.typography.bodySmall)
                        weekly.forEach { week ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Week of " + week.weekLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(week.tradeCount.toString() + " closed trades", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(week.netPnl.usd(), color = pnlColor(week.netPnl), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Journal insights", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Patterns in the trades currently shown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { insightsExpanded = !insightsExpanded }) { Text(if (insightsExpanded) "Hide" else "View") }
                    }
                    if (insightsExpanded) {
                        if (insights.tradeCount < 3) {
                            Text("Record at least 3 trades to compare patterns. Current sample: " + insights.tradeCount + ".", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val strongest = insights.strategies.firstOrNull { it.sampleSize >= 3 }
                            if (strongest != null) {
                                Text("Top net strategy · " + strongest.strategy, fontWeight = FontWeight.SemiBold)
                                Text(strongest.sampleSize.toString() + " trades · " + strongest.netPnl.usd() + " · Win rate " + strongest.winRate.pct(1), style = MaterialTheme.typography.bodySmall)
                            } else Text("Use the same strategy tag on at least 3 trades to compare results.", style = MaterialTheme.typography.bodySmall)
                            insights.mistakeCounts.entries.firstOrNull()?.let { Text("Most-recorded mistake · " + it.key + " (" + it.value + ")", style = MaterialTheme.typography.bodySmall) }
                            insights.largestLoss?.let { Text("Largest recorded loss · " + it.symbol + " · " + it.pnl.usd(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Descriptive patterns only; small samples are not predictions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { BinancePositionJournalSection(exchangeFills, onSyncExchange) }
        item {
            OutlinedButton(onClick = {
                val csv = tradesToCsv(trades)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Risk Tracker trade journal")
                    putExtra(Intent.EXTRA_TEXT, csv)
                }
                context.startActivity(Intent.createChooser(send, "Export trade journal CSV"))
            }, modifier = Modifier.fillMaxWidth()) { Text("Export journal CSV") }
        }
        item {
            Text("External trades are stored locally and kept separate from Binance imports. P&L is a journal estimate, not exchange-reported performance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (editingTrade != null) {
        AddTradeDialog(maxTradeRisk, initialTrade = editingTrade, onDismiss = { editingTrade = null }) { trade ->
            onUpdate(trade); editingTrade = null
        }
    } else if (showAdd) {
        AddTradeDialog(maxTradeRisk, onDismiss = { showAdd = false }) { trade ->
            onAdd(trade); showAdd = false
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
