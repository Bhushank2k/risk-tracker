package com.risktracker.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.ceil

@Composable
fun TargetScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("profit_target_planner_v1", Context.MODE_PRIVATE) }
    var startingText by remember { mutableStateOf(prefs.getString("starting_balance", "1000") ?: "1000") }
    var goalText by remember { mutableStateOf(prefs.getString("goal_balance", "1500") ?: "1500") }
    var daysText by remember { mutableStateOf(prefs.getString("target_days", "30") ?: "30") }
    var dailyText by remember { mutableStateOf(prefs.getString("daily_target", "25") ?: "25") }

    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    val starting = startingText.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
    val goal = goalText.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
    val days = daysText.toIntOrNull()?.takeIf { it > 0 }
    val daily = dailyText.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    val remaining = if (starting != null && goal != null) (goal - starting).coerceAtLeast(0.0) else null
    val dailyRequired = if (remaining != null && days != null) remaining / days else null
    val projectedDays = if (remaining != null && daily != null) ceil(remaining / daily).toInt() else null
    val progress = if (starting != null && goal != null && goal > 0) (starting / goal).coerceIn(0.0, 1.0).toFloat() else 0f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GOAL PLANNER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Your profit target", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Set a balance goal and timeframe. Risk Tracker calculates the average net profit needed per day and how long a chosen daily target could take.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TARGET BALANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(goal?.usd() ?: "Enter a valid amount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (remaining != null) Text(
                    if (remaining == 0.0) "Goal reached based on the starting balance" else "Additional net profit needed: ${remaining.usd()}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))
                Text("Starting balance toward goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1 · Define your goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = startingText, onValueChange = { startingText = it; save("starting_balance", it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Starting / current balance (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = goalText, onValueChange = { goalText = it; save("goal_balance", it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Target balance (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = daysText, onValueChange = { daysText = it; save("target_days", it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Days to reach the goal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2 · Your required daily target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Average net profit needed each calendar day to reach your balance goal in the selected timeframe.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dailyRequired?.usd() ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    color = if (dailyRequired != null && dailyRequired > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (dailyRequired != null && days != null) {
                    Text("Weekly equivalent: ${(dailyRequired * 7).usd()} · 30-day equivalent: ${(dailyRequired * 30).usd()}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("This is a straight-line average, not a guaranteed return. Trading days may differ from calendar days.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3 · Calculate days from a daily target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = dailyText, onValueChange = { dailyText = it; save("daily_target", it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Planned net profit per day (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    when {
                        remaining == null -> "Enter valid starting and target balances."
                        remaining == 0.0 -> "Your target is already reached based on the starting balance."
                        projectedDays == null -> "Enter a positive daily target to estimate the number of days."
                        else -> "Estimated time: $projectedDays calendar day${if (projectedDays == 1) "" else "s"} at ${daily!!.usd()} net profit per day."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (remaining != null && daily != null && remaining > 0) {
                    Text("Weekly pace: ${(daily * 7).usd()} · Monthly pace: ${(daily * 30).usd()}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Risk reminder: profit targets are planning estimates, not promises. Results can be negative; set a maximum daily loss and avoid increasing leverage just to meet a deadline.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}
