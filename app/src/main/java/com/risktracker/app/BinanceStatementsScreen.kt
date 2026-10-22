package com.risktracker.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BinanceStatementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(BinanceStatementStore.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(BinanceStatementStore.csv(context)) }
                status = "Statement CSV exported."
            } catch (e: Exception) { status = "Export failed: ${e.message ?: "file error"}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back to Binance sync") }
        Text("Binance account statements", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only sync of available deposits, withdrawals, distributions, transfers and Futures income. Records are deduplicated and saved separately from your existing journal and Daily P&L.", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Coverage matters", style = MaterialTheme.typography.titleMedium)
                Text("Binance does not expose every account statement through one endpoint. API history limits vary by statement type, and this first version fetches a recent 7-day window for supported categories. It does not guarantee a complete archive back to 2019 or include every Spot trade, order, fiat, earn, or tax document.")
                Text("For a complete historical archive, also download Binance's official account data export and import the CSV files through Historical trades & India tax reports.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (BinanceCredentialStore(context).read() == null) {
                        status = "Save read-only Binance API credentials on the previous screen first."
                    } else {
                        busy = true
                        status = "Fetching available statement categories…"
                        scope.launch {
                            try {
                                val result = BinanceStatementSyncRunner.sync(context)
                                rows = BinanceStatementStore.load(context)
                                status = "Sync finished: ${result.first} new records; ${result.second} total stored. Unsupported categories or API retention limits may result in missing records."
                            } catch (e: Exception) {
                                status = "Sync failed: ${e.message ?: "check API access"}"
                            } finally { busy = false }
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Syncing statements…" else "Sync statements now") }
                OutlinedButton(onClick = { export.launch("binance-account-statements.csv") }, enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Export all stored statements as CSV") }
                Text("Daily background sync is scheduled when Binance credentials are saved.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Stored records: ${rows.size}", style = MaterialTheme.typography.titleMedium)
        val last = BinanceStatementStore.lastSync(context)
        if (last > 0) Text("Last statement sync: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last)))
        rows.take(100).forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.category, style = MaterialTheme.typography.titleSmall)
                    Text("${row.asset} ${row.amount} · ${row.status}")
                    Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(row.time)))
                    if (row.reference.isNotBlank()) Text("Ref: ${row.reference}", style = MaterialTheme.typography.bodySmall)
                    if (row.details.isNotBlank()) Text(row.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (rows.size > 100) Text("Showing newest 100 of ${rows.size} records. CSV export includes all stored records.")
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }
}
