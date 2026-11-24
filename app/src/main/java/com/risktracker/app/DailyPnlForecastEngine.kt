package com.risktracker.app

import kotlin.math.floor
import kotlin.math.ceil

enum class ForecastReliability { INSUFFICIENT, LIMITED, PRELIMINARY, HISTORICAL }

data class DailyPnlForecast(
    val asOfDate: String,
    val sampleCount: Int,
    val expectedPnl: Double?,
    val lowerOutcome: Double?,
    val upperOutcome: Double?,
    val profitableDayPercent: Double?,
    val losingDayPercent: Double?,
    val flatDayPercent: Double?,
    val reliability: ForecastReliability
) {
    val hasEstimate: Boolean get() = sampleCount > 0 && expectedPnl != null
    val reliabilityLabel: String get() = when (reliability) {
        ForecastReliability.INSUFFICIENT -> "Insufficient history"
        ForecastReliability.LIMITED -> "Limited history"
        ForecastReliability.PRELIMINARY -> "Preliminary historical estimate"
        ForecastReliability.HISTORICAL -> "Historical estimate"
    }
}

/**
 * Descriptive next-session baseline built only from completed, dated observations before asOfDate.
 * It reports empirical outcome percentiles and frequencies rather than claiming a calibrated
 * probability of future profit. Missing calendar days are not treated as zero-P&L days.
 */
object DailyPnlForecastEngine {
    fun forecast(
        rows: List<ReconciledDailyPnl>,
        asOfDate: String,
        lookbackDays: Int = 90
    ): DailyPnlForecast {
        require(lookbackDays in 1..365)
        val cutoff = canonicalPnlDate(asOfDate) ?: error("Invalid forecast date")
        val outcomes = rows.mapNotNull { row ->
            val date = canonicalPnlDate(row.date) ?: return@mapNotNull null
            row.selectedPnl.takeIf { date < cutoff && it.isFinite() }?.let { date to it }
        }.sortedBy { it.first }.takeLast(lookbackDays).map { it.second }

        val n = outcomes.size
        val reliability = when {
            n < 10 -> ForecastReliability.INSUFFICIENT
            n < 30 -> ForecastReliability.LIMITED
            n < 60 -> ForecastReliability.PRELIMINARY
            else -> ForecastReliability.HISTORICAL
        }
        if (n == 0) return DailyPnlForecast(cutoff, 0, null, null, null, null, null, null, reliability)

        val sorted = outcomes.sorted()
        return DailyPnlForecast(
            asOfDate = cutoff,
            sampleCount = n,
            expectedPnl = outcomes.average(),
            lowerOutcome = percentile(sorted, 0.10),
            upperOutcome = percentile(sorted, 0.90),
            profitableDayPercent = outcomes.count { it > 0.0 } * 100.0 / n,
            losingDayPercent = outcomes.count { it < 0.0 } * 100.0 / n,
            flatDayPercent = outcomes.count { it == 0.0 } * 100.0 / n,
            reliability = reliability
        )
    }

    private fun percentile(sorted: List<Double>, probability: Double): Double {
        if (sorted.size == 1) return sorted.single()
        val position = (sorted.size - 1) * probability
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }
}
