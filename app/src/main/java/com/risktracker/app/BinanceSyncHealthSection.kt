package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BinanceSyncHealthSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(BinanceAutoJournalStore.syncStatus(context)) }
    var fillCount by remember { mutableIntStateOf(BinanceAutoJournalStore.load(context).size) }
    var loading by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }

    fun syncNow() {
        if (loading) return
        if (BinanceCredentialStore(context).read() == null) {
            status = BinanceAutoJournalStore.syncStatus(context)
            operationError = "Binance is not connected. Add a read-only API key in the connection section above."
            return
        }
        loading = true
        operationError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) }
                status = BinanceAutoJournalStore.syncStatus(context)
                fillCount = BinanceAutoJournalStore.load(context).size
            } catch (e: Exception) {
                operationError = e.message ?: "Sync failed unexpectedly. Check connectivity and API permissions."
                status = BinanceAutoJournalStore.syncStatus(context)
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val syncHealthy = status.state.startsWith("Up to date")
                    val syncFailed = status.state.contains("failed", true) || status.state.contains("error", true)
                    Icon(
                        when {
                            syncHealthy -> Icons.Filled.CheckCircle
                            syncFailed -> Icons.Filled.Error
                            else -> Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = when {
                            syncHealthy -> MaterialTheme.colorScheme.primary
                            syncFailed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                    Text(status.state, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                Button(onClick = { syncNow() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (loading) "Syncing…" else "Sync now")
                }
                operationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Latest automatic journal sync", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Saved executions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fillCount.toString(), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Records fetched last attempt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.fillsFetched.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("New records added", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.fillsInserted.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Symbols attempted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.symbolsAttempted.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Symbol requests failed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.symbolsFailed.toString(), color = if (status.symbolsFailed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
