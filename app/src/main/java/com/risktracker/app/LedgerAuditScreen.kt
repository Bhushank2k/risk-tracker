package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LedgerAuditScreen() {
    val context = LocalContext.current
    val auditStore = remember { BinanceImportAuditStore(context) }
    val prefs = remember { context.getSharedPreferences("binance_year_reconciliation_v1", 0) }
    var year by remember { mutableStateOf(2026) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var sourceTotalText by remember(year) {
        mutableStateOf(prefs.getString("source_total_$year", "") ?: "")
    }
    val report = auditStore.load(year)
    val importedTotal = report?.daily?.values?.sum() ?: 0.0
    val sourceTotal = sourceTotalText.trim().replace(",", "").toDoubleOrNull()
    val reconciliation = if (sourceTotal != null && sourceTotal.isFinite() && report != null) {
        runCatching { BinanceYearReconciliation.compare(importedTotal, sourceTotal) }.getOrNull()
    } else null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ledger audit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Review historical Binance Futures import coverage and totals separately from estimated journal P&L. A matching total does not prove the export is complete.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { yearMenuExpanded = true }) { Text("Year: $year ▾") }
            DropdownMenu(expanded = yearMenuExpanded, onDismissRequest = { yearMenuExpanded = false }) {
                (2018..2026).toList().reversed().forEach { option ->
                    DropdownMenuItem(text = { Text(option.toString()) }, onClick = {
                        year = option
                        yearMenuExpanded = false
                    })
                }
            }
        }

        if (report == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No saved import report for $year", fontWeight = FontWeight.SemiBold)
                    Text("Import a Binance USDⓈ-M Futures income CSV from Binance data to populate coverage and duplicate-row metadata.")
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Import coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    AuditRow("First activity", report.firstActivityDate.ifBlank { "—" })
                    AuditRow("Last activity", report.lastActivityDate.ifBlank { "—" })
                    AuditRow("Days with activity", report.daily.size.toString())
                    AuditRow("Rows included", report.includedRows.toString())
                    AuditRow("Rows ignored", report.ignoredRows.toString())
                    AuditRow("Duplicate rows", report.duplicateRows.toString())
                    AuditRow("Imported Futures total", importedTotal.usd())
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Rows by income type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (report.rowsByType.isEmpty()) Text("No income-type breakdown was saved.")
                    report.rowsByType.toSortedMap().forEach { (type, count) -> AuditRow(type, count.toString()) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional source-total reconciliation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Enter the independently verified total from your complete Binance statement. This value is stored with the existing year reconciliation settings.")
                    OutlinedTextField(
                        value = sourceTotalText,
                        onValueChange = { sourceTotalText = it },
                        label = { Text("Verified source total (USD)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = {
                        val parsed = sourceTotalText.trim().replace(",", "").toDoubleOrNull()
                        if (parsed != null && parsed.isFinite()) {
                            prefs.edit().putString("source_total_$year", parsed.toString()).apply()
                            sourceTotalText = parsed.toString()
                        }
                    }, enabled = sourceTotalText.isBlank() || sourceTotalText.trim().replace(",", "").toDoubleOrNull()?.isFinite() == true) {
                        Text("Save source total")
                    }
                    when {
                        sourceTotalText.isBlank() -> Text("No source total entered; reconciliation is not evaluated.")
                        sourceTotal == null || !sourceTotal.isFinite() -> Text("Enter a finite number to compare totals.", color = MaterialTheme.colorScheme.error)
                        reconciliation == null -> Text("No saved import report is available for comparison.")
                        reconciliation.status == BinanceReconciliationStatus.MATCHED_TOTALS -> Text(
                            "Totals match within $0.01. Export completeness still needs independent verification.",
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> Text(
                            "Discrepancy: imported total ${reconciliation.importedTotal.usd()}, source total ${reconciliation.sourceTotal.usd()}, delta ${reconciliation.delta.usd()}. Check date coverage, income types and ignored rows.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Spacer(Modifier.padding(top = 2.dp))
        Text(
            "Provenance guardrail: Binance imports and journal estimates are different sources and must not be added together automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}
