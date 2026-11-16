package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
 * One scanner workspace. The unified feed is the default and contains the six new
 * strategy engines plus the existing Trade Ideas engine. Specialist SMC views remain
 * available as tabs so existing scanner functionality is not discarded.
 */
@Composable
fun CombinedSMCIdeasScreen(onOpenTradeIdeas: () -> Unit = {}) {
    var selectedView by remember { mutableStateOf("Unified Scanner") }
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Unified Trade Scanner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("One market scan and ranked trade-idea feed, with specialist SMC views retained below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Unified Scanner", "SMC Patterns", "Advanced SMC").forEach { view ->
                FilterChip(
                    selected = selectedView == view,
                    onClick = { selectedView = view },
                    label = { Text(view) }
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (selectedView) {
                "SMC Patterns" -> SMCTradeScreen()
                "Advanced SMC" -> AdvancedSMCSignalsScreen(onOpenTradeIdeas = onOpenTradeIdeas)
                else -> DailyTradeIdeasScreen()
            }
        }
    }
}
