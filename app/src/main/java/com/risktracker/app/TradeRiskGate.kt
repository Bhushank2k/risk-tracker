package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class TradePlan(
    val id: String, val createdAt: String, val symbol: String, val side: String,
    val balance: Double, val riskPercent: Double, val entry: Double, val stopLoss: Double,
    val target: Double?, val estimatedFees: Double, val plannedQuantity: Double,
    val plannedRisk: Double, val riskReward: Double?, val status: String = "PLANNED"
)

data class TradeRiskSettings(
    val maxTradeRisk: Double = 5.0, val maxDailyLoss: Double = 20.0,
    val dailyPnl: Double = 0.0, val maxTradesPerDay: Int = 10, val tradesTakenToday: Int = 0,
    val dailyPnlKnown: Boolean = true
)
data class RiskCheck(val title: String, val passed: Boolean, val detail: String)
data class TradeRiskEvaluation(
    val valid: Boolean, val quantity: Double, val plannedRisk: Double,
    val riskReward: Double?, val checks: List<RiskCheck>, val errors: List<String>
) {
    val ready: Boolean get() = valid && errors.isEmpty() && checks.all { it.passed }
}

/** Pure local advisory evaluation. This does not place, block, or modify exchange orders. */
fun evaluateTradeRisk(
    side: String, balance: Double, riskPercent: Double, entry: Double, stopLoss: Double,
    target: Double?, estimatedFees: Double, settings: TradeRiskSettings
): TradeRiskEvaluation {
    val errors = mutableListOf<String>()
    val values = listOf(balance, riskPercent, entry, stopLoss, estimatedFees,
        settings.maxTradeRisk, settings.maxDailyLoss, settings.dailyPnl)
    if (values.any { !it.isFinite() } || (target != null && !target.isFinite())) errors += "All numeric values must be finite."
    if (side != "Long" && side != "Short") errors += "Choose Long or Short."
    if (balance <= 0.0) errors += "Account balance must be greater than zero."
    if (riskPercent <= 0.0 || riskPercent > 100.0) errors += "Risk per trade must be above 0% and at most 100%."
    if (entry <= 0.0 || stopLoss <= 0.0) errors += "Entry and stop-loss prices must be greater than zero."
    if (estimatedFees < 0.0) errors += "Estimated fees cannot be negative."
    if (settings.maxTradeRisk < 0.0 || settings.maxDailyLoss < 0.0) errors += "Risk limits cannot be negative."
    if (settings.maxTradesPerDay < 1 || settings.tradesTakenToday < 0) errors += "Trade counts are invalid."
    if (settings.tradesTakenToday >= settings.maxTradesPerDay) errors += "Daily trade-count limit has already been reached."
    if (entry > 0.0 && stopLoss > 0.0) {
        if (side == "Long" && stopLoss >= entry || side == "Short" && stopLoss <= entry)
            errors += side + " trades need the stop loss on the risk side of entry."
    }
    if (target != null) {
        if (target <= 0.0) errors += "Target must be greater than zero or left blank."
        else if (entry > 0.0 && (side == "Long" && target <= entry || side == "Short" && target >= entry))
            errors += side + " trades need the target beyond entry in the profit direction."
    }
    if (errors.isNotEmpty()) return TradeRiskEvaluation(false, 0.0, 0.0, null, emptyList(), errors)
    val distance = abs(entry - stopLoss)
    if (distance <= 0.0) return TradeRiskEvaluation(false, 0.0, 0.0, null, emptyList(), listOf("Stop-loss distance must be greater than zero."))
    val riskBudget = balance * riskPercent / 100.0
    if (!riskBudget.isFinite() || riskBudget <= 0.0) {
        return TradeRiskEvaluation(false, 0.0, 0.0, null, emptyList(),
            listOf("Calculated risk budget is outside the supported numeric range."))
    }
    if (estimatedFees >= riskBudget) {
        return TradeRiskEvaluation(false, 0.0, estimatedFees, null, emptyList(),
            listOf("Estimated fees meet or exceed the entire risk budget; no position size can be calculated within this fee assumption."))
    }
    val quantity = (riskBudget - estimatedFees) / distance
    val plannedRisk = quantity * distance + estimatedFees
    if (!quantity.isFinite() || !plannedRisk.isFinite() || quantity <= 0.0) {
        return TradeRiskEvaluation(false, 0.0, 0.0, null, emptyList(),
            listOf("Calculated position size is outside the supported numeric range."))
    }
    val reward = target?.let { abs(it - entry) * quantity }
    val rr = if (plannedRisk > 0.0 && reward != null) reward / plannedRisk else null
    val remainingDailyBudget = (settings.maxDailyLoss + minOf(settings.dailyPnl, 0.0)).coerceAtLeast(0.0)
    if ((reward != null && !reward.isFinite()) || (rr != null && !rr.isFinite()) ||
        !remainingDailyBudget.isFinite()) {
        return TradeRiskEvaluation(false, 0.0, 0.0, null, emptyList(),
            listOf("Calculated reward or daily risk values are outside the supported numeric range."))
    }
    val checks = listOf(
        RiskCheck("Per-trade risk", plannedRisk <= settings.maxTradeRisk,
            "Estimated loss at stop: " + "%.2f".format(Locale.US, plannedRisk) + "; limit " + "%.2f".format(Locale.US, settings.maxTradeRisk)),
        RiskCheck("Today's P&L data", settings.dailyPnlKnown,
            if (settings.dailyPnlKnown) "Today's reconciled P&L is available"
            else "No daily P&L found for today. Verify or record today's result before relying on the daily loss budget."),
        RiskCheck("Daily loss budget", plannedRisk <= remainingDailyBudget,
            "Remaining budget before this trade: " + "%.2f".format(Locale.US, remainingDailyBudget)),
        RiskCheck("Trade count", settings.tradesTakenToday < settings.maxTradesPerDay,
            settings.tradesTakenToday.toString() + " of " + settings.maxTradesPerDay + " trades used"),
        RiskCheck("Risk/reward", rr == null || rr >= 1.0,
            rr?.let { "Estimated R:R " + "%.2f".format(Locale.US, it) } ?: "No target set; reward is not evaluated")
    )
    return TradeRiskEvaluation(true, quantity, plannedRisk, rr, checks, emptyList())
}

class TradePlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("risk_tracker_trade_plans", Context.MODE_PRIVATE)
    fun exportJson(): String = prefs.getString("plans_v1", "[]") ?: "[]"
    fun importJson(raw: String) { val parsed = JSONArray(raw); prefs.edit().putString("plans_v1", parsed.toString()).apply() }
    fun load(): List<TradePlan> = runCatching {
        val array = JSONArray(prefs.getString("plans_v1", "[]") ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TradePlan(o.getString("id"), o.getString("createdAt"), o.getString("symbol"), o.getString("side"),
                o.getDouble("balance"), o.getDouble("riskPercent"), o.getDouble("entry"), o.getDouble("stopLoss"),
                if (o.isNull("target")) null else o.getDouble("target"), o.getDouble("estimatedFees"),
                o.getDouble("plannedQuantity"), o.getDouble("plannedRisk"),
                if (o.isNull("riskReward")) null else o.getDouble("riskReward"), o.optString("status", "PLANNED"))
        }
    }.getOrDefault(emptyList())
    fun save(plans: List<TradePlan>) {
        val array = JSONArray()
        plans.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("createdAt", p.createdAt).put("symbol", p.symbol)
                .put("side", p.side).put("balance", p.balance).put("riskPercent", p.riskPercent)
                .put("entry", p.entry).put("stopLoss", p.stopLoss).put("target", p.target ?: JSONObject.NULL)
                .put("estimatedFees", p.estimatedFees).put("plannedQuantity", p.plannedQuantity)
                .put("plannedRisk", p.plannedRisk).put("riskReward", p.riskReward ?: JSONObject.NULL).put("status", p.status))
        }
        prefs.edit().putString("plans_v1", array.toString()).apply()
    }
}

