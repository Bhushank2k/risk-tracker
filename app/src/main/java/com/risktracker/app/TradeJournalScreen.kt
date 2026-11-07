package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Combined exchange-first journal and performance view. Analytics are presented
 * as a section of Journal rather than a separate navigation destination.
 */
@Composable
fun TradeJournalScreen(
    exchangeFills: List<BinanceJournalFill>,
    syncStatus: BinanceJournalSyncStatus,
    dailyEntries: List<PnlEntry>,
    manualTrades: List<TradeEntry>,
    importedDailyPnl: List<BinanceDailyResult>,
    initialSection: String = "History"
) {
    var section by remember { mutableStateOf(initialSection) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = section == "History", onClick = { section = "History" }, label = { Text("Trade history") })
            FilterChip(selected = section == "Analytics", onClick = { section = "Analytics" }, label = { Text("Performance") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section == "Analytics") {
                TradingAnalyticsScreen(exchangeFills, syncStatus, dailyEntries, manualTrades, importedDailyPnl)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { BinancePositionJournalSection(exchangeFills) }
                }
            }
        }
    }
}
