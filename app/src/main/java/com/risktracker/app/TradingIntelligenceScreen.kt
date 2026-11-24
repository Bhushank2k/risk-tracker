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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    }
}
