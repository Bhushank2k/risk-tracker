package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BinanceSyncScreen(onSynced: () -> Unit) {
    val context = LocalContext.current
    val credentials = remember { BinanceCredentialStore(context) }
    val saved = remember { credentials.read() }
    var apiKey by remember { mutableStateOf(saved?.first ?: "") }
    var secret by remember { mutableStateOf("") }
    var hasSaved by remember { mutableStateOf(saved != null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showTaxReports by remember { mutableStateOf(false) }\n    var rows by remember { mutableStateOf(BinanceSyncData.load(context)) }
    var lastSync by remember { mutableStateOf(BinanceSyncData.lastSync(context)) }
    

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Binance trade sync", style = MaterialTheme.typography.headlineSmall)
        Text("Imports Spot realized P&L using FIFO matching and Futures realized P&L, commissions and funding income. Imported results remain separate from manual Daily P&L entries.", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Read-only API credentials", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Binance API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(secret, { secret = it }, label = { Text(if (hasSaved) "API secret (enter again to rotate)" else "Binance API secret") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = {
                    try {
                        if (secret.isNotBlank()) credentials.save(apiKey, secret)
                        else if (!hasSaved) error("Enter the API secret.")
                        BinanceDailyWorker.schedule(context)
                        hasSaved = true
                        secret = ""
                        status = "Credentials saved encrypted on this device. Daily background sync is scheduled."
                    } catch (e: Exception) { status = e.message ?: "Could not save credentials." }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save & enable daily sync") }
                Text("Create an API key with read-only account access only. Do not enable Spot/Futures trading or withdrawals. API keys are encrypted using Android Keystore and are excluded from backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        credentials.clear()
                        BinanceDailyWorker.cancel(context)
                        apiKey = ""; secret = ""; hasSaved = false
                        status = "Credentials cleared and scheduled sync disabled."
                    }, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                    Button(onClick = {
                        if (credentials.read() == null) { status = "Save valid API credentials first."; return@Button }
                        busy = true
                        status = "Syncing Spot and Futures history…"
                        scope.launch {
                            try {
                                BinanceSyncRunner.sync(context)
                                rows = BinanceSyncData.load(context)
                                lastSync = BinanceSyncData.lastSync(context)
                                onSynced()
                                status = "Sync completed. Imported P&L is visible on Daily P&L."
                            } catch (e: Exception) {
                                status = "Sync failed: " + (e.message ?: "check credentials and API permissions")
                            } finally { busy = false }
                        }
                    }, modifier = Modifier.weight(1f), enabled = !busy) { Text(if (busy) "Syncing…" else "Sync now") }
                }
            }
        }
        if (lastSync > 0L) Text("Last sync: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastSync)), style = MaterialTheme.typography.bodySmall)
        if (rows.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Imported history", style = MaterialTheme.typography.titleMedium)
                    Text("Spot: " + rows.sumOf { it.spotPnl }.usd() + " · Futures: " + rows.sumOf { it.futuresPnl }.usd(), color = MaterialTheme.colorScheme.primary)
                    rows.takeLast(10).reversed().forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.date)
                            Text(row.total.usd(), color = pnlColor(row.total))
                        }
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    }
}
