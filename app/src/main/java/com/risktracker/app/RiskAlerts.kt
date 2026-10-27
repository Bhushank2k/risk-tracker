package com.risktracker.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RiskAlert(val key: String, val title: String, val detail: String)

object RiskAlertEngine {
    fun evaluate(
        dailyPnl: Double?, dailyLossLimit: Double, dailyLossEnabled: Boolean,
        drawdownPercent: Double?, drawdownLimitPercent: Double, drawdownEnabled: Boolean,
        hasBinanceCredentials: Boolean, lastSyncSuccess: Long, now: Long,
        staleAfterMillis: Long, staleSyncEnabled: Boolean,
        consecutiveLosses: Int = 0, consecutiveLossLimit: Int = 3, consecutiveLossEnabled: Boolean = false
    ): List<RiskAlert> = buildList {
        if (dailyLossEnabled && dailyPnl != null && dailyPnl.isFinite() &&
            dailyLossLimit.isFinite() && dailyLossLimit > 0.0 && dailyPnl <= -dailyLossLimit) {
            add(RiskAlert("daily-loss", "Daily loss limit reached",
                "Recorded daily P&L is " + "%.2f".format(Locale.US, dailyPnl) +
                    "; configured loss limit is " + "%.2f".format(Locale.US, dailyLossLimit) + "."))
        }
        if (drawdownEnabled && drawdownPercent != null && drawdownPercent.isFinite() &&
            drawdownLimitPercent.isFinite() && drawdownLimitPercent > 0.0 &&
            drawdownPercent >= drawdownLimitPercent) {
            add(RiskAlert("drawdown", "Drawdown threshold reached",
                "Recorded peak-to-current drawdown is " + "%.2f".format(Locale.US, drawdownPercent) +
                    "%, above your " + "%.2f".format(Locale.US, drawdownLimitPercent) + "% threshold."))
        }
        if (consecutiveLossEnabled && consecutiveLossLimit > 0 && consecutiveLosses >= consecutiveLossLimit) {
            add(RiskAlert("loss-streak", "Consecutive-loss limit reached",
                "The latest recorded journal results contain " + consecutiveLosses +
                    " consecutive losses; your alert threshold is " + consecutiveLossLimit + "."))
        }
        if (staleSyncEnabled && hasBinanceCredentials &&
            (lastSyncSuccess <= 0L || now - lastSyncSuccess >= staleAfterMillis)) {
            val detail = if (lastSyncSuccess <= 0L) {
                "Binance credentials are saved, but no successful execution-history sync is recorded."
            } else {
                "The last successful execution-history sync was " +
                    ((now - lastSyncSuccess).coerceAtLeast(0L) / 3_600_000L) + " hours ago."
            }
            add(RiskAlert("stale-sync", "Binance data may be stale", detail))
        }
    }
}

