package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Single destination for conventional SMC setups and advanced SMC pattern ideas.
 * The two existing engines and their persisted/background scan state remain independent,
 * while users access both from one combined Trade Ideas screen.
 */
@Composable
fun CombinedSMCIdeasScreen(onOpenTradeIdeas: () -> Unit = {}) {
    var selectedSource by remember { mutableStateOf("All ideas") }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "SMC Trade Ideas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "One place for core SMC setups and advanced SMC signals. Choose a source to review its ranked ideas.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All ideas", "SMC Trade", "Advanced Signals").forEach { source ->
                FilterChip(
                    selected = selectedSource == source,
                    onClick = { selectedSource = source },
                    label = { Text(source) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSource) {
                "SMC Trade" -> SMCTradeScreen()
                "Advanced Signals" -> AdvancedSMCSignalsScreen(onOpenTradeIdeas = onOpenTradeIdeas)
                else -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Core SMC setups",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(modifier = Modifier.weight(1f)) { SMCTradeScreen() }
                    Text(
                        "Advanced SMC signals",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        AdvancedSMCSignalsScreen(onOpenTradeIdeas = onOpenTradeIdeas)
                    }
                }
            }
        }
    }
}
