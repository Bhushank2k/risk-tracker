package com.risktracker.app

import kotlin.random.Random

data class PeriodPnlForecast(
    val sampleCount: Int,
    val remainingSessions: Int,
    val currentPeriodPnl: Double,
    val expectedEndPnl: Double?,
    val lowerEndPnl: Double?,
    val upperEndPnl: Double?,
    val targetPnl: Double?,
    val targetReachPercent: Double?,
    val requiredAverageDailyPnl: Double?,
    val reliability: ForecastReliability
)

object PeriodPnlForecastEngine {
    private const val SIMULATION_COUNT = 2_000

    /**
     * Resamples prior recorded daily outcomes with replacement to estimate a period-end range.
     * Target reach is an empirical bootstrap frequency, not a calibrated probability.
     */
    fun forecast(
        historicalRows: List<ReconciledDailyPnl>,
        asOfDate: String,
        currentPeriodPnl: Double,
        remainingSessions: Int,
        targetPnl: Double? = null,
        lookbackDays: Int = 90,
        seed: Int = 42
    ): PeriodPnlForecast {
        require(remainingSessions in 0..60)
        require(currentPeriodPnl.isFinite())
        require(targetPnl == null || targetPnl.isFinite())
        require(lookbackDays in 1..365)
        val cutoff = canonicalPnlDate(asOfDate) ?: error("Invalid forecast date")
        val outcomes = historicalRows.mapNotNull { row ->
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
        if (n < 10) return PeriodPnlForecast(
            n, remainingSessions, currentPeriodPnl, null, null, null,
            targetPnl, null, targetPnl?.let { if (remainingSessions > 0) (it - currentPeriodPnl) / remainingSessions else null },
            reliability
        )

        val mean = outcomes.average()
        val random = Random(seed)
        val simulatedTotals = if (remainingSessions == 0) {
            listOf(currentPeriodPnl)
        } else {
            List(SIMULATION_COUNT) {
                var total = currentPeriodPnl
                repeat(remainingSessions) { total += outcomes[random.nextInt(n)] }
                total
            }
        }.sorted()
        fun percentile(p: Double): Double {
            val index = ((simulatedTotals.size - 1) * p).toInt().coerceIn(0, simulatedTotals.lastIndex)
            return simulatedTotals[index]
        }
        val expected = currentPeriodPnl + mean * remainingSessions
        return PeriodPnlForecast(
            sampleCount = n,
            remainingSessions = remainingSessions,
            currentPeriodPnl = currentPeriodPnl,
            expectedEndPnl = expected,
            lowerEndPnl = percentile(0.10),
            upperEndPnl = percentile(0.90),
            targetPnl = targetPnl,
            targetReachPercent = targetPnl?.let { simulatedTotals.count { value -> value >= it } * 100.0 / simulatedTotals.size },
            requiredAverageDailyPnl = targetPnl?.let {
                if (remainingSessions > 0) (it - currentPeriodPnl) / remainingSessions else null
            },
            reliability = reliability
        )
    }
}
