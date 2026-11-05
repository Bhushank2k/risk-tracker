package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Exchange-first journal. Position cycles and Spot activity are derived only from
 * automatically imported Binance executions; manual trade creation is intentionally absent.
 */
@Composable
fun TradeJournalScreen(exchangeFills: List<BinanceJournalFill>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { BinancePositionJournalSection(exchangeFills) }
    }
}
