package com.risktracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CoinExplosionRadarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ExplosionConfig()) }
    var result by remember { mutableStateOf<ExplosionScanResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Scan all active Binance USDT perpetual contracts.") }
    var selected by remember { mutableStateOf<ExplosionSignal?>(null) }
    var replay by remember { mutableStateOf<ExplosionReplayStats?>(null) }
    var replayLoading by remember { mutableStateOf(false) }
    var showRejected by remember { mutableStateOf(false) }

    fun scan() {
        if (loading) return
        loading = true
        status = "Loading Binance contract universe, market data and completed 1h candles…"
        scope.launch {
            runCatching { CoinExplosionRadarDataSource.scan(config) }
                .onSuccess {
                    result = it
                    selected = it.signals.firstOrNull { signal -> signal.stage != ExplosionStage.REJECTED && signal.score >= config.watchScore }
                    status = "Scanned ${it.scanned} contracts · ${it.signals.count { s -> s.stage != ExplosionStage.REJECTED }} passed safety filters · ${it.failed.size} data failures."
                }
                .onFailure { status = AppErrorMessage.from(it, "Radar scan failed. Check your connection and retry.") }
            loading = false
        }
    }

    val visible = result?.signals.orEmpty().filter { showRejected || (it.stage != ExplosionStage.REJECTED && it.score >= config.watchScore) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Coin Explosion Radar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Early pressure scanner · 1h candles · 1–24h research horizon · Binance USD-M public market data · read-only",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Universe & risk controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("All active USDT perpetual contracts are enumerated. Thin or wide-spread markets remain visible as rejected results when enabled; they are never promoted as trade candidates.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Minimum 24h quote volume: ${"%.1f".format(config.minimumQuoteVolumeUsd / 1_000_000.0)}M USDT")
                    Slider(value = (config.minimumQuoteVolumeUsd / 1_000_000.0).toFloat(), onValueChange = { config = config.copy(minimumQuoteVolumeUsd = it.toDouble() * 1_000_000.0) }, valueRange = 1f..20f)
                    Text("Maximum spread: ${"%.0f".format(config.maximumSpreadBps)} bps")
                    Slider(value = config.maximumSpreadBps.toFloat(), onValueChange = { config = config.copy(maximumSpreadBps = it.toDouble()) }, valueRange = 5f..50f)
                    Text("Early-warning threshold: ${config.watchScore}/100")
                    Slider(value = config.watchScore.toFloat(), onValueChange = { config = config.copy(watchScore = it.toInt(), ignitionScore = maxOf(config.ignitionScore, it.toInt())) }, valueRange = 35f..75f, steps = 7)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scan() }, enabled = !loading) { Text(if (loading) "Scanning…" else "Scan all USDT pairs") }
                        OutlinedButton(onClick = { showRejected = !showRejected }) { Text(if (showRejected) "Hide rejected" else "Show rejected") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result?.let { Text("Updated ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.updatedAt))} · ${it.scanned} universe contracts",
                        style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How to read the score", fontWeight = FontWeight.SemiBold)
                    Text("BUILDING = early pressure, IGNITION = stronger confluence, CONFIRMED = completed-candle breakout. A high score is a research rank, not a probability of profit. Missing open-interest/funding/spread data is never silently treated as bullish.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (visible.isEmpty() && result != null) item { Text("No candidates reached the ${config.watchScore}/100 early-warning threshold. This is a valid result; do not lower safety limits just to force signals.") }
        items(visible, key = { it.symbol }) { signal ->
            Card(Modifier.fillMaxWidth().clickable { selected = signal; replay = null }) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(signal.symbol, fontWeight = FontWeight.Bold)
                            Text("${signal.stage.name} · ${signal.direction.name}", style = MaterialTheme.typography.labelSmall,
                                color = if (signal.stage == ExplosionStage.REJECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text("${signal.score}/100", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("Price ${"%.8f".format(signal.lastPrice)} · 24h volume ${"%.1fM".format(signal.quoteVolume24h / 1_000_000.0)} USDT",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Trigger ${signal.triggerPrice?.let { "%.8f".format(it) } ?: "—"} · Invalidation ${signal.invalidationPrice?.let { "%.8f".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Compression ${signal.breakdown.compression} · Volume ${signal.breakdown.relativeVolume} · Pressure ${signal.breakdown.resistancePressure} · Flow ${signal.breakdown.takerFlow} · RS ${signal.breakdown.relativeStrength} · OI ${signal.breakdown.openInterest}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (signal.riskFlags.isNotEmpty()) Text(signal.riskFlags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        selected?.let { signal ->
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Selected setup · ${signal.symbol}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        signal.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text("Risk flags: ${signal.riskFlags.ifEmpty { listOf("No hard risk filter tripped; verify all exchange conditions before trading.") }.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button(onClick = {
                            replayLoading = true
                            scope.launch {
                                replay = runCatching { CoinExplosionRadarDataSource.replay(signal.symbol, config) }.getOrNull()
                                replayLoading = false
                            }
                        }, enabled = !replayLoading && signal.stage != ExplosionStage.REJECTED) {
                            Text(if (replayLoading) "Running replay…" else "Run 1h historical replay")
                        }
                        replay?.let { stats ->
                            HorizontalDivider()
                            Text("Historical replay · ${stats.trades} trades · ${stats.horizonBars}h holding horizon", fontWeight = FontWeight.SemiBold)
                            if (stats.trades < 30) Text("INSUFFICIENT SAMPLE · Fewer than 30 historical trades; do not infer an edge.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Text("Win rate ${stats.winRatePct?.let { "%.1f%%".format(it) } ?: "—"} · Avg return ${stats.averageReturnPct?.let { "%+.2f%%".format(it) } ?: "—"} · Profit factor ${stats.profitFactor?.let { "%.2f".format(it) } ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Max drawdown ${stats.maxDrawdownPct?.let { "%.2f%%".format(it) } ?: "—"} · Avg MFE ${stats.averageMfePct?.let { "%.2f%%".format(it) } ?: "—"} · Avg MAE ${stats.averageMaePct?.let { "%.2f%%".format(it) } ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Replay is historical, single-symbol and indicative. It uses next-candle open entries, completed-candle signals, fee and slippage assumptions; no funding, liquidation or order-book queue simulation.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Text("Safety: public market data only. No order placement. OI history is requested for higher-turnover contracts only; absent OI receives zero points. Binance order-book data excludes some retail price-improvement orders. Scores are not calibrated probabilities and cannot predict an imminent pump.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
