package com.risktracker.app

import kotlin.math.sqrt

enum class VolatilityRegime { QUIET, NORMAL, ELEVATED, EXTREME, INSUFFICIENT }

data class VolatilityForecast(
    val symbol: String,
    val sampleCount: Int,
    val recentVolatilityPercent: Double?,
    val volatilityPercentile: Double?,
    val meanAbsoluteDailyMovePercent: Double?,
    val p90AbsoluteDailyMovePercent: Double?,
    val regime: VolatilityRegime,
    val reliabilityLabel: String
)

/**
 * Descriptive next-session volatility baseline from completed daily candles. Percentiles are
 * empirical historical ranks; they are not price targets or guaranteed next-day ranges.
 */
object VolatilityForecastEngine {
    fun forecast(symbol: String, candles: List<MarketCandle>, lookbackReturns: Int = 20): VolatilityForecast {
        require(lookbackReturns in 10..60)
        val closes = candles.filter {
            it.close.isFinite() && it.close > 0.0 && it.high.isFinite() && it.low.isFinite() &&
                it.high >= it.low
        }.sortedBy { it.closeTime }.map { it.close }
        val returns = closes.zipWithNext().mapNotNull { (previous, current) ->
            if (previous <= 0.0) null else ((current / previous) - 1.0) * 100.0
        }.filter { it.isFinite() }
        if (returns.size < lookbackReturns) {
            return VolatilityForecast(
                symbol, returns.size, null, null, null, null,
                VolatilityRegime.INSUFFICIENT, "Insufficient completed daily candles"
            )
        }

        val recent = returns.takeLast(lookbackReturns)
        val recentVol = standardDeviation(recent)
        val rollingVols = returns.windowed(lookbackReturns).map { standardDeviation(it) }
        val rank = rollingVols.count { it <= recentVol } * 100.0 / rollingVols.size
        val absoluteMoves = recent.map { kotlin.math.abs(it) }.sorted()
        val meanAbsolute = absoluteMoves.average()
        val p90 = percentile(absoluteMoves, 0.90)
        val regime = when {
            rank < 25.0 -> VolatilityRegime.QUIET
            rank < 75.0 -> VolatilityRegime.NORMAL
            rank < 90.0 -> VolatilityRegime.ELEVATED
            else -> VolatilityRegime.EXTREME
        }
        val reliability = when {
            returns.size < 40 -> "Preliminary · short history"
            returns.size < 90 -> "Historical comparison · under 90 returns"
            else -> "Historical comparison · 90+ returns"
        }
        return VolatilityForecast(
            symbol = symbol,
            sampleCount = returns.size,
            recentVolatilityPercent = recentVol,
            volatilityPercentile = rank,
            meanAbsoluteDailyMovePercent = meanAbsolute,
            p90AbsoluteDailyMovePercent = p90,
            regime = regime,
            reliabilityLabel = reliability
        )
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun percentile(sorted: List<Double>, probability: Double): Double {
        val index = ((sorted.size - 1) * probability).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
