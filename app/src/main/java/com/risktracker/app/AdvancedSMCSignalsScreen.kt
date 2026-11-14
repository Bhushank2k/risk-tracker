package com.risktracker.app

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AdvancedSMCSignalStore {
    private const val PREFS = "advanced_smc_signals_v1"

    fun update(context: Context, scanned: Int, total: Int, running: Boolean, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("scanned", scanned).putInt("total", total).putBoolean("running", running)
            .putString("message", message).putLong("statusAt", System.currentTimeMillis()).apply()
    }

    fun status(context: Context): JSONObject {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject().put("scanned", p.getInt("scanned", 0)).put("total", p.getInt("total", 0))
            .put("running", p.getBoolean("running", false))
            .put("message", p.getString("message", "Background scan is scheduled") ?: "")
    }

    fun save(context: Context, signals: List<AdvancedSMCSignal>) {
        val rows = JSONArray()
        signals.forEach { s ->
            rows.put(JSONObject().put("id", s.id).put("symbol", s.symbol).put("side", s.side)
                .put("signalType", s.signalType).put("horizon", s.horizon).put("score", s.score)
                .put("status", s.status).put("entryLow", s.entryLow).put("entryHigh", s.entryHigh)
                .put("stop", s.stop).put("target1", s.target1).put("target2", s.target2)
                .put("rewardRisk", s.rewardRisk).put("explanation", s.explanation)
                .put("confirmation", s.confirmation).put("detectedAt", s.detectedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("signals", rows.toString()).putLong("resultsAt", System.currentTimeMillis()).apply()
    }

    fun load(context: Context): List<AdvancedSMCSignal> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("signals", "[]") ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val x = rows.getJSONObject(i)
                AdvancedSMCSignal(
                    id = x.getString("id"), symbol = x.getString("symbol"), side = x.getString("side"),
                    signalType = x.getString("signalType"), horizon = x.getString("horizon"),
                    score = x.getInt("score"), status = x.getString("status"),
                    entryLow = x.getDouble("entryLow"), entryHigh = x.getDouble("entryHigh"),
                    stop = x.getDouble("stop"), target1 = x.getDouble("target1"), target2 = x.getDouble("target2"),
                    rewardRisk = x.getDouble("rewardRisk"), explanation = x.getString("explanation"),
                    confirmation = x.getString("confirmation"), detectedAt = x.getLong("detectedAt")
                )
            }.getOrNull()
        }
    }

    fun resultsUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("resultsAt", 0L)
}

@Composable
fun AdvancedSMCSignalsScreen(onOpenTradeIdeas: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signals by remember { mutableStateOf(AdvancedSMCSignalStore.load(context)) }
    val initialStatus = remember { AdvancedSMCSignalStore.status(context) }
    var scanned by remember { mutableIntStateOf(initialStatus.optInt("scanned")) }
    var total by remember { mutableIntStateOf(initialStatus.optInt("total")) }
    var backgroundRunning by remember { mutableStateOf(initialStatus.optBoolean("running")) }
    var backgroundMessage by remember { mutableStateOf(initialStatus.optString("message")) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Scan the most liquid Binance USDT-M perpetual markets for advanced SMC patterns.") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var resultsAt by remember { mutableLongStateOf(AdvancedSMCSignalStore.resultsUpdatedAt(context)) }

    fun refresh() {
        if (loading) return
        loading = true; error = null
        message = "Loading advanced SMC market data…"
        scope.launch {
            try {
                BinanceMarketScanner.initialize(context)
                val result = BinanceMarketScanner.scanAdvancedSmc(
                    progress = { message = it },
                    onProgress = { done, count -> scanned = done; total = count }
                )
                signals = result
                AdvancedSMCSignalStore.save(context, result)
                resultsAt = AdvancedSMCSignalStore.resultsUpdatedAt(context)
                AdvancedSMCSignalStore.update(context, total, total, false, "Advanced SMC scan complete · ${result.size} ideas")
                message = "Scan complete · ${result.size} advanced SMC trade ideas found."
            } catch (e: Exception) {
                error = e.message ?: "Advanced SMC scan failed."
                message = "Advanced SMC scan could not be completed."
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) {
        if (signals.isEmpty()) refresh()
        while (true) {
            val snapshot = AdvancedSMCSignalStore.status(context)
            scanned = snapshot.optInt("scanned"); total = snapshot.optInt("total")
            backgroundRunning = snapshot.optBoolean("running"); backgroundMessage = snapshot.optString("message")
            val updated = AdvancedSMCSignalStore.resultsUpdatedAt(context)
            if (!loading && updated > resultsAt) {
                signals = AdvancedSMCSignalStore.load(context)
                resultsAt = updated
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SMC Signals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Advanced patterns: IFVG · breaker blocks · SMT divergence · Asian-session liquidity raids",
                    style = MaterialTheme.typography.bodySmall)
                Text("Separate from SMC Trade · up to 30 ranked pattern ideas", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Scanning advanced signals…" else "Scan advanced SMC signals")
        }
        OutlinedButton(onClick = onOpenTradeIdeas, modifier = Modifier.fillMaxWidth()) {
            Text("Open Trade Ideas for execution checks")
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading || backgroundRunning || total > 0) {
            val fraction = if (total > 0) (scanned.toFloat() / total).coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (loading) "Foreground scan" else if (backgroundRunning) "Background scan" else "Last scan",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text("$scanned / $total coins", style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                if (!loading) Text(backgroundMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (signals.isEmpty() && !loading) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("No advanced SMC patterns qualified", fontWeight = FontWeight.Bold)
                    Text("No trade idea is better than a forced setup. Try again after fresh candles close.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        signals.forEach { signal ->
            val open = expanded == signal.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(signal.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${signal.side} · ${signal.signalType} · ${signal.horizon}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (signal.side == "LONG") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("${signal.score}/100", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(signal.status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SignalMetric("Entry zone", "${signal.entryLow.signalPrice()} – ${signal.entryHigh.signalPrice()}")
                        SignalMetric("Stop", signal.stop.signalPrice())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SignalMetric("Target 1", signal.target1.signalPrice())
                        SignalMetric("Target 2", signal.target2.signalPrice())
                        SignalMetric("Gross R:R", "1:${"%.2f".format(Locale.US, signal.rewardRisk)}")
                    }
                    TextButton(onClick = { expanded = if (open) null else signal.id }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "Hide reasoning" else "Show reasoning")
                    }
                    if (open) {
                        HorizontalDivider()
                        Text(signal.explanation, style = MaterialTheme.typography.bodySmall)
                        Text(signal.confirmation, style = MaterialTheme.typography.bodySmall)
                        Text("Detected: " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(signal.detectedAt)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("These are candle-derived watchlist ideas. R:R is gross, before fees, funding and slippage. Even a confirmed pattern is not an approved entry until Trade Ideas verifies spread, directional depth, volume, stop and at least 2.0R net of costs.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun Double.signalPrice(): String = when {
    this >= 1000 -> "%.2f".format(Locale.US, this)
    this >= 1 -> "%.4f".format(Locale.US, this)
    else -> "%.6f".format(Locale.US, this)
}
