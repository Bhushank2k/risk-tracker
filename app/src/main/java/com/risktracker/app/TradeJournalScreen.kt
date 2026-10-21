package com.risktracker.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun TradeJournalScreen(
    trades: List<TradeEntry>,
    onAdd: (TradeEntry) -> Unit,
    onDelete: (TradeEntry) -> Unit
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    val stats = computeTradeStats(trades)
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
                        JournalMetric("Max drawdown", stats.maxDrawdown.usd(), Red)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val csv = buildString {
                        append("Date,Symbol,Side,Entry,Exit,Quantity,Fees,Net PNL,Notes\n")
                        trades.sortedBy { it.date }.forEach { t ->
                            val safeNotes = t.notes.replace("\"", "\"\"")
                            append("\"${t.date}\",\"${t.symbol}\",\"${t.side}\",${t.entry},${t.exit},${t.quantity},${t.fees},${t.pnl},\"${safeNotes}\"\n")
                        }
                    }
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
        if (trades.isEmpty()) {
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
            items(trades, key = { it.id }) { trade ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${trade.symbol.uppercase(Locale.ROOT)} · ${trade.side}", fontWeight = FontWeight.Bold)
                            Text("${trade.date} · Entry ${trade.entry.num()} → Exit ${trade.exit.num()}", style = MaterialTheme.typography.bodySmall)
                            Text("Qty ${trade.quantity.num(4)} · Fees ${trade.fees.usd()}", style = MaterialTheme.typography.bodySmall)
                            if (trade.notes.isNotBlank()) Text(trade.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(trade.pnl.usd(), color = pnlColor(trade.pnl), fontWeight = FontWeight.Bold)
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
    if (showAdd) AddTradeDialog(onDismiss = { showAdd = false }) { trade ->
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
private fun AddTradeDialog(onDismiss: () -> Unit, onAdd: (TradeEntry) -> Unit) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var date by remember { mutableStateOf(today) }
    var symbol by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("Long") }
    var entry by remember { mutableStateOf("") }
    var exit by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var fees by remember { mutableStateOf("0") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
                    JournalField("Notes (optional)", notes, { notes = it })
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
                error = when {
                    symbol.isBlank() -> "Enter a symbol."
                    date.isBlank() -> "Enter a date."
                    e == null || x == null || e <= 0 || x <= 0 -> "Entry and exit must be positive prices."
                    q == null || q <= 0 -> "Quantity must be greater than zero."
                    fee == null || fee < 0 -> "Fees cannot be negative."
                    else -> null
                }
                if (error == null) onAdd(TradeEntry(
                    id = "${System.currentTimeMillis()}-${symbol.trim().uppercase(Locale.ROOT)}",
                    date = date.trim(), symbol = symbol.trim().uppercase(Locale.ROOT), side = side,
                    entry = e!!, exit = x!!, quantity = q!!, fees = fee!!, notes = notes.trim()
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
