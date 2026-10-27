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
            listOf("Estimated fees meet or exceed the entire risk budget; no safe position size is available."))
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
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var side by remember { mutableStateOf("Long") }
    var balance by remember { mutableStateOf("1000") }
    var risk by remember { mutableStateOf("1") }
    var entry by remember { mutableStateOf("100") }
    var stop by remember { mutableStateOf("98") }
    var target by remember { mutableStateOf("104") }
    var fees by remember { mutableStateOf("0.5") }
    val parsedTarget = target.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: Double.NaN }
    val evaluation = evaluateTradeRisk(side, balance.toDoubleOrNull() ?: Double.NaN,
        risk.toDoubleOrNull() ?: Double.NaN, entry.toDoubleOrNull() ?: Double.NaN,
        stop.toDoubleOrNull() ?: Double.NaN, parsedTarget, fees.toDoubleOrNull() ?: Double.NaN, settings)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trade Risk Gate", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Check a proposed trade against your rules before entry. This screen does not place orders.")
        OutlinedTextField(symbol, { symbol = it.uppercase(Locale.ROOT) }, label = { Text("Symbol") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "Long", onClick = { side = "Long" }, label = { Text("Long") })
            FilterChip(selected = side == "Short", onClick = { side = "Short" }, label = { Text("Short") })
        }
        RiskGateField("Account equity (USD)", balance) { balance = it }
        RiskGateField("Risk per trade (%)", risk) { risk = it }
        RiskGateField("Entry price", entry) { entry = it }
        RiskGateField("Stop-loss price", stop) { stop = it }
        RiskGateField("Take-profit price (optional)", target) { target = it }
        RiskGateField("Estimated total fees", fees) { fees = it }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pre-trade assessment", style = MaterialTheme.typography.titleMedium)
                evaluation.errors.forEach { Text("• " + it, color = MaterialTheme.colorScheme.error) }
                if (evaluation.errors.isEmpty()) {
                    Text("Suggested quantity: " + "%.6f".format(Locale.US, evaluation.quantity))
                    Text("Estimated risk at stop: " + "%.2f".format(Locale.US, evaluation.plannedRisk))
                    Text("Risk/reward: " + (evaluation.riskReward?.let { "%.2f".format(Locale.US, it) } ?: "Not set"))
                    evaluation.checks.forEach { c ->
                        Text((if (c.passed) "✓ " else "⚠ ") + c.title + ": " + c.detail,
                            color = if (c.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Text(if (evaluation.ready) "All configured checks passed" else "Review failed checks before proceeding",
                        fontWeight = FontWeight.Bold, color = if (evaluation.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Button(onClick = {
                    val plan = TradePlan(UUID.randomUUID().toString(),
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                        symbol.trim().uppercase(Locale.ROOT), side, balance.toDouble(), risk.toDouble(),
                        entry.toDouble(), stop.toDouble(), parsedTarget, fees.toDouble(),
                        evaluation.quantity, evaluation.plannedRisk, evaluation.riskReward,
                        status = if (evaluation.ready) "CHECKS_PASSED" else "REVIEW_REQUIRED")
                    plans = listOf(plan) + plans
                    store.save(plans)
                }, enabled = evaluation.valid && symbol.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save trade plan") }
            }
        }
        Text("Saved plans", style = MaterialTheme.typography.titleMedium)
        if (plans.isEmpty()) Text("No saved plans yet.", style = MaterialTheme.typography.bodySmall)
        plans.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.symbol + " · " + p.side, fontWeight = FontWeight.Bold)
                    Text(p.createdAt + " · Risk " + "%.2f".format(Locale.US, p.plannedRisk) + " · Qty " + "%.6f".format(Locale.US, p.plannedQuantity))
                    Text(
                        when (p.status) {
                            "CHECKS_PASSED" -> "Status: all configured checks passed when saved"
                            "REVIEW_REQUIRED" -> "Status: warnings present when saved — review before trading"
                            else -> "Status: " + p.status.replace('_', ' ').lowercase(Locale.US)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.status == "CHECKS_PASSED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("Entry " + p.entry + " · Stop " + p.stopLoss + " · Target " + (p.target?.toString() ?: "—"))
                    OutlinedButton(onClick = { plans = plans.filterNot { it.id == p.id }; store.save(plans) }) { Text("Delete") }
                }
            }
        }
        Text("Estimates may exclude contract multipliers, slippage, funding, exchange-specific fees and liquidation rules.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RiskGateField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
