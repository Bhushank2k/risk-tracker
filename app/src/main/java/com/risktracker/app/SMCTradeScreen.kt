package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dedicated SMC workspace. It intentionally does not relabel general scanner
 * signals as SMC; candidates require a separate SMC candle-structure engine.
 */
@Composable
fun SMCTradeScreen() {
    var horizon by remember { mutableStateOf("All") }
    var expanded by remember { mutableStateOf(false) }
    val horizons = listOf("All", "Scalp", "Intraday", "Swing")
    val horizonLabel = if (horizon == "All") "" else "$horizon "
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.ShowChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Smart Money Concepts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("A dedicated SMC-only workspace for crypto futures setups. Prefer no trade over a weak or incomplete setup.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("SMC signals only · Maximum 5 shortlisted setups", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Trading horizon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            horizons.forEach { item ->
                FilterChip(selected = horizon == item, onClick = { horizon = item }, label = { Text(item) })
            }
        }
        Text("SMC confirmation checklist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf(
            "Higher-timeframe structure — BOS / CHoCH",
            "Liquidity sweep — prior highs, lows or equal levels",
            "Displacement — decisive move after the sweep",
            "Order block and fair value gap — valid, unmitigated zone",
            "Premium / discount — entry location in dealing range",
            "Risk validation — invalidation stop and minimum 1:2 R:R"
        ).forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${index + 1}", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                    }
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SMC trade shortlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("No SMC-validated ${horizonLabel}setups are available yet.", style = MaterialTheme.typography.bodyMedium)
                Text("This screen is separate from Daily Trade Ideas. It will not reuse non-SMC scores or display unverified setups as SMC trades.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide scanner requirements" else "View scanner requirements")
                }
                if (expanded) {
                    HorizontalDivider()
                    Text("Required market inputs", fontWeight = FontWeight.SemiBold)
                    Text("Exchange OHLCV candles across selected timeframes, swing-point and dealing-range detection, liquidity-level tracking, displacement validation, order-block / FVG lifecycle, and explicit entry / stop / target calculations.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("SMC analysis is decision support, not a guarantee. Confirm levels and position risk before placing any futures trade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
