package com.risktracker.app

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Entry dashboard for the India sole-proprietorship tax workspace.
 *
 * This screen deliberately reports source-data coverage only. It does not estimate
 * taxable income or tax due until the transaction classification and accounting
 * ledgers have been reviewed and implemented.
 */
@Composable
fun IndiaBusinessTaxCenterScreen(
    trades: List<HistoricalCryptoTrade>,
    onNavigate: (Int) -> Unit
) {
    val indiaCalendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"), Locale.US)
    val calendarYear = indiaCalendar.get(Calendar.YEAR)
    val financialYearStart = if (indiaCalendar.get(Calendar.MONTH) >= Calendar.APRIL) calendarYear else calendarYear - 1
    val spotTrades = trades.count { !it.market.contains("Futures", ignoreCase = true) }
    val futuresTrades = trades.size - spotTrades
    val missingPnl = trades.count { it.realizedPnl == null }
    val fyTrades = trades.filter { it.year == financialYearStart }
    val fySpot = fyTrades.count { !it.market.contains("Futures", ignoreCase = true) }
    val fyFutures = fyTrades.size - fySpot

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("India Business Tax Center", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Sole proprietorship · Binance · FY $financialYearStart–${(financialYearStart + 1).toString().takeLast(2)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Business accounting workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Full accounting is selected: trading ledgers, business income and expenses, owner capital and drawings, assets, liabilities, tax credits and CA review will be maintained separately.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "No tax payable estimate is shown until records are classified and reconciled. This prevents exchange P&L from being mistaken for taxable income.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text("Binance source-data coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageCard("Spot records", spotTrades.toString(), "FY: $fySpot", Modifier.weight(1f))
            CoverageCard("Futures records", futuresTrades.toString(), "FY: $fyFutures", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageCard("Current FY records", fyTrades.size.toString(), "All imported markets", Modifier.weight(1f))
            CoverageCard("Missing realized P&L", missingPnl.toString(), "Requires review", Modifier.weight(1f))
        }

        Text("Workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        WorkspaceAction(
            title = "Spot VDA tax ledger",
            description = "Review Binance Spot imports, acquisition cost, disposals and transaction evidence.",
            action = "Open tax ledger",
            onClick = { onNavigate(9) }
        )
        WorkspaceAction(
            title = "Futures accounting",
            description = "Open Binance sync and statements to review fills, realized P&L, fees and funding records.",
            action = "Open Binance records",
            onClick = { onNavigate(6) }
        )
        WorkspaceAction(
            title = "Exchange statements",
            description = "Review account activity and source statements for reconciliation.",
            action = "Open statements",
            onClick = { onNavigate(17) }
        )

        Text("Planned accounting modules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf(
            "Business profit & loss and chart of accounts",
            "Owner capital, drawings, assets and liabilities",
            "INR valuation evidence and transaction matching",
            "TDS credit reconciliation and GST records",
            "Tax-year rules, exports and CA approval history"
        ).forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("  $item", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Tax information is a working record for CA review, not a tax-return filing or professional tax opinion.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CoverageCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceAction(title: String, description: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}
