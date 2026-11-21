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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class UnifiedSMCRow(
    val symbol: String, val side: String, val horizon: String, val score: Int,
    val entryLow: Double, val entryHigh: Double, val stop: Double,
    val target: Double, val rr: Double, val sources: Set<String>,
    val patterns: Set<String>, val status: String, val explanation: String,
    val levelSource: String, val detectedAt: Long, val id: String
)

internal data class UnifiedSMCScanUiState(
    val initialized: Boolean = false,
    val core: List<SMCTradeSetup> = emptyList(),
    val advanced: List<AdvancedSMCSignal> = emptyList(),
    val loading: Boolean = false,
    val progress: String = "Preparing independent SMC scan…",
    val scanned: Int = 0,
    val total: Int = 0,
    val coreUpdatedAt: Long = 0L,
    val advancedUpdatedAt: Long = 0L,
    val error: String? = null
)

/**
 * Process-owned SMC scan lifecycle. The scan survives switching between Trade Ideas
 * and SMC Signals; the screen only observes this state and never owns the scan job.
 */
internal object UnifiedSMCScanManager {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(UnifiedSMCScanUiState())
    val state: StateFlow<UnifiedSMCScanUiState> = mutableState
    private var scanJob: Job? = null

    fun initialize(context: android.content.Context) {
        val app = context.applicationContext
        if (mutableState.value.initialized) return

        val core = SMCScanStateStore.loadResults(app)
        val advanced = AdvancedSMCSignalStore.load(app)
        val coreUpdatedAt = SMCScanStateStore.resultsUpdatedAt(app)
        val advancedUpdatedAt = AdvancedSMCSignalStore.resultsUpdatedAt(app)
        val status = SMCScanStateStore.status(app)
        val running = status.optBoolean("running")
        mutableState.value = UnifiedSMCScanUiState(
            initialized = true,
            core = core,
            advanced = advanced,
            loading = running,
            progress = status.optString("message", "Ready · independent SMC scan"),
            scanned = status.optInt("scanned"),
            total = status.optInt("total"),
            coreUpdatedAt = coreUpdatedAt,
            advancedUpdatedAt = advancedUpdatedAt
        )

        // Observe WorkManager results too, so background refreshes appear without
        // requiring the user to leave and reopen the SMC tab.
        managerScope.launch {
            while (true) {
                val latestStatus = SMCScanStateStore.status(app)
                val latestCoreAt = SMCScanStateStore.resultsUpdatedAt(app)
                val latestAdvancedAt = AdvancedSMCSignalStore.resultsUpdatedAt(app)
                val current = mutableState.value
                val coreChanged = latestCoreAt > current.coreUpdatedAt
                val advancedChanged = latestAdvancedAt > current.advancedUpdatedAt
                val latestMessage = latestStatus.optString("message", current.progress)
                val latestRunning = latestStatus.optBoolean("running") || scanJob?.isActive == true
                val latestError = if (!latestRunning &&
                    (latestMessage.contains("failed", ignoreCase = true) ||
                     latestMessage.contains("incomplete", ignoreCase = true))) latestMessage else null
                mutableState.value = current.copy(
                    core = if (coreChanged) SMCScanStateStore.loadResults(app) else current.core,
                    advanced = if (advancedChanged) AdvancedSMCSignalStore.load(app) else current.advanced,
                    coreUpdatedAt = maxOf(current.coreUpdatedAt, latestCoreAt),
                    advancedUpdatedAt = maxOf(current.advancedUpdatedAt, latestAdvancedAt),
                    loading = latestRunning,
                    progress = latestMessage,
                    scanned = latestStatus.optInt("scanned"),
                    total = latestStatus.optInt("total"),
                    error = latestError
                )
                delay(1_000)
            }
        }

        // Do not launch a foreground scan merely because the user opened this
        // screen. The scheduler owns routine background refreshes; manual retry
        // is available from the Scan Now button below.
    }

    fun refresh(context: android.content.Context) {
        val app = context.applicationContext
        if (scanJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            scanned = 0,
            total = 0,
            progress = "Starting unified SMC scan · Trade Ideas continues independently…"
        )
        SMCScanStateStore.update(app, 0, 0, true, "Loading shared SMC market data…")
        scanJob = managerScope.launch {
            try {
                val result = BinanceMarketScanner.scanUnifiedSmc(
                    progress = { message ->
                        mutableState.value = mutableState.value.copy(progress = message)
                        val old = SMCScanStateStore.status(app)
                        SMCScanStateStore.update(app, old.optInt("scanned"), old.optInt("total"), true, message)
                    },
                    onProgress = { done, count ->
                        mutableState.value = mutableState.value.copy(scanned = done, total = count)
                        val old = SMCScanStateStore.status(app)
                        SMCScanStateStore.update(app, done, count, true, old.optString("message"))
                    }
                )
                withContext(Dispatchers.IO) {
                    AdvancedSMCSignalStore.save(app, result.advanced)
                    SMCScanStateStore.saveResults(app, result.core)
                }
                val completedCoreAt = SMCScanStateStore.resultsUpdatedAt(app)
                val completedAdvancedAt = AdvancedSMCSignalStore.resultsUpdatedAt(app)
                mutableState.value = mutableState.value.copy(
                    core = result.core,
                    advanced = result.advanced,
                    coreUpdatedAt = completedCoreAt,
                    advancedUpdatedAt = completedAdvancedAt,
                    error = null,
                    progress = "Unified SMC complete · ${result.core.size} Core + ${result.advanced.size} Advanced candidates."
                )
                SMCScanStateStore.update(app, mutableState.value.total, mutableState.value.total, false,
                    "Unified SMC scan complete · ${result.core.size} Core + ${result.advanced.size} Advanced candidates.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "SMC market scan failed."
                mutableState.value = mutableState.value.copy(error = message, progress = "SMC scan incomplete: $message")
                val old = SMCScanStateStore.status(app)
                SMCScanStateStore.update(app, old.optInt("scanned"), old.optInt("total"), false,
                    "SMC scan failed: $message")
            } finally {
                mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }
}

@Composable
internal fun UnifiedSMCSignalsScreen(onOpenTradeIdeas: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scanState by UnifiedSMCScanManager.state.collectAsState()
    val core = scanState.core
    val advanced = scanState.advanced
    val loading = scanState.loading
    val tradeScanState by TradeIdeaScanManager.state.collectAsState()
    val combinedLoading = loading || tradeScanState.loading
    val canStartCombined = !combinedLoading
    val progress = scanState.progress
    val scanned = scanState.scanned
    val total = scanState.total
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); clockNow = System.currentTimeMillis() }
    }
    val coreAgeMs = if (scanState.coreUpdatedAt > 0L) (clockNow - scanState.coreUpdatedAt).coerceAtLeast(0L) else Long.MAX_VALUE
    val advancedAgeMs = if (scanState.advancedUpdatedAt > 0L) (clockNow - scanState.advancedUpdatedAt).coerceAtLeast(0L) else Long.MAX_VALUE
    var filter by remember { mutableStateOf("All") }
    var horizon by remember { mutableStateOf("All") }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun scan() = UnifiedMarketScanCoordinator.refresh(context)
    LaunchedEffect(Unit) { UnifiedSMCScanManager.initialize(context) }

