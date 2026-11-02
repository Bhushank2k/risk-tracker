package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PerformanceSnapshot(
    val netPnl: Double,
    val winRatePercent: Double,
    val winningDays: Int,
    val losingDays: Int,
    val averagePnl: Double,
    val bestDay: Double?,
    val worstDay: Double?,
    val maxDrawdownPercent: Double,
    val tradeCount: Int
)

fun buildPerformanceSnapshot(pnlValues: List<Double>, closingBalances: List<Double>, tradeCount: Int): PerformanceSnapshot {
    val decisive = pnlValues.filter { it != 0.0 && it.isFinite() }
    val wins = decisive.count { it > 0.0 }
    var peak = 0.0
    var maxDrawdown = 0.0
    closingBalances.filter { it.isFinite() && it >= 0.0 }.forEach { balance ->
        if (balance > peak) peak = balance
        if (peak > 0.0) maxDrawdown = maxOf(maxDrawdown, (peak - balance) / peak * 100.0)
    }
    val cleanPnl = pnlValues.filter { it.isFinite() }
    return PerformanceSnapshot(
        netPnl = cleanPnl.sum(),
        winRatePercent = if (decisive.isEmpty()) 0.0 else wins * 100.0 / decisive.size,
        winningDays = cleanPnl.count { it > 0.0 },
        losingDays = cleanPnl.count { it < 0.0 },
        averagePnl = if (cleanPnl.isEmpty()) 0.0 else cleanPnl.average(),
        bestDay = cleanPnl.maxOrNull(),
        worstDay = cleanPnl.minOrNull(),
        maxDrawdownPercent = maxDrawdown,
        tradeCount = tradeCount.coerceAtLeast(0)
    )
}

private val ANALYTICS_DATE_FORMATS = listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy")

private fun parseAnalyticsDate(value: String): Date? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    for (pattern in INSIGHTS_DATE_FORMATS) {
        val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val parsed = format.parse(trimmed, position)
        if (parsed != null && position.index == trimmed.length) return parsed
    }
    return null
}

/** Shared local-calendar period filter used by the unified Analytics destination. */
fun filterPnlEntriesForPeriod(entries: List<PnlEntry>, days: Int?, nowMillis: Long): List<PnlEntry> {
    if (days == null) return entries
    if (days <= 0) return emptyList()

    val today = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }
    val endExclusive = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    return entries.filter { entry ->
        val time = parseInsightsDate(entry.date)?.time
        time != null && time >= start.timeInMillis && time < endExclusive.timeInMillis
    }
}


data class ExchangeOrderAnalytics(
    val realizedOrders: List<Double>,
    val grossProfit: Double,
    val grossLoss: Double,
    val winRatePercent: Double?,
    val profitFactor: Double?,
    val averageWin: Double?,
    val averageLoss: Double?,
    val knownStableFees: Double,
    val feesInOtherAssets: Map<String, Double>,
    val netAfterKnownStableFees: Double
)

/**
 * Groups partial fills by exchange order before classifying outcomes. Only stable-quote fees
 * (USDT/USDC) are subtracted from USD-denominated realized P&L; fees in other assets stay
 * separately itemized because no historical conversion is available.
 */
fun summarizeExchangeOrders(fills: List<BinanceJournalFill>): ExchangeOrderAnalytics {
    val orders = fills.filter { it.realizedPnl?.isFinite() == true }
        .groupBy { fill ->
            "${fill.exchange}|${fill.market}|${fill.symbol}|" +
                (fill.orderId.takeIf { it.isNotBlank() } ?: fill.id)
        }.values.map { rows -> rows.sumOf { it.realizedPnl ?: 0.0 } }.filter { it.isFinite() }
    val wins = orders.filter { it > 0.0 }
    val losses = orders.filter { it < 0.0 }
    val grossProfit = wins.sum()
    val grossLoss = -losses.sum()
    val feeByAsset = fills.filter { it.fee.isFinite() && it.fee > 0.0 }
        .groupBy { it.feeAsset.ifBlank { "Unknown asset" }.uppercase(Locale.ROOT) }
        .mapValues { (_, rows) -> rows.sumOf { it.fee } }
    val stableFees = feeByAsset.filterKeys { it in setOf("USDT", "USDC") }.values.sum()
    val otherFees = feeByAsset.filterKeys { it !in setOf("USDT", "USDC") }
    val decisive = wins.size + losses.size
    return ExchangeOrderAnalytics(
        realizedOrders = orders,
        grossProfit = grossProfit,
        grossLoss = grossLoss,
        winRatePercent = if (decisive == 0) null else wins.size * 100.0 / decisive,
        profitFactor = when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            else -> null
        },
        averageWin = wins.takeIf { it.isNotEmpty() }?.average(),
        averageLoss = losses.takeIf { it.isNotEmpty() }?.average(),
        knownStableFees = stableFees,
        feesInOtherAssets = otherFees,
        netAfterKnownStableFees = grossProfit - grossLoss - stableFees
    )
}
