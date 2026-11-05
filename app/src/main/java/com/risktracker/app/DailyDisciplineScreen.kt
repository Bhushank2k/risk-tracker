package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/**
 * Read-only daily workflow hub. It links existing screens and surfaces known data coverage;
 * it does not create trades, alter risk settings, or place exchange orders.
 */
@Composable
fun DailyDisciplineScreen(
    trades: List<TradeEntry>,
    exchangeFills: List<BinanceJournalFill>,
    syncStatus: BinanceJournalSyncStatus,
    todayPnl: Double?,
    maxDailyLoss: Double,
    onNavigate: (Int) -> Unit
) {
    val context = LocalContext.current
    val reviews = TradeReviewStore(context.applicationContext).load()
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val todaysTrades = trades.filter { canonicalPnlDate(it.date) == today }
    val reviewedCount = trades.count { reviews.containsKey(it.id) }
    val reviewCoverage = if (trades.isEmpty()) null else reviewedCount * 100 / trades.size
    val syncKnown = syncStatus.lastSuccess > 0L && syncStatus.symbolsFailed == 0 &&
        syncStatus.state.startsWith("Up to date", ignoreCase = true)
    val pnlLabel = todayPnl?.let { it.usd() } ?: "Incomplete"
    val dailyLossUsed = todayPnl?.let { (-it).coerceAtLeast(0.0) }
    val lossRemaining = dailyLossUsed?.let { (maxDailyLoss - it).coerceAtLeast(0.0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "A practical session workflow built from your existing records. This is guidance, not an exchange trading control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session snapshot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    SnapshotRow("Today's recorded P&L", pnlLabel)
                    SnapshotRow("Trades recorded today", todaysTrades.size.toString())
                    SnapshotRow("Imported exchange fills", exchangeFills.size.toString())
                    SnapshotRow("Journal reviews complete", if (reviewCoverage == null) "No journal history" else "$reviewedCount / ${trades.size} ($reviewCoverage%)")
                    SnapshotRow("Daily loss budget remaining", lossRemaining?.usd() ?: "Unavailable")
                    Text(
                        if (todayPnl == null) "Daily P&L coverage is incomplete or unavailable. Remaining loss budget is not estimated."
                        else "Loss-budget figure is a simple comparison against the configured limit and today's selected recorded P&L; it is not a live margin or liquidation estimate.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            WorkflowSection(
                title = "1 · Prepare before trading",
                description = "Check the account context, define the loss you can accept, and size the position before entering it.",
                actions = listOf(
                    "Position & leverage calculator" to 2,
                    "Trade Risk Gate" to 11
                ),
                onNavigate = onNavigate
            )
        }
        item {
            WorkflowSection(
                title = "2 · Monitor records and exposure",
                description = "Confirm that imported data is current. Cached or partial records should never be treated as complete.",
                actions = listOf(
                    "Binance sync health" to 18,
                    "Live positions & orders" to 16
                ),
                onNavigate = onNavigate
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (syncKnown) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = if (syncKnown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                Text(
                    if (syncKnown) "Last successful sync: ${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(syncStatus.lastSuccess))}"
                    else "Sync freshness is not confirmed: ${syncStatus.state}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            WorkflowSection(
                title = "3 · Review after closing",
                description = "Compare the actual result with the saved plan, record execution quality, and learn from mistakes.",
                actions = listOf(
                    "Trade journal" to 1,
                    "Post-trade reviews" to 14,
                    "Trading analytics" to 20
                ),
                onNavigate = onNavigate
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Data note: an empty result can mean missing history, an incomplete sync, or no recorded activity. It must not be interpreted as zero profit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun WorkflowSection(
    title: String,
    description: String,
    actions: List<Pair<String, Int>>,
    onNavigate: (Int) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions.forEach { (label, destination) ->
                TextButton(onClick = { onNavigate(destination) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                    Text(label)
                }
            }
        }
    }
}
