package com.risktracker.app

enum class MarketRegime { BULLISH_TREND, BEARISH_TREND, SIDEWAYS, HIGH_VOLATILITY, INSUFFICIENT }

data class MarketRegimeAssessment(
    val symbol: String,
    val regime: MarketRegime,
    val confidencePercent: Double?,
    val latestClose: Double?,
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val twentyDayChangePercent: Double?,
    val rsi14: Double?,
    val evidence: List<String>,
    val sampleCount: Int
)

/**
 * Explainable daily market classification. It uses only completed candles supplied by the caller.
 * Confidence is a rule-alignment score, not a statistically calibrated probability.
 */
object MarketRegimeEngine {
    fun classify(
        symbol: String,
        candles: List<MarketCandle>,
        volatility: VolatilityForecast? = null
    ): MarketRegimeAssessment {
        val valid = candles.filter {
            it.close.isFinite() && it.close > 0.0 && it.high.isFinite() && it.low.isFinite() &&
                it.high >= it.low && it.closeTime > 0L
        }.sortedBy { it.closeTime }
        val closes = valid.map { it.close }
        if (closes.size < 200) {
            return MarketRegimeAssessment(
                symbol, MarketRegime.INSUFFICIENT, null, closes.lastOrNull(),
                null, null, null, null, null,
                listOf("At least 200 completed daily candles are required for the long-term trend baseline."),
                closes.size
            )
        }
        val ema20 = ema(closes, 20)
        val ema50 = ema(closes, 50)
        val ema200 = ema(closes, 200)
        val latest = closes.last()
        val change20 = (latest / closes[closes.lastIndex - 20] - 1.0) * 100.0
        val rsi = ScannerCalculationCore.rsiWilder(closes, 14)
        val volatilityRank = volatility?.volatilityPercentile
        val highVolatility = volatilityRank != null && volatilityRank >= 90.0
        val bullish = (if (latest > ema200) 35 else 0) +
            (if (ema20 > ema50) 30 else 0) +
            (if (change20 > 0.0) 20 else 0) +
            (if (rsi != null && rsi >= 50.0) 15 else 0)
        val bearish = (if (latest < ema200) 35 else 0) +
            (if (ema20 < ema50) 30 else 0) +
            (if (change20 < 0.0) 20 else 0) +
            (if (rsi != null && rsi <= 50.0) 15 else 0)
        val regime = when {
            highVolatility -> MarketRegime.HIGH_VOLATILITY
            bullish >= 75 && bullish > bearish -> MarketRegime.BULLISH_TREND
            bearish >= 75 && bearish > bullish -> MarketRegime.BEARISH_TREND
            else -> MarketRegime.SIDEWAYS
        }
        val confidence = when (regime) {
            MarketRegime.BULLISH_TREND -> bullish.toDouble()
            MarketRegime.BEARISH_TREND -> bearish.toDouble()
            MarketRegime.HIGH_VOLATILITY -> volatilityRank
            MarketRegime.SIDEWAYS -> (100 - maxOf(bullish, bearish)).toDouble().coerceIn(0.0, 100.0)
            MarketRegime.INSUFFICIENT -> null
        }
        val evidence = buildList {
            add(if (latest > ema200) "Price is above the 200-day EMA" else "Price is below the 200-day EMA")
            add(if (ema20 > ema50) "20-day EMA is above the 50-day EMA" else "20-day EMA is below the 50-day EMA")
            add("20-day price change: " + "%.2f".format(java.util.Locale.US, change20) + "%")
            rsi?.let { add("RSI(14): " + "%.1f".format(java.util.Locale.US, it)) }
            if (highVolatility) add("Volatility is at or above its 90th historical percentile")
        }
        return MarketRegimeAssessment(
            symbol, regime, confidence, latest, ema20, ema50, ema200, change20, rsi, evidence, closes.size
        )
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        for (index in period until values.size) {
            value = values[index] * alpha + value * (1.0 - alpha)
        }
        return value
    }
}
