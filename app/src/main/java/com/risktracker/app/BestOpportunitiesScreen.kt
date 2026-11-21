package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import java.util.Locale

@Composable
internal fun BestOpportunitiesScreen(
    onOpenTradeIdeas: () -> Unit = {},
    onOpenSmc: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tradeState by TradeIdeaScanManager.state.collectAsState()
    val smcState by UnifiedSMCScanManager.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var statusFilter by remember { mutableStateOf("All") }
    var sideFilter by remember { mutableStateOf("All") }
    var horizonFilter by remember { mutableStateOf("All") }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        TradeIdeaScanManager.initialize(context)
        UnifiedSMCScanManager.initialize(context)
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    val tradeUpdatedAt = tradeState.updatedAt
    val tradeAge = when {
        tradeState.partial -> 0L
        tradeUpdatedAt == null || tradeUpdatedAt <= 0L -> Long.MAX_VALUE
        else -> (now - tradeUpdatedAt).coerceAtLeast(0L)
    }
    val coreCandidates = remember(smcState.core, smcState.coreUpdatedAt) {
        smcState.core.map { x ->
            UnifiedSMCCandidate(
                id = x.id, symbol = x.symbol, side = x.side, horizon = x.horizon,
                score = x.score, entryLow = x.entryLow, entryHigh = x.entryHigh,
                stop = x.stop, target = x.target, rewardRisk = x.rewardRisk,
                source = "Core SMC", pattern = "BOS / liquidity sweep / displacement / OB-FVG",
                status = x.status,
                explanation = listOf(x.structure, x.sweep, x.displacement, x.zoneDescription, x.location).joinToString("\n"),
                detectedAt = smcState.coreUpdatedAt
            )
        }
    }
    val advancedCandidates = remember(smcState.advanced, smcState.advancedUpdatedAt) {
        smcState.advanced.map { x ->
            UnifiedSMCCandidate(
                id = x.id, symbol = x.symbol, side = x.side, horizon = x.horizon,
                score = x.score, entryLow = x.entryLow, entryHigh = x.entryHigh,
                stop = x.stop, target = x.target1, rewardRisk = x.rewardRisk,
                source = "Advanced SMC", pattern = x.signalType, status = x.status,
                explanation = x.explanation + "\n" + x.confirmation,
                detectedAt = smcState.advancedUpdatedAt
            )
        }
    }
    val smcSignals = remember(coreCandidates, advancedCandidates) {
        mergeUnifiedSMCCandidates(coreCandidates, advancedCandidates)
    }
    val opportunities = remember(tradeState.ideas, tradeAge, smcSignals, now, tradeState.partial) {
        buildBestOpportunities(
            tradeIdeas = tradeState.ideas,
            smcSignals = smcSignals,
            tradeIdeaSnapshotAgeMs = tradeAge,
            now = now,
            tradeIdeaSnapshotComplete = !tradeState.partial && tradeState.updatedAt != null
        )
    }
    val visible = remember(opportunities, statusFilter, sideFilter, horizonFilter) {
        opportunities.filter { item ->
            (statusFilter == "All" || item.status.name == statusFilter) &&
                (sideFilter == "All" || item.side == sideFilter) &&
                (horizonFilter == "All" || item.horizon.contains(horizonFilter, ignoreCase = true))
        }
    }
    val isLoading = tradeState.loading || smcState.loading
    val confirmed = opportunities.count { it.status == BestOpportunityStatus.CONFIRMED }
    val waiting = opportunities.count { it.status == BestOpportunityStatus.WAITING }
    val conflicts = opportunities.count { it.status == BestOpportunityStatus.CONFLICT }
    val rejected = opportunities.count { it.status == BestOpportunityStatus.REJECTED }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Best Opportunities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Daily Trade Ideas discovery + Core/Advanced SMC structure + mandatory execution checks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { UnifiedMarketScanCoordinator.refresh(context) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isLoading) "Refreshing both scanners…" else "Refresh Best Opportunities")
        }
        if (tradeState.error != null) {
            Text("Daily Trade Ideas: ${tradeState.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (smcState.error != null) {
            Text("SMC: ${smcState.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("Daily Trade Ideas · ${tradeState.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("SMC · ${smcState.progress}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryMetric("Confirmed", confirmed, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            SummaryMetric("Waiting", waiting, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            SummaryMetric("Conflict", conflicts, MaterialTheme.colorScheme.error, Modifier.weight(1f))
            SummaryMetric("Rejected", rejected, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
        }
        Text("Status", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("All", "CONFIRMED", "WAITING", "CONFLICT", "REJECTED").forEach { value ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = value },
                    label = { Text(if (value == "All") value else value.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("All", "LONG", "SHORT").forEach { value ->
                FilterChip(selected = sideFilter == value, onClick = { sideFilter = value }, label = { Text(value) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("All", "Scalp", "Intraday", "Swing").forEach { value ->
                FilterChip(selected = horizonFilter == value, onClick = { horizonFilter = value }, label = { Text(value) })
            }
        }
        Text(
            "${visible.size} candidates · ${opportunities.count { it.idea != null && it.smc != null }} cross-scanner matches",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        visible.forEach { item ->
            val open = expanded == item.id
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${item.side} · ${item.horizon}", style = MaterialTheme.typography.labelMedium)
                        }
                        Surface(
                            color = when (item.status) {
                                BestOpportunityStatus.CONFIRMED -> MaterialTheme.colorScheme.primaryContainer
                                BestOpportunityStatus.CONFLICT, BestOpportunityStatus.REJECTED -> MaterialTheme.colorScheme.errorContainer
                                BestOpportunityStatus.WAITING -> MaterialTheme.colorScheme.tertiaryContainer
                            },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                item.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                                Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        item.sources.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        AssistChip(onClick = {}, label = { Text("Priority ${item.priorityScore}") })
                    }
                    val entry = item.idea?.entry
                    val stop = item.idea?.stop ?: item.smc?.stop
                    val target = item.idea?.target1 ?: item.smc?.target
                    if (entry != null && stop != null && target != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Entry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(entry.bestPrice(), fontWeight = FontWeight.SemiBold) }
                            Column { Text("Stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(stop.bestPrice(), fontWeight = FontWeight.SemiBold) }
                            Column { Text("Target 1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(target.bestPrice(), fontWeight = FontWeight.SemiBold) }
                        }
                    } else if (item.smc != null) {
                        Text("SMC entry zone · ${item.smc.entryLow.bestPrice()} – ${item.smc.entryHigh.bestPrice()}")
                        Text("Stop ${item.smc.stop.bestPrice()} · Target ${item.smc.target.bestPrice()}")
                    }
                    if (item.idea != null && item.smc != null) {
                        Text("SMC entry zone · ${item.smc.entryLow.bestPrice()} – ${item.smc.entryHigh.bestPrice()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.idea != null) {
                        Text("Daily score ${item.idea.score}/100 · SMC score ${item.smc?.score?.toString() ?: "—"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.eligibility?.let { Text("Execution validation · ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("SMC score ${item.smc?.score ?: 0}/100 · waiting for Daily Trade Ideas execution validation",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { expanded = if (open) null else item.id }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "Hide reasoning" else "Show reasoning")
                    }
                    if (open) {
                        Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                        item.smc?.let { Text("SMC levels: ${it.levelSource}", style = MaterialTheme.typography.labelSmall) }
                        item.idea?.let { idea ->
                            Text("Relative volume ${"%.2f".format(Locale.US, idea.volumeRatio)}× · RSI ${"%.1f".format(Locale.US, idea.rsi)}",
                                style = MaterialTheme.typography.labelSmall)
                            item.eligibility?.checks?.forEach { check ->
                                val marker = when (check.passed) { true -> "✓"; false -> "✕"; null -> "?" }
                                Text("$marker ${check.label}", style = MaterialTheme.typography.labelSmall,
                                    color = if (check.passed == false && check.hardGate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (visible.isEmpty() && !isLoading) Text("No candidates match these filters. Refresh both scanners or widen the filters.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenTradeIdeas, modifier = Modifier.weight(1f)) { Text("Daily Trade Ideas") }
            OutlinedButton(onClick = onOpenSmc, modifier = Modifier.weight(1f)) { Text("SMC Signals") }
        }
        Text(
            "Confirmed means a fresh SMC match and every mandatory Daily Trade Ideas gate passed. WAITING includes missing data or pending entry confirmation. CONFLICT means opposing directions exist for the same symbol. Priority is a ranking aid, not a win probability. No orders are placed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Double.bestPrice(): String = when {
    !isFinite() -> "—"
    this >= 1000.0 -> "%.2f".format(Locale.US, this)
    this >= 1.0 -> "%.4f".format(Locale.US, this)
    else -> "%.6f".format(Locale.US, this)
}
