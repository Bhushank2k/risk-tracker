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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SMCTradeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var horizon by remember { mutableStateOf("All") }
    var setups by remember { mutableStateOf<List<SMCTradeSetup>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Refresh to scan liquid Binance USDT-M perpetual markets for SMC setups.") }
    var error by remember { mutableStateOf<String?>(null) }
    var scannedAt by remember { mutableStateOf<Long?>(null) }

    fun refresh() {
        if (loading) return
        loading = true
        error = null
        status = "Loading Binance futures markets…"
        scope.launch {
            try {
                val result = BinanceMarketScanner.scanSmc { message -> status = message }
                setups = result
                scannedAt = System.currentTimeMillis()
                status = if (result.isEmpty()) "Scan complete · no SMC setups passed every required confirmation." else "Scan complete · ${result.size} qualified SMC setups."
            } catch (e: Exception) {
                error = e.message ?: "SMC scan failed."
                status = "SMC scan could not be completed."
            } finally {
                loading = false
            }
        }
    }

    val visible = remember(setups, horizon) {
        (if (horizon == "All") setups else setups.filter { it.horizon == horizon })
            .sortedByDescending { it.score }.take(5)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("SMC Trade", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Smart Money Concepts only · BOS / CHoCH · liquidity sweep · displacement · order block / FVG · premium / discount",
                    style = MaterialTheme.typography.bodySmall)
                Text("Up to 5 strongest setups · zero is a valid result", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Scalp", "Intraday", "Swing").forEach {
                FilterChip(selected = horizon == it, onClick = { horizon = it }, label = { Text(it) })
            }
        }
        Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Scanning SMC setups…" else "Scan SMC trades")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        scannedAt?.let { Text("Last completed scan: " + java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(java.util.Date(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (visible.isEmpty() && !loading) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("No qualifying setups", fontWeight = FontWeight.Bold)
                    Text("No trade is preferable to forcing a setup. Try another scan or horizon after fresh candles form.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        visible.forEach { setup ->
            val isExpanded = expanded == setup.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(setup.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${setup.side} · ${setup.horizon} · ${setup.status}", style = MaterialTheme.typography.labelSmall,
                                color = if (setup.side == "LONG") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("${setup.score}/100", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SMCPriceMetric("Entry zone", "${setup.entryLow.price()} – ${setup.entryHigh.price()}")
                        SMCPriceMetric("Stop", setup.stop.price())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SMCPriceMetric("Liquidity target", setup.target.price())
                        SMCPriceMetric("R:R", "1:${"%.2f".format(Locale.US, setup.rewardRisk)}")
                    }
                    TextButton(onClick = { expanded = if (isExpanded) null else setup.id }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (isExpanded) "Hide SMC reasoning" else "Expand SMC reasoning")
                    }
                    if (isExpanded) {
                        HorizontalDivider()
                        Text("Market structure", fontWeight = FontWeight.SemiBold)
                        Text(setup.structure, style = MaterialTheme.typography.bodySmall)
                        Text("Liquidity event", fontWeight = FontWeight.SemiBold)
                        Text(setup.sweep, style = MaterialTheme.typography.bodySmall)
                        Text("Displacement / BOS", fontWeight = FontWeight.SemiBold)
                        Text(setup.displacement, style = MaterialTheme.typography.bodySmall)
                        Text("Order block / FVG", fontWeight = FontWeight.SemiBold)
                        Text(setup.zoneDescription, style = MaterialTheme.typography.bodySmall)
                        Text("Dealing range", fontWeight = FontWeight.SemiBold)
                        Text(setup.location, style = MaterialTheme.typography.bodySmall)
                        Text("Score: structure 20 · sweep 20 · displacement/BOS 20 · OB/FVG 15 · location 10 · R:R 15",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("SMC levels are algorithmic interpretations of candle data, not proof of institutional orders. Check live price, fees, funding and slippage before trading futures.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SMCPriceMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun Double.price(): String = if (this >= 1000) "%.2f".format(Locale.US, this) else if (this >= 1) "%.4f".format(Locale.US, this) else "%.6f".format(Locale.US, this)
