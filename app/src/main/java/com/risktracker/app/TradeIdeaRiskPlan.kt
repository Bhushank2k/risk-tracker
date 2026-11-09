package com.risktracker.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/** User-configurable, advisory-only risk settings. No exchange orders are submitted. */
internal data class EntryRiskSettings(
    val accountEquity: Double = 0.0,
    val riskPercent: Double = 0.5,
    val maxLeverage: Int = 3,
    val maxRiskPerTradePercent: Double = 2.0,
    val maxCombinedOpenRiskPercent: Double = 1.5,
    val minRewardRisk: Double = 1.5,
    val maxActivePositions: Int = 2,
    val dailyLossLimitPercent: Double = 1.5,
    val maxConsecutiveLosses: Int = 2,
    val tp1Percent: Int = 40,
    val tp2Percent: Int = 30,
    val tp3Percent: Int = 30
)

internal object EntryRiskSettingsStore {
    private const val PREFS = "entry_risk_plan_v1"

    fun load(context: Context): EntryRiskSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EntryRiskSettings(
            accountEquity = p.getString("equity", "0")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            riskPercent = p.getFloat("risk_percent", 0.5f).toDouble().coerceIn(0.5, 2.0),
            maxLeverage = p.getInt("max_leverage", 3).coerceIn(1, 3),
            maxRiskPerTradePercent = 2.0,
            maxCombinedOpenRiskPercent = 1.5,
            minRewardRisk = 1.5,
            maxActivePositions = 2,
            dailyLossLimitPercent = 1.5,
            maxConsecutiveLosses = 2,
            tp1Percent = 40, tp2Percent = 30, tp3Percent = 30
        )
    }

    fun save(context: Context, settings: EntryRiskSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("equity", settings.accountEquity.toString())
            .putFloat("risk_percent", settings.riskPercent.toFloat())
            .putInt("max_leverage", settings.maxLeverage)
            .apply()
    }
}

internal data class EntryRiskCalculation(
    val riskAmount: Double,
    val stopDistancePercent: Double,
    val positionNotional: Double,
    val quantity: Double,
    val estimatedInitialMargin: Double,
    val target1Profit: Double,
    val target2Profit: Double
)

internal fun calculateEntryRiskPlan(idea: TradeIdea, settings: EntryRiskSettings): EntryRiskCalculation? {
    val entry = idea.entry
    val stopDistance = abs(entry - idea.stop)
    if (idea.side !in setOf("LONG", "SHORT") || settings.accountEquity <= 0.0 || !settings.accountEquity.isFinite() ||
        entry <= 0.0 || !entry.isFinite() || stopDistance <= 0.0 || !stopDistance.isFinite()) return null
    if (idea.side == "LONG" && (idea.stop >= entry || idea.target1 <= entry || idea.target2 <= idea.target1)) return null
    if (idea.side == "SHORT" && (idea.stop <= entry || idea.target1 >= entry || idea.target2 >= idea.target1)) return null
    val riskAmount = settings.accountEquity * settings.riskPercent / 100.0
    val stopDistancePercent = stopDistance / entry * 100.0
    if (stopDistancePercent <= 0.0) return null
    val notional = riskAmount / (stopDistancePercent / 100.0)
    if (!notional.isFinite() || notional <= 0.0) return null
    val reward1R = abs(idea.target1 - entry) / stopDistance
    val reward2R = abs(idea.target2 - entry) / stopDistance
    return EntryRiskCalculation(
        riskAmount = riskAmount,
        stopDistancePercent = stopDistancePercent,
        positionNotional = notional,
        quantity = notional / entry,
        estimatedInitialMargin = notional / settings.maxLeverage.coerceAtLeast(1),
        target1Profit = riskAmount * reward1R,
        target2Profit = riskAmount * reward2R
    )
}

@Composable
internal fun EntryRiskPlanSettingsCard(
    settings: EntryRiskSettings,
    onSettingsChanged: (EntryRiskSettings) -> Unit
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    )) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Entry & Risk Plan", style = MaterialTheme.typography.titleMedium)
            Text("Risk profile · ${"%.1f".format(Locale.US, settings.riskPercent)}% account risk · maximum 3x leverage. Position size is calculated from stop distance; leverage only estimates margin.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = if (settings.accountEquity > 0.0) settings.accountEquity.toString() else "",
                onValueChange = { raw ->
                    val parsed = raw.replace(",", "").toDoubleOrNull()
                    if (raw.isBlank()) onSettingsChanged(settings.copy(accountEquity = 0.0))
                    else if (parsed != null && parsed.isFinite() && parsed >= 0.0) onSettingsChanged(settings.copy(accountEquity = parsed))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Account equity (USDT)") },
                placeholder = { Text("Enter your equity to calculate size") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Text("Risk per trade: ${"%.1f".format(Locale.US, settings.riskPercent)}% of equity",
                style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.riskPercent.toFloat(),
                onValueChange = { onSettingsChanged(settings.copy(riskPercent = it.toDouble())) },
                valueRange = 0.5f..2.0f,
                steps = 2
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Leverage cap", style = MaterialTheme.typography.bodyMedium)
                Text("${settings.maxLeverage}x", style = MaterialTheme.typography.bodyMedium)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (1..3).forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = settings.maxLeverage == value,
                        onClick = { onSettingsChanged(settings.copy(maxLeverage = value)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) { Text("${value}x") }
                }
            }
            HorizontalDivider()
            RiskSettingRow("Maximum risk per trade", "2%")
            RiskSettingRow("Maximum combined open risk", "1.5%")
            RiskSettingRow("Minimum net reward / risk", "1:1.5")
            RiskSettingRow("Maximum active positions", "2")
            RiskSettingRow("Daily loss limit", "1.5%")
            RiskSettingRow("Loss-streak lockout", "2 consecutive losses")
            RiskSettingRow("Margin mode", "Isolated")
            RiskSettingRow("Profit-taking", "40% / 30% / 30%")
            Text("These are planning limits, not guarantees. Estimates exclude fees, funding, slippage, maintenance margin and liquidation effects. No orders are placed.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RiskSettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun EntryRiskPlanDetails(idea: TradeIdea, settings: EntryRiskSettings) {
    val plan = calculateEntryRiskPlan(idea, settings)
    if (plan == null) {
        Text("Enter account equity above to calculate position quantity, risk amount and estimated margin.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    RiskPlanMetric("Account risk at stop", formatRiskUsd(plan.riskAmount) + " (${"%.2f".format(Locale.US, settings.riskPercent)}%)")
    RiskPlanMetric("Stop distance", "${"%.2f".format(Locale.US, plan.stopDistancePercent)}%")
    RiskPlanMetric("Position notional", formatRiskUsd(plan.positionNotional))
    RiskPlanMetric("Estimated quantity", "${"%.6f".format(Locale.US, plan.quantity)}")
    RiskPlanMetric("Estimated initial margin", formatRiskUsd(plan.estimatedInitialMargin) + " at ${settings.maxLeverage}x")
    RiskPlanMetric("Target 1 gross P&L", "+" + formatRiskUsd(plan.target1Profit))
    RiskPlanMetric("Target 2 gross P&L", "+" + formatRiskUsd(plan.target2Profit))
}

@Composable
private fun RiskPlanMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

private fun formatRiskUsd(value: Double): String = String.format(Locale.US, "\$%,.2f", value)
