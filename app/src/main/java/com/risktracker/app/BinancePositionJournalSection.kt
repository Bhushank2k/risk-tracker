package com.risktracker.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BinancePositionJournalSection(fills: List<BinanceJournalFill>) {
    val cycles = remember(fills) { BinancePositionJournal.futuresCycles(fills) }
    val spotGroups = remember(fills) { BinancePositionJournal.spotActivity(fills) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var symbolQuery by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("All") }
    var outcome by remember { mutableStateOf("All") }
    var periodDays by remember { mutableStateOf<Int?>(null) }
    var sort by remember { mutableStateOf("Latest") }
    var showFilters by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val cutoff = periodDays?.let { now - it.toLong() * 24L * 60L * 60L * 1000L }
    val query = symbolQuery.trim().uppercase(Locale.ROOT)
    val filteredCycles = remember(cycles, query, outcome, periodDays, sort, now) {
        cycles.asSequence()
            .filter { query.isBlank() || it.symbol.uppercase(Locale.ROOT).contains(query) }
            .filter { cutoff == null || it.endTime >= cutoff }
            .filter {
                when (outcome) {
                    "Open" -> it.isUnclosed
                    "Closed" -> !it.isUnclosed
                    "Winners" -> !it.isUnclosed && (it.realizedPnl ?: 0.0) > 0.0
                    "Losers" -> !it.isUnclosed && (it.realizedPnl ?: 0.0) < 0.0
                    else -> true
                }
            }
            .let { rows ->
                when (sort) {
                    "Profit" -> rows.sortedByDescending { it.realizedPnl ?: Double.NEGATIVE_INFINITY }
                    "Loss" -> rows.sortedBy { it.realizedPnl ?: Double.POSITIVE_INFINITY }
                    else -> rows.sortedByDescending { it.endTime }
                }
            }.toList()
    }
    val filteredSpot = remember(spotGroups, query, periodDays, now) {
        spotGroups.filter { group ->
            (query.isBlank() || group.symbol.uppercase(Locale.ROOT).contains(query)) &&
                (cutoff == null || (group.fills.maxOfOrNull { it.time } ?: 0L) >= cutoff)
        }
    }
    val closedWithPnl = cycles.filter { !it.isUnclosed && it.realizedPnl != null }
    val winners = closedWithPnl.count { (it.realizedPnl ?: 0.0) > 0.0 }
    val winRate = if (closedWithPnl.isEmpty()) null else winners * 100.0 / closedWithPnl.size
    val netRealized = closedWithPnl.sumOf { it.realizedPnl ?: 0.0 }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    HistoryMetric("Positions", cycles.size.toString())
                    HistoryMetric("Win", winRate?.let { it.pct(0) } ?: "—")
                    HistoryMetric("Open", cycles.count { it.isUnclosed }.toString())
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(netRealized.usd(), color = pnlColor(netRealized), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Net realized", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedTextField(
            value = symbolQuery,
            onValueChange = { symbolQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search symbol") },
            placeholder = { Text("e.g. BTCUSDT") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showFilters = !showFilters }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (showFilters) "Hide filters" else "Filters  ·  ${if (market != "All" || outcome != "All" || periodDays != null || sort != "Latest") "Active" else "Optional"}")
            }
        }
        if (showFilters) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Futures", "Spot").forEach { value ->
                    FilterChip(selected = market == value, onClick = { market = value }, label = { Text(value) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Open", "Closed", "Winners", "Losers").forEach { value ->
                    FilterChip(selected = outcome == value, onClick = { outcome = value }, label = { Text(value) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All" to null, "7D" to 7, "30D" to 30).forEach { (label, days) ->
                    FilterChip(selected = periodDays == days, onClick = { periodDays = days }, label = { Text(label) })
                }
                listOf("Latest", "Profit", "Loss").forEach { value ->
                    FilterChip(selected = sort == value, onClick = { sort = value }, label = { Text(value) })
                }
            }
        }

        if (market != "Spot" && filteredCycles.isNotEmpty()) {
            Text("USDⓈ-M Futures · ${filteredCycles.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            filteredCycles.take(100).forEach { cycle ->
                val isExpanded = cycle.key in expanded
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("${cycle.symbol}  ·  ${cycle.direction}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("${if (cycle.isUnclosed) "Open / partial" else "Closed"} · ${cycle.fillCount} fills · ${timeLabel(cycle.startTime)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(cycle.realizedPnl?.let { (if (it > 0) "+" else "") + it.usd() } ?: "P&L —",
                                    color = cycle.realizedPnl?.let { pnlColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(if (cycle.isUnclosed) "Remaining ${cycle.unclosedQuantity.num(5)}" else "Realized", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Entry ${cycle.averageEntryPrice?.num(5) ?: "—"}", style = MaterialTheme.typography.labelSmall)
                            Text("Exit ${cycle.averageExitPrice?.num(5) ?: "—"}", style = MaterialTheme.typography.labelSmall)
                            Text("${cycle.closedQuantity.num(4)}/${cycle.openedQuantity.num(4)} qty", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { expanded = if (isExpanded) expanded - cycle.key else expanded + cycle.key }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (isExpanded) "Hide details" else "Details · ${cycle.fills.size} executions")
                        }
                        if (isExpanded) {
                            Text("${timeLabel(cycle.startTime)} → ${timeLabel(cycle.endTime)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cycle.firstObservedCycle) Text("Direction inferred from the first imported fill; earlier history may be missing.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cycle.feeTotals.isNotEmpty()) Text("Fees: " + cycle.feeTotals.entries.joinToString(" · ") { "${it.value.num(6)} ${it.key}" }, style = MaterialTheme.typography.bodySmall)
                            cycle.fills.forEach { fill ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text("${fill.side} · ${fill.quantity.num(6)} @ ${fill.price.num(6)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text("${timeLabel(fill.time)} · Order ${fill.orderId.ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    fill.realizedPnl?.let { Text(it.usd(), style = MaterialTheme.typography.bodySmall, color = pnlColor(it)) }
                                }
                            }
                        }
                    }
                }
            }
            if (filteredCycles.size > 100) Text("Showing 100 of ${filteredCycles.size} position cycles.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (market != "Spot") {
            Text("No Futures positions match these filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (market != "USDⓈ-M Futures" && filteredSpot.isNotEmpty()) {
            Text("Spot activity · ${filteredSpot.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            filteredSpot.take(20).forEach { group ->
                val key = "spot:${group.symbol}"
                val isExpanded = key in expanded
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(group.symbol, fontWeight = FontWeight.SemiBold)
                                Text("${group.fills.size} fills · Bought ${group.baseQuantityBought.num(4)} · Sold ${group.baseQuantitySold.num(4)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(group.quoteVolume.num(), fontWeight = FontWeight.SemiBold)
                                Text("Quote turnover", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { expanded = if (isExpanded) expanded - key else expanded + key }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (isExpanded) "Hide fills" else "View fills")
                        }
                        if (isExpanded) {
                            if (group.feeTotals.isNotEmpty()) Text("Fees: " + group.feeTotals.entries.joinToString(" · ") { "${it.value.num(6)} ${it.key}" }, style = MaterialTheme.typography.bodySmall)
                            group.fills.asReversed().forEach { fill ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("${fill.side} · ${fill.quantity.num(6)} @ ${fill.price.num(6)} · ${timeLabel(fill.time)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else if (market != "USDⓈ-M Futures") {
            Text("No Spot activity matches this search and date range.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Spot is activity, not closed-position P&L. Futures cycles may be incomplete if earlier fills are missing or the account uses hedge mode; fees remain in original assets.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun timeLabel(time: Long): String =
    SimpleDateFormat("dd MMM yy, HH:mm", Locale.getDefault()).format(Date(time))
