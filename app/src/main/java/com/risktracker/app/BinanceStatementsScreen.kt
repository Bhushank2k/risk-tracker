package com.risktracker.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
    var selectedCategory by remember { mutableStateOf("All") }
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
        Text("Account activity", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only Binance history for deposits, withdrawals, distributions, transfers and Futures income. Records are deduplicated and kept separate from your journal and Daily P&L.", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent history only", style = MaterialTheme.typography.titleMedium)
                Text("This screen fetches a recent 7-day window for supported categories. Binance API history limits vary by statement type, so this is not a lifetime archive and does not include every Spot trade, order, fiat, Earn, or tax document.")
                Text("For older activity, download Binance's official account data export and use the historical import tools where supported.", style = MaterialTheme.typography.bodySmall)
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
                                status = if (result.failedCategories.isEmpty()) {
                                    "Sync finished: ${result.newRecords} new records; ${result.totalRecords} total stored."
                                } else {
                                    "Partial sync: ${result.newRecords} new records; ${result.totalRecords} total stored. Failed categories: ${result.failedCategories.joinToString()}. Check API permissions and retry."
                                }
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
        val reconciliation = BinanceCapitalReconciliationEngine.reconcile(rows)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Capital reconciliation", style = MaterialTheme.typography.titleMedium)
                Text("Net external cash flow by asset. Deposits minus withdrawals; internal transfers and trading income are kept separate.", style = MaterialTheme.typography.bodySmall)
                if (reconciliation.assetFlows.isEmpty()) {
                    Text("No completed deposits or withdrawals found in the stored statement history.")
                } else {
                    reconciliation.assetFlows.forEach { flow ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(flow.asset, style = MaterialTheme.typography.titleSmall)
                                Text("${flow.completedDepositCount} deposits · ${flow.completedWithdrawalCount} withdrawals", style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text("Net " + "%.8f".format(Locale.US, flow.netExternalFlow).trimEnd('0').trimEnd('.'))
                                Text("In ${"%.8f".format(Locale.US, flow.deposits).trimEnd('0').trimEnd('.')} · Out ${"%.8f".format(Locale.US, flow.withdrawals).trimEnd('0').trimEnd('.')}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val firstRecord = reconciliation.firstRecordTime
                val lastRecord = reconciliation.lastRecordTime
                if (firstRecord != null && lastRecord != null) {
                    Text("Stored records span " +
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(firstRecord)) +
                        " – " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(lastRecord)) +
                        " · ${reconciliation.totalStoredRecords} records", style = MaterialTheme.typography.bodySmall)
                }
                Text("Starting capital is not inferred: the API sync covers a recent, best-effort window and may miss older cash flows. Import verified historical Binance exports before treating this as lifetime reconciliation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        val futuresIncomeByType = rows.filter { it.category == "Futures income" }
            .groupBy { row -> row.details.substringBefore(" | ").ifBlank { "Other income" } }
            .mapValues { (_, items) ->
                items.groupBy { it.asset.ifBlank { "Unknown asset" } }
                    .mapValues { (_, assetRows) -> assetRows.sumOf { it.amount } }
            }
        if (futuresIncomeByType.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Futures income by type", style = MaterialTheme.typography.titleMedium)
                    Text("Signed amounts by asset. This is only the saved recent window, not lifetime P&L.", style = MaterialTheme.typography.bodySmall)
                    futuresIncomeByType.toSortedMap().forEach { (type, byAsset) ->
                        Text(type, style = MaterialTheme.typography.titleSmall)
                        byAsset.toSortedMap().forEach { (asset, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(asset)
                                Text((if (amount > 0.0) "+" else "") + "%.8f".format(Locale.US, amount).trimEnd('0').trimEnd('.'))
                            }
                        }
                    }
                }
            }
        }
        Text("Stored records: ${rows.size}", style = MaterialTheme.typography.titleMedium)
        val last = BinanceStatementStore.lastSync(context)
        if (last > 0) Text("Last statement sync: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last)))
        val categories = listOf("All") + rows.map { it.category }.distinct().sorted()
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { category ->
                FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category) })
            }
        }
        val visibleRows = rows.filter { selectedCategory == "All" || it.category == selectedCategory }
        visibleRows.take(100).forEach { row ->
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
        if (visibleRows.size > 100) Text("Showing newest 100 of ${visibleRows.size} filtered records. CSV export includes all stored records.")
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }
}
