package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun StoredSignalReplayScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val intervals = listOf("1m", "5m", "15m", "1h", "4h", "1d")
    var interval by remember { mutableStateOf("5m") }
    var report by remember { mutableStateOf<StoredSignalReplayReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadReplay() {
        scope.launch {
            loading = true
            error = null
            try {
                report = withContext(Dispatchers.IO) {
                    StoredSignalReplayRunner.replay(context.applicationContext, interval)
                }
            } catch (e: Exception) {
                error = e.message ?: "Replay could not be completed."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(interval) { loadReplay() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Stored signal replay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Replays saved scanner plans against candles recorded after each signal. Research only — no orders are placed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { value ->
                if (interval == value) {
                    AssistChip(onClick = { interval = value }, label = { Text(value) }, enabled = false)
                } else {
                    AssistChip(onClick = { interval = value }, label = { Text(value) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadReplay() }, enabled = !loading) { Text("Run replay") }
            if (loading) CircularProgressIndicator(modifier = Modifier.height(32.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        report?.let { data ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${data.interval} results", fontWeight = FontWeight.SemiBold)
                    Text("${data.capturedSnapshots} saved signals · ${data.replayedSignals} replayed · ${data.missingHistorySignals} awaiting later candles")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("Win rate", "${"%.1f".format(Locale.US, data.summary.winRatePct)}%")
                        Metric("Expectancy", "${signed(data.summary.expectancyNetR)} R")
                        Metric("Profit factor", if (data.summary.profitFactor.isFinite()) "%.2f".format(Locale.US, data.summary.profitFactor) else "∞")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("Net R", signed(data.summary.totalNetR))
                        Metric("Wins / losses", "${data.summary.wins} / ${data.summary.losses}")
                        Metric("Entered", data.summary.enteredTrades.toString())
                    }
                }
            }
            Text("Recent replay outcomes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val visible = data.trades.asReversed().take(50)
            if (visible.isEmpty()) {
                Text("No saved signal snapshots yet. Run a fresh scan and collect later candles to build replay history.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.signalId }) { trade ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${trade.symbol} · ${trade.side}", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text("Score ${trade.score}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${trade.outcome.name.replace('_', ' ')} · ${signed(trade.netR)} R net")
                                Text(
                                    "Entry ${trade.entryTime?.let(::formatReplayTime) ?: "—"} · Exit ${trade.exitTime?.let(::formatReplayTime) ?: "—"} · ${trade.barsHeld} bars",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (trade.note.isNotBlank()) Text(trade.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun signed(value: Double): String = (if (value > 0) "+" else "") + "%.2f".format(Locale.US, value)
private fun formatReplayTime(value: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(value))
