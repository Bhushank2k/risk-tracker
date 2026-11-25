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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val RISK_IST: ZoneId = ZoneId.of("Asia/Kolkata")

data class DailyTargetRiskSettings(
    val dailyTargetPercent: Double = 10.0,
    val dailyMaxLossPercent: Double = 5.0
)

data class DailyTargetRiskResult(
    val istDate: LocalDate,
    val equity: Double?,
    val baselineEquity: Double?,
    val dailyPnl: Double?,
    val targetAmount: Double?,
    val maxLossAmount: Double?,
    val targetProgress: Double,
    val lossUsage: Double,
    val status: DailyTargetRiskStatus,
    val pnlSource: String
)

enum class DailyTargetRiskStatus {
    ON_TRACK, TARGET_REACHED, LOSS_WARNING, LOSS_LIMIT_REACHED, DATA_UNAVAILABLE, INVALID
}

fun istTradingDate(nowMillis: Long = System.currentTimeMillis()): LocalDate =
    Instant.ofEpochMilli(nowMillis).atZone(RISK_IST).toLocalDate()

/**
 * Daily thresholds are based on estimated start-of-day equity (current equity minus today's
 * reconciled P&L), so reaching a profit/loss threshold does not move the threshold itself.
 * This is advisory only and never places, blocks, or cancels exchange orders.
 */
fun calculateDailyTargetRisk(
    equity: Double?,
    dailyPnl: Double?,
    targetPercent: Double,
    maxLossPercent: Double,
    nowMillis: Long = System.currentTimeMillis()
): DailyTargetRiskResult {
    val date = istTradingDate(nowMillis)
    val validEquity = equity?.takeIf { it.isFinite() && it > 0.0 }
    val validPnl = dailyPnl?.takeIf { it.isFinite() }
    val validPercents = targetPercent.isFinite() && maxLossPercent.isFinite() &&
        targetPercent in 0.1..100.0 && maxLossPercent in 0.1..100.0
    if (!validPercents) {
        return DailyTargetRiskResult(date, validEquity, null, validPnl, null, null,
            0.0, 0.0, DailyTargetRiskStatus.INVALID, "Settings")
    }
    if (validEquity == null) {
        return DailyTargetRiskResult(date, null, null, validPnl, null, null,
            0.0, 0.0, DailyTargetRiskStatus.DATA_UNAVAILABLE, "Account equity unavailable")
    }
    val baseline = if (validPnl != null) validEquity - validPnl else validEquity
    if (!baseline.isFinite() || baseline <= 0.0) {
        return DailyTargetRiskResult(date, validEquity, null, validPnl, null, null,
            0.0, 0.0, DailyTargetRiskStatus.INVALID, "Invalid start-of-day equity")
    }
    val target = baseline * targetPercent / 100.0
    val maxLoss = baseline * maxLossPercent / 100.0
    if (!target.isFinite() || !maxLoss.isFinite() || target <= 0.0 || maxLoss <= 0.0) {
        return DailyTargetRiskResult(date, validEquity, baseline, validPnl, null, null,
            0.0, 0.0, DailyTargetRiskStatus.INVALID, "Calculated limits are out of range")
    }
    val targetProgress = if (validPnl == null) 0.0 else (validPnl.coerceAtLeast(0.0) / target).coerceIn(0.0, 1.0)
    val lossUsage = if (validPnl == null) 0.0 else ((-validPnl).coerceAtLeast(0.0) / maxLoss).coerceAtLeast(0.0)
    val status = when {
        validPnl == null -> DailyTargetRiskStatus.DATA_UNAVAILABLE
        validPnl >= target -> DailyTargetRiskStatus.TARGET_REACHED
        validPnl <= -maxLoss -> DailyTargetRiskStatus.LOSS_LIMIT_REACHED
        lossUsage >= 0.75 -> DailyTargetRiskStatus.LOSS_WARNING
        else -> DailyTargetRiskStatus.ON_TRACK
    }
    return DailyTargetRiskResult(date, validEquity, baseline, validPnl, target, maxLoss,
        targetProgress, lossUsage, status, if (validPnl == null) "No verified P&L for today" else "IST daily ledger")
}

/** Daily P&L uses exchange futures realized P&L when every same-day futures fill has it;
 * otherwise it falls back to the app's non-additive manual/journal reconciliation. */
fun dailyPnlForIstDate(
    entries: List<PnlEntry>,
    trades: List<TradeEntry>,
    fills: List<BinanceJournalFill>,
    nowMillis: Long = System.currentTimeMillis()
): Pair<Double?, String> {
    val today = istTradingDate(nowMillis)
    val todaysFutures = fills.filter {
        it.market == "USDⓈ-M Futures" &&
            Instant.ofEpochMilli(it.time).atZone(RISK_IST).toLocalDate() == today
    }
    if (todaysFutures.isNotEmpty() && todaysFutures.all { it.realizedPnl != null && it.realizedPnl!!.isFinite() }) {
        val total = todaysFutures.sumOf { it.realizedPnl!! }
        if (total.isFinite()) return total to "Binance futures realized P&L (before fees)"
    }
    val row = reconcileDailyPnl(entries, emptyList(), trades).lastOrNull { it.date == today.toString() }
    return row?.selectedPnl?.takeIf { it.isFinite() } to
        (row?.source?.name?.lowercase(Locale.ROOT)?.replaceFirstChar { it.uppercase() } ?: "No verified P&L for today")
}

class DailyTargetRiskSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("daily_target_risk_v1", Context.MODE_PRIVATE)
    fun load() = DailyTargetRiskSettings(
        dailyTargetPercent = prefs.getString("target_percent", "10")?.toDoubleOrNull() ?: 10.0,
        dailyMaxLossPercent = prefs.getString("max_loss_percent", "5")?.toDoubleOrNull() ?: 5.0
    )
    fun save(settings: DailyTargetRiskSettings) {
        require(settings.dailyTargetPercent.isFinite() && settings.dailyTargetPercent in 0.1..100.0)
        require(settings.dailyMaxLossPercent.isFinite() && settings.dailyMaxLossPercent in 0.1..100.0)
        check(prefs.edit()
            .putString("target_percent", settings.dailyTargetPercent.toString())
            .putString("max_loss_percent", settings.dailyMaxLossPercent.toString())
            .commit()) { "Could not save daily risk settings." }
    }
}

@Composable
fun DailyTargetRiskScreen(
    entries: List<PnlEntry>,
    trades: List<TradeEntry>,
    fills: List<BinanceJournalFill>
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { DailyTargetRiskSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var targetInput by remember(settings) { mutableStateOf(settings.dailyTargetPercent.toString()) }
    var lossInput by remember(settings) { mutableStateOf(settings.dailyMaxLossPercent.toString()) }
    var snapshot by remember { mutableStateOf(BinanceAccountSnapshotStore(context).load()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = BinanceAccountSnapshotStore(context).load()
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val dailyPnl = dailyPnlForIstDate(entries, trades, fills, now)
    val equity = snapshot?.let { it.spot.valueUsdt + it.futures.totalEquity }
        ?.takeIf { it.isFinite() && it > 0.0 }
    val result = calculateDailyTargetRisk(equity, dailyPnl.first,
        settings.dailyTargetPercent, settings.dailyMaxLossPercent, now)
    val currency = remember { Store(context).inr }
    fun money(value: Double): String = "₹" + String.format(Locale.US, "%,.2f", value * currency)
    val ageMinutes = snapshot?.let { ((now - it.savedAt).coerceAtLeast(0L) / 60_000L) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Automatic daily target & risk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Equity = Binance Spot value (USDT) + USDⓈ-M Futures total equity. Targets use estimated start-of-day equity and reset on the IST calendar date.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Trading day: ${result.istDate} · IST", style = MaterialTheme.typography.labelMedium)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("TOTAL ACCOUNT EQUITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(equity?.let(::money) ?: "Connect Binance to load equity",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (ageMinutes == null) "No saved account snapshot"
                    else "Last successful account snapshot: ${ageMinutes} min ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ageMinutes == null || ageMinutes > 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if ((snapshot?.spot?.unpricedAssets ?: 0) > 0) {
                    Text("${snapshot!!.spot.unpricedAssets} Spot assets are unpriced; total equity may be understated.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Daily profit target", result.targetAmount?.let(::money) ?: "—", Modifier.weight(1f))
            MetricTile("Maximum daily loss", result.maxLossAmount?.let(::money) ?: "—", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Today's P&L", result.dailyPnl?.let(::money) ?: "Unavailable", Modifier.weight(1f))
            MetricTile("Start equity", result.baselineEquity?.let(::money) ?: "—", Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Profit target progress", style = MaterialTheme.typography.labelLarge)
                Text("${(result.targetProgress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { result.targetProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Daily loss limit used", style = MaterialTheme.typography.labelLarge)
                Text("${(result.lossUsage * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { result.lossUsage.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(),
                color = if (result.lossUsage >= 0.75) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
        }
        val statusText = when (result.status) {
            DailyTargetRiskStatus.ON_TRACK -> "Within configured daily limits"
            DailyTargetRiskStatus.TARGET_REACHED -> "Daily profit target reached"
            DailyTargetRiskStatus.LOSS_WARNING -> "Warning: 75% or more of daily loss limit used"
            DailyTargetRiskStatus.LOSS_LIMIT_REACHED -> "Daily maximum loss reached"
            DailyTargetRiskStatus.DATA_UNAVAILABLE -> "P&L unavailable — risk status cannot be verified"
            DailyTargetRiskStatus.INVALID -> "Invalid equity or risk settings"
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = when (result.status) {
                DailyTargetRiskStatus.LOSS_WARNING, DailyTargetRiskStatus.LOSS_LIMIT_REACHED, DailyTargetRiskStatus.INVALID -> MaterialTheme.colorScheme.error
                DailyTargetRiskStatus.TARGET_REACHED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            })
        Text("P&L source: ${dailyPnl.second}. Binance futures realized P&L excludes commissions/funding here; fallback ledger is not exchange-verified.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Risk settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(targetInput, { targetInput = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Daily profit target (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(lossInput, { lossInput = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Daily maximum loss (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Button(onClick = {
            val target = targetInput.toDoubleOrNull()
            val loss = lossInput.toDoubleOrNull()
            if (target == null || loss == null || !target.isFinite() || !loss.isFinite() ||
                target !in 0.1..100.0 || loss !in 0.1..100.0) {
                message = "Enter percentages from 0.1% to 100%."
            } else {
                val updated = DailyTargetRiskSettings(target, loss)
                runCatching { settingsStore.save(updated) }
                    .onSuccess { settings = updated; message = "Settings saved." }
                    .onFailure { message = "Could not save settings. Try again." }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save risk settings") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("These are advisory limits, not exchange-enforced protections. The app does not stop orders or guarantee a maximum loss. Review stale account data before relying on the figures.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
