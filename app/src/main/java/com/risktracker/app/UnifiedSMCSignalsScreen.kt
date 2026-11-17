package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

private data class UnifiedSMCRow(
    val symbol: String, val side: String, val horizon: String, val score: Int,
    val entryLow: Double, val entryHigh: Double, val stop: Double,
    val target: Double, val rr: Double, val sources: Set<String>,
    val patterns: Set<String>, val status: String, val explanation: String,
    val id: String
)

@Composable
internal fun UnifiedSMCSignalsScreen(onOpenTradeIdeas: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf(SMCScanStateStore.loadResults(context)) }
    var advanced by remember { mutableStateOf(AdvancedSMCSignalStore.load(context)) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("Ready · SMC reuses the shared Daily Trade Ideas market-data pipeline.") }
    var scanned by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("All") }
    var horizon by remember { mutableStateOf("All") }
    var expanded by remember { mutableStateOf<String?>(null) }

    val rows = remember(core, advanced) {
        val merged = linkedMapOf<String, UnifiedSMCRow>()
        core.forEach { x ->
            val key = "${x.symbol}|${x.side}|${x.horizon}"
            val row = UnifiedSMCRow(x.symbol, x.side, x.horizon, x.score, x.entryLow, x.entryHigh,
                x.stop, x.target, x.rewardRisk, setOf("Core SMC"),
                setOf("BOS / liquidity sweep / displacement / OB-FVG"),
                "PATTERN DETECTED · EXECUTION CHECK REQUIRED",
                listOf(x.structure, x.sweep, x.displacement, x.zoneDescription, x.location).joinToString("\n"),
                key)
            merged[key] = row
        }
        advanced.forEach { x ->
            val key = "${x.symbol}|${x.side}|${x.horizon}"
            val previous = merged[key]
            if (previous == null) {
                merged[key] = UnifiedSMCRow(x.symbol, x.side, x.horizon, x.score, x.entryLow, x.entryHigh,
                    x.stop, x.target1, x.rewardRisk, setOf("Advanced SMC"), setOf(x.signalType),
                    "PATTERN DETECTED · EXECUTION CHECK REQUIRED",
                    x.explanation + "\n" + x.confirmation, key)
            } else {
                merged[key] = previous.copy(score = maxOf(previous.score, x.score),
                    sources = previous.sources + "Advanced SMC", patterns = previous.patterns + x.signalType,
                    explanation = previous.explanation + "\n" + x.explanation + "\n" + x.confirmation)
            }
        }
        merged.values.sortedWith(compareByDescending<UnifiedSMCRow> { it.score }.thenBy { it.symbol })
    }
    val visible = remember(rows, filter, horizon) {
        rows.filter { (filter == "All" || it.side == filter) && (horizon == "All" || it.horizon == horizon) }
    }

    fun scan() {
        if (loading) return
        loading = true
        scanned = 0
        total = 0
        scope.launch {
            try {
                progress = "Starting SMC scan from the shared market-data pipeline…"
                val result = BinanceMarketScanner.scanUnifiedSmc(
                    progress = { progress = it },
                    onProgress = { done, count -> scanned = done; total = count }
                )
                core = result.core
                advanced = result.advanced
                SMCScanStateStore.saveResults(context, result.core)
                AdvancedSMCSignalStore.save(context, result.advanced)
                progress = "Unified scan complete · ${result.core.size} Core + ${result.advanced.size} Advanced candidates."
            } catch (e: Exception) {
                progress = "Scan incomplete: ${e.message ?: "market data unavailable"}"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) {
        if (core.isEmpty() && advanced.isEmpty()) scan()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Unified SMC Signals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Core + Advanced · shared Daily + SMC market data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scan() }, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Run unified SMC scan")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "LONG", "SHORT").forEach {
                FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Scalp", "Intraday", "Swing").forEach {
                FilterChip(selected = horizon == it, onClick = { horizon = it }, label = { Text(it) })
            }
        }
        if (loading || total > 0) {
            Text(progress, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (total > 0) (scanned.toFloat() / total).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("$scanned / $total eligible SMC markets checked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${visible.size} combined signals · ${rows.count { it.sources.size > 1 }} confluence matches",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        visible.forEach { row ->
            val open = expanded == row.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${row.side} · ${row.horizon}", style = MaterialTheme.typography.labelMedium,
                                color = if (row.side == "LONG") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("${row.score}/100", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.sources.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                    row.patterns.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(row.status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Entry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${row.entryLow.unifiedPrice()} – ${row.entryHigh.unifiedPrice()}", fontWeight = FontWeight.SemiBold) }
                        Column { Text("Stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(row.stop.unifiedPrice(), fontWeight = FontWeight.SemiBold) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(row.target.unifiedPrice(), fontWeight = FontWeight.SemiBold) }
                        Column { Text("Gross R:R", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("1:${"%.2f".format(Locale.US, row.rr)}", fontWeight = FontWeight.SemiBold) }
                    }
                    TextButton(onClick = { expanded = if (open) null else row.id }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "Hide reasoning" else "Show reasoning")
                    }
                    if (open) Text(row.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (visible.isEmpty() && !loading) Text("No qualifying SMC signals at the moment.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenTradeIdeas, modifier = Modifier.fillMaxWidth()) {
            Text("Open Trade Ideas for execution validation")
        }
        Text("Signals are candle-derived pattern candidates, not approved entries. READY is intentionally withheld until shared live risk gates (spread, depth, volume, funding, stop validity and net R:R after costs) are verified. Gross R:R is not net R:R.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Double.unifiedPrice(): String = when {
    this >= 1000 -> "%.2f".format(Locale.US, this)
    this >= 1 -> "%.4f".format(Locale.US, this)
    else -> "%.6f".format(Locale.US, this)
}
