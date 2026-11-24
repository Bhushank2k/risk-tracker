package com.risktracker.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CryptoTaxReportScreen(onJournalImported: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trades by remember { mutableStateOf(CryptoTaxHistory.load(context)) }
    var rateText by remember { mutableStateOf("83.0") }
    var status by remember { mutableStateOf("Ready to import Binance exports.") }
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<CryptoTaxImportPreview?>(null) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sideFilter by remember { mutableStateOf("ALL") }
    var marketFilter by remember { mutableStateOf("ALL") }
    var showSpotEstimate by remember { mutableStateOf(SPOT_ESTIMATE_DEFAULT_ENABLED) }
    var selectedYear by remember {
        mutableStateOf(run {
            val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
            val year = now.get(java.util.Calendar.YEAR)
            (if (now.get(java.util.Calendar.MONTH) >= java.util.Calendar.APRIL) year else year - 1).toString()
        })
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) { CryptoTaxHistory.previewUri(context, uri, trades) }
                    pendingImport = preview
                    status = "Import preview ready. Review the detected rows before adding them to your history."
                } catch (e: Exception) {
                    status = AppErrorMessage.from(e, "Import failed. Check the file format and try again.")
                } finally { busy = false }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val rate = rateText.toDouble()
            val summaries = CryptoTaxHistory.summaries(trades, rate)
            val csv = buildString {
                appendLine("Financial year (start year),Transactions,Exchange-reported realized P&L (INR),Fees (INR),Sell proceeds (INR),Buy value (INR),Rows missing realized P&L")
                summaries.forEach { s -> appendLine("${s.year},${s.trades},${s.realizedPnlInr},${s.feesInr},${s.proceedsInr},${s.costBasisInr},${s.missingPnlRecords}") }
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
            status = "Year-wise CSV report exported. Review missing P&L rows before relying on totals."
        } catch (e: Exception) { status = AppErrorMessage.from(e, "Export failed. Check the conversion rate and try again.") }
    }
    val transactionExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val selectedFinancialYear = selectedYear.toIntOrNull()
                ?: throw IllegalArgumentException("Enter a valid financial-year start year.")
            val csv = historicalTradesToCsv(trades, selectedFinancialYear)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                ?: throw IllegalStateException("Could not write to the selected destination.")
            status = "Exported ${trades.count { it.year == selectedFinancialYear }} transaction rows for FY ${selectedFinancialYear}-${(selectedFinancialYear + 1).toString().takeLast(2)}. Review source data before using for tax reporting."
        } catch (e: Exception) {
            status = AppErrorMessage.from(e, "Transaction export failed. Check storage access and try again.")
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val document = android.graphics.pdf.PdfDocument()
            try {
                val rate = rateText.toDoubleOrNull() ?: error("Enter a valid INR conversion rate.")
                val reportLines = CryptoTaxHistory.printableReportLines(trades, rate).flatMap { wrapTaxReportLine(it) }
                reportLines.chunked(47).ifEmpty { listOf(emptyList()) }.forEachIndexed { pageIndex, pageLines ->
                    val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                    val titlePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 14f
                        isFakeBoldText = true
                    }
                    val bodyPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 9f
                    }
                    page.canvas.drawText("Risk Tracker - India crypto tax review", 36f, 32f, titlePaint)
                    pageLines.forEachIndexed { lineIndex, line ->
                        page.canvas.drawText(line, 36f, 56f + lineIndex * 15f, bodyPaint)
                    }
                    page.canvas.drawText("Page ${pageIndex + 1}", 520f, 820f, bodyPaint)
                    document.finishPage(page)
                }
                val output = context.contentResolver.openOutputStream(uri) ?: error("Could not write the PDF.")
                output.use { document.writeTo(it) }
                status = "Printable tax review exported. Verify source statements, missing records and INR assumptions before use."
            } catch (e: Exception) {
                status = AppErrorMessage.from(e, "PDF export failed. Check storage access and try again.")
            } finally {
                document.close()
            }
        }
    }

    val summaries = remember(trades, rateText) {
        runCatching { CryptoTaxHistory.summaries(trades, rateText.toDouble()) }.getOrDefault(emptyList())
    }
    val costReview = remember(trades, rateText) {
        runCatching { CryptoTaxHistory.costBasisReview(trades, rateText.toDouble()) }.getOrNull()
    }
    val quality = remember(trades) { CryptoTaxHistory.dataQuality(trades) }
    val year = selectedYear.toIntOrNull()
    val yearTrades = trades.filter { it.year == year }
    val yearSummary = summaries.firstOrNull { it.year == year }
    val availableYears = (trades.map { it.year } + listOfNotNull(year)).distinct().sortedDescending()
    val filteredTrades = yearTrades.asSequence()
        .filter { searchQuery.isBlank() || it.symbol.contains(searchQuery.trim(), ignoreCase = true) || it.source.contains(searchQuery.trim(), ignoreCase = true) }
        .filter { sideFilter == "ALL" || it.side == sideFilter }
        .filter { marketFilter == "ALL" || it.market.contains(marketFilter, ignoreCase = true) }
        .sortedByDescending { it.timestamp }
        .toList()
    val yearLabel = year?.let { "FY $it–${(it + 1).toString().takeLast(2)}" } ?: "Select financial year"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Crypto tax", style = MaterialTheme.typography.headlineSmall)
            Text("India · Financial year 1 April–31 March", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review aid only", style = MaterialTheme.typography.titleSmall)
                Text("Exchange-reported realized P&L is not automatically taxable VDA income. Verify historical INR values, transfers and tax treatment with a qualified Indian tax professional.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Financial year", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { yearMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(yearLabel, modifier = Modifier.weight(1f))
                    Text("▾")
                }
                DropdownMenu(expanded = yearMenuExpanded, onDismissRequest = { yearMenuExpanded = false }) {
                    availableYears.forEach { fy ->
                        DropdownMenuItem(
                            text = { Text("FY $fy–${(fy + 1).toString().takeLast(2)} · ${trades.count { it.year == fy }} trades") },
                            onClick = { selectedYear = fy.toString(); yearMenuExpanded = false }
                        )
                    }
                    if (availableYears.isEmpty()) {
                        DropdownMenuItem(text = { Text("No imported years yet") }, onClick = { yearMenuExpanded = false })
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Year at a glance", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaxMetricCard("Transactions", yearSummary?.trades?.toString() ?: "0", "Imported fills", Modifier.weight(1f))
                TaxMetricCard("Realized P&L", yearSummary?.let { "₹${it.realizedPnlInr.num(2)}" } ?: "—",
                    "Exchange-reported", Modifier.weight(1f), pnlColor(yearSummary?.realizedPnlInr ?: 0.0))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaxMetricCard("Recorded fees", yearSummary?.let { "₹${it.feesInr.num(2)}" } ?: "—",
                    "Using manual FX", Modifier.weight(1f))
                TaxMetricCard("Missing P&L", yearSummary?.missingPnlRecords?.toString() ?: "0",
                    "Rows needing review", Modifier.weight(1f),
                    if ((yearSummary?.missingPnlRecords ?: 0) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }

        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Import history", style = MaterialTheme.typography.titleMedium)
                    Text("${trades.size} rows", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Import Binance Spot or Futures CSV / ZIP. Preview and inspect duplicates or rejected rows before saving. Confirmed fills merge into the shared Binance trade history; manual journal and Daily P&L records remain unchanged.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { importPicker.launch(arrayOf("text/*", "application/zip", "application/octet-stream", "application/vnd.ms-excel")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Importing…" else "Import Binance CSV / ZIP")
                }
                pendingImport?.let { preview ->
                    val firstYear = preview.trades.minOfOrNull { it.year }
                    val lastYear = preview.trades.maxOfOrNull { it.year }
                    RiskCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Review import before saving", style = MaterialTheme.typography.titleSmall)
                            Text("Detected ${preview.trades.size} · New ${preview.newRecordCount} · Duplicates ${preview.duplicateRecordCount}")
                            if (preview.issues.isNotEmpty()) {
                                Text("${preview.issues.size} rows/files need review", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleSmall)
                                preview.issues.take(20).forEach { issue ->
                                    val location = issue.rowNumber?.let { "row $it" } ?: "file"
                                    Text("${issue.fileName} · $location: ${issue.reason}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                    if (issue.sample.isNotBlank()) Text(issue.sample, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (preview.issues.size > 20) Text("Showing first 20 issues.", style = MaterialTheme.typography.bodySmall)
                            } else Text("No skipped rows detected.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            if (firstYear != null && lastYear != null) {
                                Text("Coverage: FY $firstYear–${(lastYear + 1).toString().takeLast(2)} through FY $lastYear–${(lastYear + 1).toString().takeLast(2)}")
                            }
                            Text("Nothing is saved until you confirm. Existing records are retained.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = {
                                val (merged, added) = CryptoTaxHistory.deduplicate(trades, preview.trades)
                                // Keep the report store limited to CSV imports; API/CSV journal fills remain canonical in the shared journal.
                                CryptoTaxHistory.save(context, merged.filterNot { it.source.startsWith("Journal ") })
                                val journalAdded = CryptoTaxHistory.mergeIntoJournal(context, preview.trades)
                                trades = CryptoTaxHistory.load(context)
                                pendingImport = null
                                onJournalImported()
                                status = "Imported ${preview.trades.size} rows; added $added to trade history and $journalAdded to the shared journal; duplicates were reconciled."
                            }, enabled = !busy && preview.trades.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                Text("Confirm and import")
                            }
                            OutlinedButton(onClick = { pendingImport = null; status = "Import cancelled. No records were changed." },
                                modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Data health", style = MaterialTheme.typography.titleMedium)
                    Text("${quality.score}/100", style = MaterialTheme.typography.titleMedium,
                        color = if (quality.score < 70) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Text("Completeness heuristic only — not a tax-accuracy score.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (quality.warnings.isEmpty()) {
                    Text("No completeness warnings detected.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    quality.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                costReview?.let {
                    Text("FIFO reconciliation · ${it.sellRowsWithoutPriorBuys} sell rows without earlier imported buys · ${it.possibleDuplicateRows} possible duplicates",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("INR assumptions & year summary", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = rateText, onValueChange = { rateText = it },
                    label = { Text("INR per USDT / quote currency") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("One manual estimate is applied to every row; it is not historical date-specific FX.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (yearSummary != null) {
                    Text("Sell proceeds: ₹${yearSummary.proceedsInr.num(2)}")
                    Text("Buy value (not a tax cost-basis determination): ₹${yearSummary.costBasisInr.num(2)}")
                } else Text("No imported records found for this financial year.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportPicker.launch("risk-tracker-india-crypto-tax-years.csv") },
                        modifier = Modifier.weight(1f), enabled = trades.isNotEmpty()) { Text("Year CSV") }
                    OutlinedButton(onClick = { transactionExportPicker.launch("risk-tracker-tax-transactions-fy-$selectedYear.csv") },
                        modifier = Modifier.weight(1f), enabled = yearTrades.isNotEmpty()) { Text("Transactions CSV") }
                }
                OutlinedButton(onClick = { pdfLauncher.launch("risk-tracker-india-crypto-tax-review.pdf") },
                    modifier = Modifier.fillMaxWidth(), enabled = trades.isNotEmpty()) { Text("Export printable PDF review") }
            }
        }

        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Indicative FIFO review", style = MaterialTheme.typography.titleMedium)
                        Text("Optional · incomplete Spot-only estimate", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = showSpotEstimate, onCheckedChange = { showSpotEstimate = it })
                }
                if (showSpotEstimate) {
                    Text("Not account-wide P&L or a tax calculation. Matching is by exact symbol and uses the single manual FX rate.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (costReview != null) {
                        Text("Matched historical buy cost estimate: ₹${costReview.estimatedMatchedCostInr.num(2)}")
                        Text("Unmatched sell quantity: ${costReview.unmatchedSellQuantity}")
                        Text("Sell rows without prior buys: ${costReview.sellRowsWithoutPriorBuys}")
                        Text("Possible duplicate rows: ${costReview.possibleDuplicateRows}")
                        costReview.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else Text("Enter a valid positive INR conversion rate to run this review.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Trade history", style = MaterialTheme.typography.titleMedium)
            Text("${filteredTrades.size} matching rows · showing up to 100 newest", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Search symbol or source") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Side", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "BUY", "SELL").forEach { value ->
                    FilterChip(selected = sideFilter == value, onClick = { sideFilter = value },
                        label = { Text(if (value == "ALL") "All" else value) })
                }
            }
            Text("Market", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "Spot", "Futures").forEach { value ->
                    FilterChip(selected = marketFilter == value, onClick = { marketFilter = value },
                        label = { Text(if (value == "ALL") "All" else value) })
                }
            }
            if (filteredTrades.isEmpty()) {
                RiskCard(Modifier.fillMaxWidth()) {
                    Text(if (yearTrades.isEmpty()) "No transactions for this financial year. Import a Binance history export to get started."
                        else "No trades match these filters.", modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                filteredTrades.take(100).forEach { trade ->
                    RiskCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${trade.symbol} · ${trade.side}", style = MaterialTheme.typography.titleSmall)
                                Text(trade.market, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(trade.timestamp)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Qty ${trade.quantity} · Price ${trade.price} · Fee ${trade.fee} ${trade.feeAsset}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Exchange: ${trade.exchange} · Source: ${trade.source}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            trade.realizedPnl?.let { Text("Exchange realized P&L: ${it.num(8)}", style = MaterialTheme.typography.bodySmall,
                                color = pnlColor(it)) }
                        }
                    }
                }
                if (filteredTrades.size > 100) Text("Showing latest 100 of ${filteredTrades.size} matching rows.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (summaries.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Available financial years", style = MaterialTheme.typography.titleMedium)
                summaries.sortedByDescending { it.year }.forEach { summary ->
                    FilterChip(selected = selectedYear == summary.year.toString(),
                        onClick = { selectedYear = summary.year.toString() },
                        label = { Text("FY ${summary.year}–${(summary.year + 1).toString().takeLast(2)} · ${summary.trades} trades · ₹${summary.realizedPnlInr.num(0)}") })
                }
            }
        }
        Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
}


private fun wrapTaxReportLine(line: String, maxChars: Int = 88): List<String> {
    if (line.length <= maxChars) return listOf(line)
    val out = mutableListOf<String>()
    var current = StringBuilder()
    line.trim().split(" ").filter { it.isNotBlank() }.forEach { word ->
        if (word.length > maxChars) {
            if (current.isNotEmpty()) {
                out += current.toString()
                current = StringBuilder()
            }
            word.chunked(maxChars).forEach { out += it }
        } else if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= maxChars) {
            current.append(' ').append(word)
        } else {
            out += current.toString()
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) out += current.toString()
    return out.ifEmpty { listOf("") }
}


@Composable
private fun TaxMetricCard(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
