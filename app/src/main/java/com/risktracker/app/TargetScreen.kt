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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil

private const val TARGET_PREFS = "profit_target_planner_v1"

@Composable
fun TargetScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE) }
    var startingBalance by remember { mutableStateOf(prefs.getString("starting_balance", "") ?: "") }
    var targetBalance by remember { mutableStateOf(prefs.getString("target_balance", "") ?: "") }
    var daysText by remember { mutableStateOf(prefs.getString("days", "30") ?: "30") }
    var dailyTarget by remember { mutableStateOf(prefs.getString("daily_target", "") ?: "") }

    LaunchedEffect(startingBalance, targetBalance, daysText, dailyTarget) {
        prefs.edit()
            .putString("starting_balance", startingBalance)
            .putString("target_balance", targetBalance)
            .putString("days", daysText)
            .putString("daily_target", dailyTarget)
            .apply()
    }

    val start = startingBalance.toDoubleOrNull()
    val goal = targetBalance.toDoubleOrNull()
    val days = daysText.toIntOrNull()
    val plannedDaily = dailyTarget.toDoubleOrNull()
    val validBalances = start != null && goal != null && start >= 0.0 && goal > start
    val profitNeeded = if (validBalances) goal!! - start!! else null
    val requiredDaily = if (profitNeeded != null && days != null && days > 0) profitNeeded / days else null
    val estimatedDays = if (profitNeeded != null && plannedDaily != null && plannedDaily > 0) ceil(profitNeeded / plannedDaily).toInt() else null
    val progress = if (start != null && goal != null && goal > 0.0) (start / goal).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Target planner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Plan a balance goal, see the average net profit required each day, and estimate a timeline from your own daily-profit assumption.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TargetNumberField("Starting balance (USD)", startingBalance, { startingBalance = it })
                TargetNumberField("Target balance (USD)", targetBalance, { targetBalance = it })
                TargetNumberField("Days to reach goal", daysText, { daysText = it }, integer = true)
                TargetNumberField("Planned net profit per day (USD)", dailyTarget, { dailyTarget = it })
                Text("Your entries are saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (validBalances) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Additional net profit needed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(money(profitNeeded!!), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Starting balance → target: \${money(start!!)} → \${money(goal!!)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Starting balance is \${String.format(Locale.US, "%.1f", progress * 100)}% of the target balance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Required pace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (requiredDaily != null) {
                        TargetMetric("Average net profit per day", money(requiredDaily))
                        TargetMetric("7-day equivalent", money(requiredDaily * 7))
                        TargetMetric("30-day equivalent", money(requiredDaily * 30))
                    } else {
                        Text("Enter a positive number of days to calculate the required daily average.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (plannedDaily != null && plannedDaily > 0 && estimatedDays != null) {
                        Spacer(Modifier.height(2.dp))
                        TargetMetric("Estimated days at your planned pace", estimatedDays.toString())
                        TargetMetric("Estimated weeks", String.format(Locale.US, "%.1f", estimatedDays / 7.0))
                    }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Enter your balances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Enter a starting balance of zero or more and a target balance greater than your starting balance to see the plan.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Risk reminder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("These figures are planning estimates, not guaranteed returns. Actual results can be negative and market conditions change. Keep a maximum daily-loss limit; never raise leverage or risk to force a deadline.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TargetNumberField(label: String, value: String, onValueChange: (String) -> Unit, integer: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal)
    )
}

@Composable
private fun TargetMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun money(value: Double): String = String.format(Locale.US, "\\$%,.2f", value)