@Composable
fun RiskAlertsScreen(entries: List<PnlEntry>, trades: List<TradeEntry>, dailyPnl: Double?, maxDailyLoss: Double) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("risk_alert_settings_v1", Context.MODE_PRIVATE) }
    var dailyEnabled by remember { mutableStateOf(prefs.getBoolean("daily_enabled", true)) }
    var drawdownEnabled by remember { mutableStateOf(prefs.getBoolean("drawdown_enabled", true)) }
    var staleEnabled by remember { mutableStateOf(prefs.getBoolean("stale_enabled", true)) }
    var streakEnabled by remember { mutableStateOf(prefs.getBoolean("streak_enabled", true)) }
    var streakLimit by remember { mutableStateOf(prefs.getInt("streak_limit", 3).toString()) }
    var drawdownThreshold by remember { mutableStateOf(prefs.getFloat("drawdown_threshold", 10f).toString()) }
    var staleHours by remember { mutableStateOf(prefs.getInt("stale_hours", 24).toString()) }
    var savedMessage by remember { mutableStateOf("") }

    val ordered = entries.mapNotNull { entry ->
        parseRiskDate(entry.date)?.let { it.time to entry.closing }
    }.sortedBy { it.first }
    var peak = Double.NaN
    var maxDrawdownPct = 0.0
    ordered.forEach { (_, balance) ->
        if (balance.isFinite() && balance > 0.0) {
            peak = if (peak.isNaN()) balance else maxOf(peak, balance)
            if (peak > 0.0) maxDrawdownPct = maxOf(maxDrawdownPct, ((peak - balance) / peak * 100.0).coerceAtLeast(0.0))
        }
    }
    val recentTradeResults = trades.mapNotNull { trade ->
        parseRiskDate(trade.date)?.let { it.time to trade.pnl }
    }.sortedByDescending { it.first }
    val consecutiveLosses = recentTradeResults.takeWhile { it.second < 0.0 }.size
    val status = remember(context) { BinanceAutoJournalStore.syncStatus(context) }
    val hasCredentials = remember(context) { BinanceCredentialStore(context).read() != null }
    val threshold = drawdownThreshold.toDoubleOrNull()
    val staleWindow = staleHours.toLongOrNull()?.coerceIn(1L, 720L) ?: 24L
    val alerts = RiskAlertEngine.evaluate(
        dailyPnl, maxDailyLoss, dailyEnabled,
        if (ordered.isEmpty()) null else maxDrawdownPct, threshold ?: 10.0, drawdownEnabled,
        hasCredentials, status.lastSuccess, System.currentTimeMillis(), staleWindow * 3_600_000L, staleEnabled,
        consecutiveLosses, streakLimit.toIntOrNull() ?: 3, streakEnabled
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Risk alerts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("In-app checks based on data saved on this device. These are advisory, not exchange-enforced controls, and they do not place or block orders.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Alert rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily loss limit", fontWeight = FontWeight.Medium)
                        Text("Uses reconciled P&L when available. Configured limit: " + maxDailyLoss.usd(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = dailyEnabled, onCheckedChange = { dailyEnabled = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Recorded drawdown", fontWeight = FontWeight.Medium)
                        Text("Compares recorded closing balances with the previous peak.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = drawdownEnabled, onCheckedChange = { drawdownEnabled = it })
                }
                OutlinedTextField(drawdownThreshold, { drawdownThreshold = it }, label = { Text("Drawdown threshold (%)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Stale Binance sync", fontWeight = FontWeight.Medium)
                        Text("Warns when connected execution-history data has not synced successfully within the selected window.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = staleEnabled, onCheckedChange = { staleEnabled = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Consecutive losses", fontWeight = FontWeight.Medium)
                        Text("Checks the latest dated closed-trade results in the journal.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = streakEnabled, onCheckedChange = { streakEnabled = it })
                }
                OutlinedTextField(streakLimit, { streakLimit = it.filter(Char::isDigit).take(2) },
                    label = { Text("Consecutive losses threshold (1–20)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(staleHours, { staleHours = it.filter(Char::isDigit).take(3) },
                    label = { Text("Stale after (hours, 1–720)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val d = drawdownThreshold.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    val h = staleHours.toLongOrNull()?.takeIf { it in 1L..720L }
                    val s = streakLimit.toIntOrNull()?.takeIf { it in 1..20 }
                    if (d != null && h != null && s != null) {
                        prefs.edit().putBoolean("daily_enabled", dailyEnabled)
                            .putBoolean("drawdown_enabled", drawdownEnabled).putBoolean("stale_enabled", staleEnabled)
                            .putBoolean("streak_enabled", streakEnabled)
                            .putFloat("drawdown_threshold", d.toFloat()).putInt("stale_hours", h.toInt())
                            .putInt("streak_limit", s).apply()
                        drawdownThreshold = d.toString()
                        staleHours = h.toString()
                        streakLimit = s.toString()
                        savedMessage = "Alert settings saved."
                    } else savedMessage = "Use a positive drawdown percentage, a stale window of 1–720 hours, and a loss-streak threshold of 1–20."
                }, modifier = Modifier.fillMaxWidth()) { Text("Save alert settings") }
                if (savedMessage.isNotBlank()) Text(savedMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (alerts.isEmpty()) Text("No enabled alert is currently triggered by the available data.", color = MaterialTheme.colorScheme.primary)
                else alerts.forEach { alert ->
                    Text(alert.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(alert.detail, style = MaterialTheme.typography.bodySmall)
                }
                if (dailyPnl == null) Text("Today's P&L is unknown; missing data is not treated as zero.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Recorded peak drawdown: " + if (ordered.isEmpty()) "Unavailable" else "%.2f%%".format(Locale.US, maxDrawdownPct),
                    style = MaterialTheme.typography.bodySmall)
                Text("Binance sync: " + status.state + ". Last successful sync: " +
                    if (status.lastSuccess <= 0L) "Not recorded" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(status.lastSuccess)) + ".",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun parseRiskDate(value: String): Date? {
    for (pattern in listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy")) {
        try { return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) }
        catch (_: Exception) { }
    }
    return null
}