@Composable
fun TradeRiskGateScreen(settings: TradeRiskSettings) {
    val context = LocalContext.current
    val store = remember { TradePlanStore(context.applicationContext) }
    var plans by remember { mutableStateOf(store.load()) }
    var symbol by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("Long") }
    var balance by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var fees by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TradePlan?>(null) }
    val parsedTarget = target.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: Double.NaN }
    val evaluation = evaluateTradeRisk(side, balance.toDoubleOrNull() ?: Double.NaN, risk.toDoubleOrNull() ?: Double.NaN, entry.toDoubleOrNull() ?: Double.NaN, stop.toDoubleOrNull() ?: Double.NaN, parsedTarget, fees.toDoubleOrNull() ?: Double.NaN, settings)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RiskTrackerDesign.pagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Trade Risk Gate", style = MaterialTheme.typography.headlineMedium)
            Text("Advisory check, not approval.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Compare a proposed plan with configured limits. This tool does not approve, place, or modify exchange orders.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trade setup", style = MaterialTheme.typography.titleLarge)
            QuietFormField("Instrument", symbol, { symbol = it.uppercase(Locale.ROOT) }, helper = "For your saved reference only.")
            Text("Direction", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = side == "Long", onClick = { side = "Long" }, label = { Text("Long") })
                FilterChip(selected = side == "Short", onClick = { side = "Short" }, label = { Text("Short") })
            }
            QuietFormField("Account balance", balance, { balance = it }, unit = "USDT", helper = "Enter the balance used for this estimate.")
            QuietFormField("Risk per trade", risk, { risk = it }, unit = "%", helper = "Configured amount at risk; not a loss guarantee.")
            QuietFormField("Entry price", entry, { entry = it }, unit = "USDT")
            val invalidStop = entry.toDoubleOrNull() != null && stop.toDoubleOrNull() != null && ((side == "Long" && stop.toDouble() >= entry.toDouble()) || (side == "Short" && stop.toDouble() <= entry.toDouble()))
            QuietFormField("Stop price", stop, { stop = it }, unit = "USDT", error = if (invalidStop) "Stop must be below entry for a long and above entry for a short." else null)
            QuietFormField("Target price (optional)", target, { target = it }, unit = "USDT", helper = "Leave blank to mark reward/risk as Not evaluated.")
            QuietFormField("Estimated total fees", fees, { fees = it }, unit = "USDT", helper = "Manual estimate. Funding, slippage and contract multipliers are excluded.")
        }
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(RiskTrackerDesign.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Advisory result", style = MaterialTheme.typography.titleLarge)
                Text("A rule comparison, not a trade approval or safety guarantee.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (evaluation.errors.isNotEmpty()) {
                    SeverityChip("INPUT REVIEW REQUIRED", RiskSeverity.CAUTION)
                    evaluation.errors.forEach { Text("• " + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                } else {
                    KpiValue("Indicative size · estimate", "%.6f".format(Locale.US, evaluation.quantity), supportingText = "Units; contract multiplier not applied.")
                    KpiValue("Estimated risk at stop", "%.2f USDT".format(Locale.US, evaluation.plannedRisk), supportingText = "Includes the entered fee estimate.")
                    Text("Rule checks", style = MaterialTheme.typography.titleMedium)
                    evaluation.checks.forEach { check ->
                        val notEvaluated = check.title == "Risk/reward" && target.isBlank()
                        val review = !check.passed && !notEvaluated
                        val status = if (notEvaluated) "Not evaluated" else if (review) "Review required" else "Passed"
                        val severity = if (notEvaluated) RiskSeverity.NEUTRAL else if (review) RiskSeverity.CAUTION else RiskSeverity.POSITIVE
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(check.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                SeverityChip(status, severity)
                            }
                            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SeverityChip(if (evaluation.ready) "Configured checks passed" else "Review required", RiskSeverity.INFORMATION.takeIf { evaluation.ready } ?: RiskSeverity.CAUTION)
                    val reasons = evaluation.checks.filterNot { it.passed }.joinToString { it.title }.ifBlank { "No configured rule breaches detected; unmodelled market and execution risks remain." }
                    Text("Reasons: " + reasons, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    if (evaluation.valid && symbol.isNotBlank()) {
                        val plan = TradePlan(UUID.randomUUID().toString(), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()), symbol.trim().uppercase(Locale.ROOT), side, balance.toDouble(), risk.toDouble(), entry.toDouble(), stop.toDouble(), parsedTarget, fees.toDouble(), evaluation.quantity, evaluation.plannedRisk, evaluation.riskReward, status = if (evaluation.ready) "CHECKS_PASSED" else "REVIEW_REQUIRED")
                        plans = listOf(plan) + plans
                        store.save(plans)
                    }
                }, enabled = evaluation.valid && symbol.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Save plan") }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saved plans", style = MaterialTheme.typography.titleLarge)
            if (plans.isEmpty()) Text("No saved plans. Enter a setup above and save it to retain its assumptions and advisory result.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            plans.forEach { plan ->
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(RiskTrackerDesign.cardPadding), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(plan.symbol + " · " + plan.side, style = MaterialTheme.typography.titleMedium)
                            SeverityChip(if (plan.status == "CHECKS_PASSED") "Passed when saved" else "Review required", if (plan.status == "CHECKS_PASSED") RiskSeverity.INFORMATION else RiskSeverity.CAUTION)
                        }
                        Text(plan.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Balance " + plan.balance + " USDT · Risk " + plan.riskPercent + "% · Entry " + plan.entry + " · Stop " + plan.stopLoss, style = MaterialTheme.typography.bodySmall)
                        Text("Target " + (plan.target?.toString() ?: "Not set") + " · Fee assumption " + plan.estimatedFees + " USDT · Size " + "%.6f".format(Locale.US, plan.plannedQuantity), style = MaterialTheme.typography.bodySmall)
                        Text("Estimate excludes funding, slippage and contract multipliers. Saved status is historical, not a current check.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { pendingDelete = plan }) { Text("Delete plan", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    pendingDelete?.let { plan ->
        QuietConfirmationDialog(title = "Delete saved plan?", affectedData = "the saved " + plan.symbol + " " + plan.side + " plan created " + plan.createdAt, confirmLabel = "Delete plan", destructive = true, onConfirm = { plans = plans.filterNot { it.id == plan.id }; store.save(plans); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }
}