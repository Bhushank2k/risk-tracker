package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TradingIntelligenceScreen(
    dailyEntries: List<PnlEntry>,
    manualTrades: List<TradeEntry>,
    importedDailyPnl: List<BinanceDailyResult>
) {
    val asOfDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val reconciled = remember(dailyEntries, manualTrades, importedDailyPnl) {
        reconcileDailyPnl(dailyEntries, importedDailyPnl, manualTrades)
    }
    val forecast = remember(reconciled, asOfDate) {
        DailyPnlForecastEngine.forecast(reconciled, asOfDate)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Trading intelligence", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Historical decision support built from reconciled trading records. Forecasts are estimates, not promises.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Next-session P&L baseline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!forecast.hasEstimate) {
                    Text("No completed historical P&L observations are available before today.")
                    Text("Sync exchange history or add dated daily P&L records to build a baseline.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(forecast.expectedPnl!!.usd(), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = pnlColor(forecast.expectedPnl))
                    Text("Average of " + forecast.sampleCount + " recorded result days · " + forecast.reliabilityLabel,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("10th percentile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(forecast.lowerOutcome!!.usd(), fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("90th percentile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(forecast.upperOutcome!!.usd(), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        "Historical recorded-day frequencies: " +
                            forecast.profitableDayPercent!!.num(0) + "% profitable · " +
                            forecast.losingDayPercent!!.num(0) + "% losing · " +
                            forecast.flatDayPercent!!.num(0) + "% flat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Forecast methodology", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Uses the latest 90 recorded daily outcomes before today. The expected result is their arithmetic mean; the range uses empirical 10th and 90th percentiles.")
                Text("Unrecorded calendar days are excluded, not counted as zero. Binance, manual and journal sources follow the existing reconciliation precedence.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This is a historical baseline, not a calibrated prediction. Accuracy must be evaluated against future completed sessions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        PeriodForecastSection(reconciled, asOfDate)
        PersonalTradingCoachSection(manualTrades)
    }
}

@Composable
private fun PeriodForecastSection(rows: List<ReconciledDailyPnl>, asOfDate: String) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("trading_intelligence_targets_v1", 0) }
    var weeklyTargetText by remember { mutableStateOf(prefs.getString("weekly_target", "") ?: "") }
    var monthlyTargetText by remember { mutableStateOf(prefs.getString("monthly_target", "") ?: "") }
    val weeklyTarget = weeklyTargetText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val monthlyTarget = monthlyTargetText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    val today = remember(asOfDate) {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(asOfDate)?.let { parsed ->
            java.util.Calendar.getInstance().apply {
                time = parsed
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        } ?: java.util.Calendar.getInstance()
    }
    val weekStart = remember(today) {
        (today.clone() as java.util.Calendar).apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            val offset = (get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
            add(java.util.Calendar.DAY_OF_YEAR, -offset)
        }
    }
    val weekEnd = remember(weekStart) { (weekStart.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 7) } }
    val monthStart = remember(today) { (today.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) } }
    val monthEnd = remember(monthStart) { (monthStart.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, 1) } }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    fun buildForecast(start: java.util.Calendar, end: java.util.Calendar, target: Double?): PeriodPnlForecast {
        val startKey = formatter.format(start.time)
        val todayKey = formatter.format(today.time)
        val endKey = formatter.format(end.time)
        val completedPnl = rows.filter { row ->
            val date = canonicalPnlDate(row.date)
            date != null && date >= startKey && date < todayKey && row.selectedPnl.isFinite()
        }.sumOf { it.selectedPnl }
        val remaining = ((end.timeInMillis - today.timeInMillis) / 86_400_000L).toInt().coerceIn(0, 60)
        return PeriodPnlForecastEngine.forecast(rows, asOfDate, completedPnl, remaining, target)
    }
    val weekly = remember(rows, asOfDate, weeklyTarget) { buildForecast(weekStart, weekEnd, weeklyTarget) }
    val monthly = remember(rows, asOfDate, monthlyTarget) { buildForecast(monthStart, monthEnd, monthlyTarget) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Weekly & monthly outlook", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Targets are optional. Projections resample your prior recorded result days and include only completed P&L earlier than today.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = weeklyTargetText,
            onValueChange = { weeklyTargetText = it; prefs.edit().putString("weekly_target", it).apply() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Weekly net P&L target (USD)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        PeriodForecastCard("This week", weekly)
        OutlinedTextField(
            value = monthlyTargetText,
            onValueChange = { monthlyTargetText = it; prefs.edit().putString("monthly_target", it).apply() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Monthly net P&L target (USD)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        PeriodForecastCard("This month", monthly)
    }
}

@Composable
private fun PeriodForecastCard(title: String, forecast: PeriodPnlForecast) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (forecast.expectedEndPnl == null) {
                Text("At least 10 prior recorded result days are required for a projection.")
            } else {
                Text("Projected period-end P&L: " + forecast.expectedEndPnl.usd(),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = pnlColor(forecast.expectedEndPnl))
                Text("Historical resampling range: " + forecast.lowerEndPnl!!.usd() + " to " + forecast.upperEndPnl!!.usd())
                Text("Current completed-day P&L: " + forecast.currentPeriodPnl.usd() +
                    " · Remaining calendar sessions: " + forecast.remainingSessions)
                forecast.targetPnl?.let { target ->
                    Text("Target " + target.usd() + " · Historical resampling frequency to reach target: " +
                        forecast.targetReachPercent!!.num(1) + "%")
                    forecast.requiredAverageDailyPnl?.let {
                        Text("Required average per remaining day: " + it.usd())
                    }
                }
                Text(forecast.reliability.name.lowercase().replaceFirstChar { it.uppercase() } +
                    " · " + forecast.sampleCount + " prior recorded days",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PersonalTradingCoachSection(trades: List<TradeEntry>) {
    val report = remember(trades) { PersonalTradingCoachEngine.analyze(trades) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Personal trading coach", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Rule-based feedback from your saved closed-trade journal. It does not infer intent or replace your own review.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Journal coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(report.tradeCount.toString() + " valid closed trades · " + report.taggedTradeCount + " strategy-tagged")
                report.insights.forEach { insight ->
                    Text(insight.title, fontWeight = FontWeight.SemiBold,
                        color = if (insight.severity == CoachInsightSeverity.CAUTION)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(insight.detail, style = MaterialTheme.typography.bodySmall)
                }
                if (report.limitations.isNotEmpty()) {
                    Text("Data limitations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    report.limitations.forEach {
                        Text("• " + it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
