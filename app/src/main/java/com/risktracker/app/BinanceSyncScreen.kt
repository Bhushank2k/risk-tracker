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
    var pendingImportYear by remember { mutableStateOf(2026) }
    val auditStore = remember { BinanceImportAuditStore(context) }
    var importReport by remember { mutableStateOf(auditStore.load(2026)) }
    val importBackupStore = remember { BinanceYearImportBackupStore(context) }
    var pendingImportReport by remember { mutableStateOf<BinanceIncomeImportReport?>(null) }
    var hasYearBackup by remember { mutableStateOf(importBackupStore.hasBackup(2026)) }
    val exportPrefs = remember { context.getSharedPreferences("binance_income_exports_v1", 0) }
    var exportId by remember { mutableStateOf(exportPrefs.getString("year_2026", null)) }
    val reconciliationPrefs = remember { context.getSharedPreferences("binance_year_reconciliation_v1", 0) }
    var sourceTotalText by remember { mutableStateOf(reconciliationPrefs.getString("source_total_2026", "") ?: "") }
    var reconciliationMessage by remember { mutableStateOf("") }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    val yearRows = rows.filter { it.date.take(4).toIntOrNull() == selectedYear }
    val yearSpot = yearRows.sumOf { it.spotPnl }
    val yearFutures = yearRows.sumOf { it.futuresPnl }
    val incomeCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            val importYear = pendingImportYear
            status = "Importing the $importYear Futures income export…"
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            BinanceIncomeCsvImporter.parseWithReport(it, importYear)
                        } ?: error("Could not open the selected file.")
                    }
                    pendingImportYear = importYear
                    pendingImportReport = report
                    status = "Preview ready for $importYear. Review the totals and confirm to replace the yearly Futures data."
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
                                onClick = { selectedYear = year; importReport = auditStore.load(year); exportId = exportPrefs.getString("year_$year", null); sourceTotalText = reconciliationPrefs.getString("source_total_$year", "") ?: ""; reconciliationMessage = ""; hasYearBackup = importBackupStore.hasBackup(year); yearMenuExpanded = false }
                            )
                        }
                    }
                    OutlinedButton(onClick = {
                        rows = BinanceSyncData.load(context)
                        lastSync = BinanceSyncData.lastSync(context)
                    }) { Text("Refresh") }
                }
                Button(
                    onClick = {
                        pendingImportYear = selectedYear
                        incomeCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel"))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import historical Futures income CSV for $selectedYear") }
                Text(
                    "Use a complete Binance USDⓈ-M Futures income-history export containing only $selectedYear. The app previews the import first; confirming replaces that years Futures totals and preserves Spot totals and other years.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                pendingImportReport?.takeIf { pendingImportYear == selectedYear }?.let { preview ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Import preview — $selectedYear", style = MaterialTheme.typography.titleSmall)
                            Text("Will replace Futures totals for $selectedYear only.")
                            Text("Daily totals in file: ${preview.daily.size}")
                            Text("Net total in file: ${preview.daily.values.sum().usd()}")
                            Text("Included rows: ${preview.includedRows} · ignored rows: ${preview.ignoredRows} · exact duplicates skipped: ${preview.duplicateRows}")
                            Text("Observed activity: ${preview.firstActivityDate} to ${preview.lastActivityDate}")
                            Text("Existing saved Futures total: ${yearFutures.usd()}")
                            Text("Confirming first saves a local recovery snapshot. Activity dates do not prove export completeness.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pendingImportReport = null; status = "Import preview cancelled. Saved yearly data was not changed." }, modifier = Modifier.weight(1f), enabled = !busy) { Text("Cancel") }
                                Button(onClick = {
                                    busy = true
                                    status = "Saving recovery snapshot and importing $selectedYear…"
                                    scope.launch {
                                        try {
                                            val report = preview
                                            val year = pendingImportYear
                                            withContext(Dispatchers.IO) {
                                                val existing = BinanceSyncData.load(context).filter { it.date.take(4).toIntOrNull() == year }.associate { it.date to it.futuresPnl }.filterValues { it != 0.0 }
                                                importBackupStore.save(year, existing)
                                                BinanceSyncData.replaceFuturesYear(context, year, report.daily)
                                                auditStore.save(year, report)
                                            }
                                            rows = BinanceSyncData.load(context)
                                            lastSync = BinanceSyncData.lastSync(context)
                                            if (selectedYear == year) importReport = report
                                            hasYearBackup = importBackupStore.hasBackup(year)
                                            pendingImportReport = null
                                            onSynced()
                                            status = "Imported ${report.daily.size} daily totals for $year. A recovery snapshot is available below."
                                        } catch (e: Exception) { status = "Import failed: " + (e.message ?: "could not save/import data") }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.weight(1f), enabled = !busy) { Text("Confirm import") }
                            }
                        }
                    }
                }
                if (hasYearBackup) {
                    OutlinedButton(onClick = {
                        busy = true
                        status = "Restoring the pre-import Futures snapshot for $selectedYear…"
                        scope.launch {
                            try {
                                val backup = withContext(Dispatchers.IO) { importBackupStore.load(selectedYear) } ?: error("No recovery snapshot is available.")
                                withContext(Dispatchers.IO) { BinanceSyncData.replaceFuturesYear(context, selectedYear, backup) }
                                rows = BinanceSyncData.load(context)
                                lastSync = BinanceSyncData.lastSync(context)
                                onSynced()
                                status = "Restored the pre-import Futures snapshot for $selectedYear. The snapshot remains available."
                            } catch (e: Exception) { status = "Restore failed: " + (e.message ?: "could not restore snapshot") }
                            finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore pre-import Futures data for $selectedYear") }
                }
                importReport?.let { report ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Last import audit", style = MaterialTheme.typography.titleSmall)
                            Text("Activity dates: ${report.firstActivityDate} to ${report.lastActivityDate}")
                            Text("Included income rows: ${report.includedRows} · ignored other income rows: ${report.ignoredRows}")
                            Text("Exact duplicate rows skipped: ${report.duplicateRows}")
                            Text("Realized P&L: ${report.rowsByType["REALIZED_PNL"] ?: 0} rows · commissions: ${report.rowsByType["COMMISSION"] ?: 0} · funding: ${report.rowsByType["FUNDING_FEE"] ?: 0}")
                            Text("Net imported Futures P&L: ${report.daily.values.sum().usd()}")
                            Text("Audit limitation: these dates describe rows present in the file, not the full coverage of the export. Compare the file with Binance's export period and statements before treating this year as complete.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Button(
                    onClick = {
                        val pair = credentials.read()
                        if (pair == null) {
                            status = "Save read-only Binance API credentials first."
                        } else {
                            val requestYear = selectedYear
                            busy = true
                            status = "Requesting Binance's historical income export for $requestYear…"
                            scope.launch {
                                try {
                                    val id = withContext(Dispatchers.IO) {
                                        BinanceApi(pair.first, pair.second).requestFuturesIncomeExport(requestYear)
                                    }
                                    exportPrefs.edit().putString("year_$requestYear", id).apply()
                                    if (selectedYear == requestYear) exportId = id
                                    status = "Export request saved for $requestYear (ID: $id). Use Check export status when Binance has prepared it."
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
                Spacer(Modifier.height(8.dp))
                Text("Year reconciliation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optional cross-check: enter the net Futures income total from a Binance statement/export for exactly $selectedYear and the same income categories. This is a manual comparison, not proof that the underlying file is complete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sourceTotalText,
                    onValueChange = { sourceTotalText = it; reconciliationMessage = "" },
                    label = { Text("Binance source net total (USDT)") },
                    placeholder = { Text("e.g. 1234.56") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val sourceTotal = sourceTotalText.trim().toDoubleOrNull()
                        if (sourceTotal == null || !sourceTotal.isFinite()) {
                            reconciliationMessage = "Enter a valid numeric source total first."
                        } else {
                            reconciliationPrefs.edit().putString("source_total_$selectedYear", sourceTotal.toString()).apply()
                            val importedTotal = yearFutures
                            val comparison = BinanceYearReconciliation.compare(importedTotal, sourceTotal)
                            val delta = comparison.delta
                            reconciliationMessage = if (comparison.status == BinanceReconciliationStatus.MATCHED_TOTALS) {
                                "Totals match within 0.01 USDT. This confirms only the totals compared, not export completeness."
                            } else {
                                "Discrepancy: imported total differs from source by ${delta.usd()}. Check year, currency, categories and export coverage."
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save & compare $selectedYear totals") }
                if (sourceTotalText.isNotBlank()) {
                    val sourceTotal = sourceTotalText.trim().toDoubleOrNull()
                    if (sourceTotal != null && sourceTotal.isFinite()) {
                        val comparison = BinanceYearReconciliation.compare(yearFutures, sourceTotal)
                        val delta = comparison.delta
                        Text(
                            if (comparison.status == BinanceReconciliationStatus.MATCHED_TOTALS) "Current status: totals match (≤ 0.01 USDT)" else "Current status: discrepancy (${delta.usd()})",
                            color = if (comparison.status == BinanceReconciliationStatus.MATCHED_TOTALS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("Current status: source total needs a valid number.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Current status: not reconciled", style = MaterialTheme.typography.bodySmall)
                }
                if (reconciliationMessage.isNotBlank()) Text(reconciliationMessage, style = MaterialTheme.typography.bodySmall)
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
