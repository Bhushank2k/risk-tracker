package com.risktracker.app

import androidx.compose.foundation.layout.*
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
fun BinancePositionJournalSection(
    fills: List<BinanceJournalFill>,
    onSync: () -> Unit
) {
    val cycles = remember(fills) { BinancePositionJournal.futuresCycles(fills) }
    val spotGroups = remember(fills) { BinancePositionJournal.spotActivity(fills) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val realized = cycles.mapNotNull { it.realizedPnl }.sum()
    val closed = cycles.count { !it.isUnclosed }
    val unclosed = cycles.count { it.isUnclosed }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Binance position journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${fills.size} saved executions · Binance source of truth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onSync) { Text("Sync now") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PositionJournalMetric("Position cycles", cycles.size.toString())
                PositionJournalMetric("Unclosed in history", unclosed.toString())
                PositionJournalMetric("Closed cycles", closed.toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Exchange-reported realized P&L", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(realized.usd(), color = pnlColor(realized), fontWeight = FontWeight.Bold)
            }
            Text("Futures positions are reconstructed from chronological fills using one-way net exposure. Partial entries/exits are grouped; a reversal starts a new cycle. This history cannot reliably reconstruct hedge-mode positions because Binance positionSide is not stored in imported fills.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (cycles.isEmpty()) {
                Text("No USDⓈ-M Futures position cycles found yet. Sync Binance or import historical executions to build this journal.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("USDⓈ-M Futures positions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                cycles.take(100).forEach { cycle ->
                    val isExpanded = cycle.key in expanded
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${cycle.symbol} · ${cycle.direction}", fontWeight = FontWeight.SemiBold)
                                    Text("${if (cycle.isUnclosed) "Unclosed in imported history" else "Closed position"} · ${cycle.fillCount} fills", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                cycle.realizedPnl?.let {
                                    Text(it.usd(), color = pnlColor(it), fontWeight = FontWeight.Bold)
                                } ?: Text("P&L unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Opened ${cycle.openedQuantity.num(5)}", style = MaterialTheme.typography.bodySmall)
                                Text("Closed ${cycle.closedQuantity.num(5)}", style = MaterialTheme.typography.bodySmall)
                                if (cycle.isUnclosed) Text("Remaining ${cycle.unclosedQuantity.num(5)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Avg entry ${cycle.averageEntryPrice?.num(5) ?: "—"} · Avg exit ${cycle.averageExitPrice?.num(5) ?: "—"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "${timeLabel(cycle.startTime)} → ${timeLabel(cycle.endTime)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cycle.feeTotals.isNotEmpty()) {
                                Text("Fees: " + cycle.feeTotals.entries.joinToString(" · ") { "${it.value.num(6)} ${it.key}" }, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                expanded = if (isExpanded) expanded - cycle.key else expanded + cycle.key
                            }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (isExpanded) "Hide executions" else "View ${cycle.fills.size} executions")
                            }
                            if (isExpanded) {
                                cycle.fills.forEach { fill ->
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                if (cycles.size > 100) Text("Showing 100 of ${cycles.size} position cycles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (spotGroups.isNotEmpty()) {
                HorizontalDivider()
                Text("Spot execution activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Spot buys and sells are grouped by pair, but not labeled as closed positions or assigned realized P&L. Deposits, withdrawals and inventory held before the imported date range can change the true position cost basis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                spotGroups.take(20).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.symbol, fontWeight = FontWeight.SemiBold)
                            Text("${group.fills.size} fills · Bought ${group.baseQuantityBought.num(5)} · Sold ${group.baseQuantitySold.num(5)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(group.quoteVolume.usd(), fontWeight = FontWeight.SemiBold)
                    }
                    if (group.feeTotals.isNotEmpty()) Text("Fees: " + group.feeTotals.entries.joinToString(" · ") { "${it.value.num(6)} ${it.key}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Unclosed means the imported execution sequence has not returned to zero net quantity; it does not confirm the position is currently open. Results depend on imported symbols and date coverage. Fees remain in their original assets and are not converted.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PositionJournalMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun timeLabel(time: Long): String =
    SimpleDateFormat("dd MMM yy, HH:mm", Locale.getDefault()).format(Date(time))