    val coreCandidates = remember(core, scanState.coreUpdatedAt) {
        core.map { x ->
            UnifiedSMCCandidate(
                id = x.id, symbol = x.symbol, side = x.side, horizon = x.horizon,
                score = x.score, entryLow = x.entryLow, entryHigh = x.entryHigh,
                stop = x.stop, target = x.target, rewardRisk = x.rewardRisk,
                source = "Core SMC", pattern = "BOS / liquidity sweep / displacement / OB-FVG",
                status = x.status,
                explanation = listOf(x.structure, x.sweep, x.displacement, x.zoneDescription, x.location).joinToString("\n"),
                detectedAt = scanState.coreUpdatedAt
            )
        }
    }
    val advancedCandidates = remember(advanced) {
        advanced.map { x ->
            UnifiedSMCCandidate(
                id = x.id, symbol = x.symbol, side = x.side, horizon = x.horizon,
                score = x.score, entryLow = x.entryLow, entryHigh = x.entryHigh,
                stop = x.stop, target = x.target1, rewardRisk = x.rewardRisk,
                source = "Advanced SMC", pattern = x.signalType, status = x.status,
                explanation = x.explanation + "\n" + x.confirmation,
                detectedAt = x.detectedAt
            )
        }
    }
    val rows = remember(coreCandidates, advancedCandidates) {
        mergeUnifiedSMCCandidates(coreCandidates, advancedCandidates).map { signal ->
            UnifiedSMCRow(
                signal.symbol, signal.side, signal.horizon, signal.score,
                signal.entryLow, signal.entryHigh, signal.stop, signal.target,
                signal.rewardRisk, signal.sources, signal.patterns, signal.status,
                signal.explanation, signal.levelSource, signal.detectedAt, signal.id
            )
        }
    }
    val visible = remember(rows, filter, horizon) {
        rows.filter { (filter == "All" || it.side == filter) && (horizon == "All" || it.horizon == horizon) }
    }


    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Unified SMC Signals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("One shared market snapshot · Trade Ideas + Core + Advanced SMC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = { scan() },
            enabled = canStartCombined,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (combinedLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    combinedLoading -> "Scanning…"
                    scanState.error != null -> "Retry Scan"
                    progress.contains("failed", ignoreCase = true) ||
                        progress.contains("incomplete", ignoreCase = true) -> "Retry Scan"
                    else -> "Scan Now"
                }
            )
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
        if (tradeScanState.loading) Text("Trade Ideas · ${tradeScanState.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading || total > 0) {
            Text(progress, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (total > 0) (scanned.toFloat() / total).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("$scanned / $total eligible SMC markets checked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scanState.error != null) Text("Last refresh failed: ${scanState.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Core SMC: ${coreAgeMs.unifiedFreshnessLabel()} · Advanced SMC: ${advancedAgeMs.unifiedFreshnessLabel()}",
            color = if (coreAgeMs > 15L * 60L * 1000L || advancedAgeMs > 15L * 60L * 1000L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
                            Text("Highest: ${row.score}/100", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.sources.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                    row.patterns.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(row.status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text("Levels: ${row.levelSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Source timestamp: ${row.detectedAt.unifiedSignalAge(clockNow)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun Long.unifiedFreshnessLabel(): String =
    if (this == Long.MAX_VALUE) "no successful scan" else if (this > 15L * 60L * 1000L) "STALE · ${this / 60000} min ago" else "updated ${this / 60000} min ago"

private fun Long.unifiedSignalAge(now: Long): String =
    if (this <= 0L) "timestamp unavailable"
    else {
        val age = (now - this).coerceAtLeast(0L)
        if (age < 60_000L) "${age / 1000}s ago" else "${age / 60_000L} min ago"
    }

private fun Double.unifiedPrice(): String = when {
    this >= 1000 -> "%.2f".format(Locale.US, this)
    this >= 1 -> "%.4f".format(Locale.US, this)
    else -> "%.6f".format(Locale.US, this)
}
