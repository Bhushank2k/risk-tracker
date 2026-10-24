package com.risktracker.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.util.Random

data class DrawdownScenario(val lossStreak: Int, val startingEquity: Double, val endingEquity: Double, val drawdownPercent: Double, val recoveryPercent: Double)
data class ScenarioSummary(val valid: Boolean, val scenarios: List<DrawdownScenario>, val error: String? = null)

fun calculateLossScenarios(startingEquity: Double, riskPercent: Double, streaks: List<Int> = listOf(3, 5, 8, 10)): ScenarioSummary {
    if (!startingEquity.isFinite() || startingEquity <= 0.0) return ScenarioSummary(false, emptyList(), "Starting equity must be finite and greater than zero.")
    if (!riskPercent.isFinite() || riskPercent <= 0.0 || riskPercent > 100.0) return ScenarioSummary(false, emptyList(), "Risk per trade must be above 0% and at most 100%.")
    if (streaks.any { it < 0 || it > 1000 }) return ScenarioSummary(false, emptyList(), "Loss streaks must be between 0 and 1000.")
    val rows = streaks.distinct().sorted().map { count ->
        val ending = startingEquity * Math.pow(1.0 - riskPercent / 100.0, count.toDouble())
        val drawdown = (startingEquity - ending) / startingEquity * 100.0
        val recovery = if (ending > 0.0) (startingEquity - ending) / ending * 100.0 else Double.POSITIVE_INFINITY
        DrawdownScenario(count, startingEquity, ending, drawdown, recovery)
    }
    return ScenarioSummary(true, rows)
}

/** Seeded bootstrap of historical R-multiples. Results are illustrative, not a forecast. */
fun simulateHistoricalOutcomes(startingEquity: Double, riskPercent: Double, rMultiples: List<Double>, trades: Int, simulations: Int, seed: Long = 1L): List<Double> {
    require(startingEquity.isFinite() && startingEquity > 0.0)
    require(riskPercent.isFinite() && riskPercent > 0.0 && riskPercent <= 100.0)
    require(rMultiples.isNotEmpty() && rMultiples.all { it.isFinite() })
    require(trades in 1..10000 && simulations in 1..10000)
    val random = Random(seed)
    return List(simulations) {
        var equity = startingEquity
        repeat(trades) {
            val r = rMultiples[random.nextInt(rMultiples.size)]
            equity *= (1.0 + riskPercent / 100.0 * r).coerceAtLeast(0.0)
        }
        equity
    }.sorted()
}

@Composable
fun RiskScenarioLabScreen() {
    var equity by remember { mutableStateOf("1000") }
    var risk by remember { mutableStateOf("1") }
    val summary = calculateLossScenarios(equity.toDoubleOrNull() ?: Double.NaN, risk.toDoubleOrNull() ?: Double.NaN)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Risk Scenario Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Stress-test a losing streak before trading. These deterministic scenarios assume each loss equals the selected fraction of current equity.")
        OutlinedTextField(equity, { equity = it }, label = { Text("Starting equity (USD)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(risk, { risk = it }, label = { Text("Risk lost per trade (%)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        summary.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (summary.valid) summary.scenarios.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.lossStreak.toString() + " consecutive losses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Ending equity: " + s.endingEquity.usd())
                    Text("Drawdown: " + s.drawdownPercent.pct(2))
                    Text("Gain needed to recover: " + (if (s.recoveryPercent.isFinite()) s.recoveryPercent.pct(2) else "Not recoverable"))
                }
            }
        }
        Text("The model excludes fees, funding, slippage, changing margin requirements and liquidation. Monte Carlo results, when used, depend on historical samples and are not forecasts.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
