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
fun CryptoTaxReportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trades by remember { mutableStateOf(CryptoTaxHistory.load(context)) }
    var rateText by remember { mutableStateOf("83.0") }
    var status by remember { mutableStateOf("Import Binance CSV or ZIP exports. Existing manual journal and Daily P&L records are not changed.") }
    var busy by remember { mutableStateOf(false) }
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
                    val incoming = withContext(Dispatchers.IO) { CryptoTaxHistory.importUri(context, uri) }
                    val (merged, added) = CryptoTaxHistory.deduplicate(trades, incoming)
                    CryptoTaxHistory.save(context, merged)
                    trades = merged
                    status = "Imported ${incoming.size} rows; added $added new records; skipped ${incoming.size - added} duplicates."
                } catch (e: Exception) {
                    status = "Import failed: " + (e.message ?: "unsupported file")
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
        } catch (e: Exception) { status = "Export failed: " + (e.message ?: "invalid INR rate") }
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
                status = "PDF export failed: " + (e.message ?: "could not generate report")
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
    val year = selectedYear.toIntOrNull()
    val yearTrades = trades.filter { it.year == year }
    val yearSummary = summaries.firstOrNull { it.year == year }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Crypto tax reports · India", style = MaterialTheme.typography.headlineSmall)
        Text("Historical Binance trade ledger from 2019 onward, grouped by Indian financial year (1 April–31 March). Imported realized P&L is not automatically the taxable VDA income under Indian law. Reports are review aids, not tax-filing advice; verify records, date-specific INR values and tax treatment with a qualified Indian tax professional.", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import historical trades", style = MaterialTheme.typography.titleMedium)
                Text("Choose a Binance CSV export or ZIP containing CSV files. Importing merges records and skips duplicate IDs; it does not overwrite existing Daily P&L or manual trade journal data.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { importPicker.launch(arrayOf("text/*", "application/zip", "application/octet-stream", "application/vnd.ms-excel")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Importing…" else "Import Binance CSV / ZIP")
                }
                Text("Rows stored: ${trades.size} · Financial years found: ${trades.map { it.year }.distinct().size}", style = MaterialTheme.typography.bodyMedium)
                val quality = CryptoTaxHistory.dataQuality(trades)
                Spacer(Modifier.height(4.dp))
                Text("Data completeness check", style = MaterialTheme.typography.titleSmall)
                Text("Review score: ${quality.score}/100", style = MaterialTheme.typography.titleMedium,
                    color = if (quality.score < 70) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text("This is a data-quality heuristic, not a tax-accuracy guarantee.", style = MaterialTheme.typography.bodySmall)
                quality.warnings.forEach { warning ->
                    Text("• $warning", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Year-wise summary", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(selectedYear, { selectedYear = it.filter(Char::isDigit).take(4) }, label = { Text("Financial year start year (e.g. 2025 = FY 2025-26)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rateText, { rateText = it }, label = { Text("INR per USDT / quote currency (manual estimate)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("This rate is a single user-entered estimate for summary display. It is not a date-specific historical FX calculation.")
                if (yearSummary != null) {
                    Text("Transactions: ${yearSummary.trades}")
                    Text("Imported realized P&L: ₹${yearSummary.realizedPnlInr.num(2)}", color = pnlColor(yearSummary.realizedPnlInr))
                    Text("Recorded fees: ₹${yearSummary.feesInr.num(2)}")
                    Text("Sell proceeds: ₹${yearSummary.proceedsInr.num(2)}")
                    Text("Buy value (not a tax cost-basis determination): ₹${yearSummary.costBasisInr.num(2)}")
                    Text("Rows missing realized P&L: ${yearSummary.missingPnlRecords}", color = if (yearSummary.missingPnlRecords > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                } else Text("No imported records found for this year.")
                OutlinedButton(onClick = { exportPicker.launch("risk-tracker-india-crypto-tax-years.csv") }, modifier = Modifier.fillMaxWidth(), enabled = trades.isNotEmpty()) { Text("Export year-wise CSV report") }
                OutlinedButton(onClick = { pdfLauncher.launch("risk-tracker-india-crypto-tax-review.pdf") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export printable PDF review")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cost-basis & reconciliation review", style = MaterialTheme.typography.titleMedium)
                Text("Indicative FIFO matching by exact symbol, using the same manual conversion rate above. This is not a tax calculation.", style = MaterialTheme.typography.bodySmall)
                if (costReview != null) {
                    Text("Matched historical buy cost (estimate): ₹${costReview.estimatedMatchedCostInr.num(2)}")
                    Text("Sell quantity without earlier imported buys: ${costReview.unmatchedSellQuantity}")
                    Text("Sell rows needing earlier history review: ${costReview.sellRowsWithoutPriorBuys}",
                        color = if (costReview.sellRowsWithoutPriorBuys > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Possible duplicate rows: ${costReview.possibleDuplicateRows}")
                    costReview.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Enter a valid positive conversion rate to run this review.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (summaries.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Available financial years", style = MaterialTheme.typography.titleMedium)
                    summaries.forEach { s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("FY ${s.year}-${(s.year + 1).toString().takeLast(2)} · ${s.trades} rows")
                            Text("₹${s.realizedPnlInr.num(0)}", color = pnlColor(s.realizedPnlInr))
                        }
                    }
                }
            }
        }
        if (yearTrades.isNotEmpty()) {
            Text("Trade history for FY $selectedYear-${((selectedYear.toIntOrNull() ?: 0) + 1).toString().takeLast(2)}", style = MaterialTheme.typography.titleMedium)
            yearTrades.takeLast(100).asReversed().forEach { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${t.symbol} · ${t.side}", style = MaterialTheme.typography.titleSmall)
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t.timestamp)))
                        Text("Qty ${t.quantity} · Price ${t.price} · Fee ${t.fee} ${t.feeAsset}")
                        Text("Market: ${t.market} · Source: ${t.source}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (yearTrades.size > 100) Text("Showing latest 100 of ${yearTrades.size} rows for performance.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
