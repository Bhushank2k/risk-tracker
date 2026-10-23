package com.risktracker.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    var showTaxReports by remember { mutableStateOf(false) }
    var showStatements by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(BinanceSyncData.load(context)) }
    var lastSync by remember { mutableStateOf(BinanceSyncData.lastSync(context)) }
    var selectedYear by remember { mutableStateOf(2026) }
    val exportPrefs = remember { context.getSharedPreferences("binance_income_exports_v1", 0) }
    var exportId by remember { mutableStateOf(exportPrefs.getString("year_2026", null)) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    val yearRows = rows.filter { it.date.take(4).toIntOrNull() == selectedYear }
    val yearSpot = yearRows.sumOf { it.spotPnl }
    val yearFutures = yearRows.sumOf { it.futuresPnl }
    val incomeCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = "Importing the $selectedYear Futures income export…"
            scope.launch {
                try {
                    val daily = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            BinanceIncomeCsvImporter.parse(it, selectedYear)
                        } ?: error("Could not open the selected file.")
                    }
                    BinanceSyncData.replaceFuturesYear(context, selectedYear, daily)
                    rows = BinanceSyncData.load(context)
                    lastSync = BinanceSyncData.lastSync(context)
                    onSynced()
                    status = "Imported ${daily.size} daily totals for $selectedYear. Confirm the CSV is a complete full-year income export."
                } catch (e: Exception) {
                    status = "CSV import failed: " + (e.message ?: "check the export format")
                } finally {
                    busy = false
                }
            }
        }
    }
    

    if (showStatements) {
        BinanceStatementsScreen(onBack = { showStatements = false })
        return
    }

    if (showTaxReports) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showTaxReports = false }) { Text("Back to Binance sync") }
            CryptoTaxReportScreen()
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Binance trade sync", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = { showTaxReports = true }, modifier = Modifier.fillMaxWidth()) { Text("Historical trades & India tax reports") }
        OutlinedButton(onClick = { showStatements = true }, modifier = Modifier.fillMaxWidth()) { Text("Automatic Binance account statements") }
        Text("Imports Spot realized P&L using FIFO matching and Futures realized P&L, commissions and funding income. Imported results remain separate from manual Daily P&L entries. The standard Futures income API exposes only recent history; older years require a Binance historical export/import workflow.", style = MaterialTheme.typography.bodyMedium)
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
                        BinanceStatementWorker.schedule(context)
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
                        BinanceStatementWorker.cancel(context)
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Year-wise imported history", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { yearMenuExpanded = true }, modifier = Modifier.weight(1f)) {
                        Text("Year: $selectedYear")
                    }
                    DropdownMenu(expanded = yearMenuExpanded, onDismissRequest = { yearMenuExpanded = false }) {
                        (2018..2026).forEach { year ->
                            DropdownMenuItem(
                                text = { Text(year.toString()) },
                                onClick = { selectedYear = year; exportId = exportPrefs.getString("year_$year", null); yearMenuExpanded = false }
                            )
                        }
                    }
                    OutlinedButton(onClick = {
                        rows = BinanceSyncData.load(context)
                        lastSync = BinanceSyncData.lastSync(context)
                    }) { Text("Refresh") }
                }
                Button(
                    onClick = { incomeCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import historical Futures income CSV for $selectedYear") }
                Text(
                    "Use a complete Binance USDⓈ-M Futures income-history export containing only $selectedYear. Import replaces that year's saved Futures totals, preserves Spot totals and other years, and includes REALIZED_PNL, COMMISSION and FUNDING_FEE rows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val pair = credentials.read()
                        if (pair == null) {
                            status = "Save read-only Binance API credentials first."
                        } else {
                            busy = true
                            status = "Requesting Binance's historical income export for $selectedYear…"
                            scope.launch {
                                try {
                                    val id = withContext(Dispatchers.IO) {
                                        BinanceApi(pair.first, pair.second).requestFuturesIncomeExport(selectedYear)
                                    }
                                    exportPrefs.edit().putString("year_$selectedYear", id).apply()
                                    exportId = id
                                    status = "Export request saved for $selectedYear (ID: $id). Use Check export status when Binance has prepared it."
                                } catch (e: Exception) {
                                    status = "Export request failed: " + (e.message ?: "check API permissions")
                                } finally { busy = false }
                            }
                        }
                    },
                    enabled = !busy && exportId == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (exportId == null) "Request official Binance income export for $selectedYear" else "Export request saved for $selectedYear") }
                OutlinedButton(
                    onClick = {
                        val pair = credentials.read()
                        val id = exportId
                        if (pair == null) {
                            status = "Save read-only Binance API credentials first."
                        } else if (id.isNullOrBlank()) {
                            status = "Request an export for $selectedYear first."
                        } else {
                            busy = true
                            status = "Checking Binance export status…"
                            scope.launch {
                                try {
                                    val response = withContext(Dispatchers.IO) {
                                        BinanceApi(pair.first, pair.second).futuresIncomeExportStatus(id)
                                    }
                                    val link = listOf("downloadLink", "url", "link")
                                        .firstNotNullOfOrNull { key -> response.optString(key).takeIf { it.startsWith("https://") } }
                                    if (link != null) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                        status = "Opened Binance's export download link. Save/extract the CSV, then import it above. Download links expire; keep this export private."
                                    } else {
                                        val exportStatus = response.optString("status").ifBlank { "not ready" }
                                        status = "Binance export status: $exportStatus. Check again later. Response did not contain a download link yet."
                                    }
                                } catch (e: Exception) {
                                    status = "Could not check export: " + (e.message ?: "the request may still be processing")
                                } finally { busy = false }
                            }
                        }
                    },
                    enabled = !busy && exportId != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Check export status / open download link") }
                if (exportId != null) {
                    Text("Saved request ID for $selectedYear: $exportId", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Quota warning: Binance limits asynchronous income-export requests to five per month, shared with the website, and each request can cover at most one year. The request ID is saved on this device to avoid requesting the same year again. A download link is temporary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text("Spot: " + yearSpot.usd() + " · Futures: " + yearFutures.usd(),
                    color = MaterialTheme.colorScheme.primary)
                Text("Combined total: " + (yearSpot + yearFutures).usd(),
                    style = MaterialTheme.typography.titleMedium,
                    color = pnlColor(yearSpot + yearFutures))
                if (yearRows.isEmpty()) {
                    Text(
                        if (selectedYear < 2026) "No saved rows for $selectedYear. The standard Futures income endpoint cannot backfill old years; import an official Binance historical export to recover unavailable history."
                        else "No imported rows for $selectedYear yet. Run Sync now to retrieve currently available records.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    yearRows.sortedByDescending { it.date }.take(31).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.date)
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(row.total.usd(), color = pnlColor(row.total))
                                Text("Spot ${row.spotPnl.usd()} · Futures ${row.futuresPnl.usd()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (yearRows.size > 31) {
                        Text("Showing 31 of ${yearRows.size} saved days for $selectedYear.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    }
}
