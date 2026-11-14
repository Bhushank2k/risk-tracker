package com.risktracker.app

import java.security.MessageDigest
import java.util.Locale

/** Strict importer for Binance USD-M Futures kline CSV exports (header optional). */
object MarketReplayCsv {
    fun parseBinanceKlines(csv: String): List<MarketCandle> {
        val rows = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(rows.isNotEmpty()) { "CSV contains no candle rows." }
        val candles = mutableListOf<MarketCandle>()
        rows.forEachIndexed { index, row ->
            val fields = row.split(',').map { it.trim() }
            if (index == 0 && fields.firstOrNull()?.toLongOrNull() == null) {
                require(fields.firstOrNull()?.contains("open_time", ignoreCase = true) == true ||
                    fields.firstOrNull()?.contains("open time", ignoreCase = true) == true) {
                    "CSV line 1 is not a Binance kline row or recognized header."
                }
                return@forEachIndexed
            }
            require(fields.size >= 8) { "CSV line ${index + 1}: expected at least 8 Binance kline columns." }
            fun number(column: Int, name: String): Double =
                fields[column].toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException("CSV line ${index + 1}: invalid $name.")
            val openTime = fields[0].toLongOrNull()
                ?: throw IllegalArgumentException("CSV line ${index + 1}: invalid open time.")
            val closeTime = fields[6].toLongOrNull()
                ?: throw IllegalArgumentException("CSV line ${index + 1}: invalid close time.")
            val candle = MarketCandle(openTime, number(1, "open"), number(2, "high"), number(3, "low"),
                number(4, "close"), number(5, "volume"), closeTime, number(7, "quote volume"))
            require(candle.openTime >= 0L && candle.closeTime > candle.openTime) {
                "CSV line ${index + 1}: invalid candle timestamps."
            }
            require(candle.open > 0.0 && candle.close > 0.0 && candle.low > 0.0 &&
                candle.high >= maxOf(candle.open, candle.close) &&
                candle.low <= minOf(candle.open, candle.close) &&
                candle.volume >= 0.0 && candle.quoteVolume >= 0.0) {
                "CSV line ${index + 1}: invalid OHLCV range or negative volume."
            }
            require(candles.lastOrNull()?.let { candle.openTime > it.openTime && candle.closeTime > it.closeTime } != false) {
                "CSV line ${index + 1}: candles must be strictly chronological with no duplicates."
            }
            candles += candle
        }
        require(candles.size >= 60) { "At least 60 valid completed candles are required for replay." }
        return candles
    }
}

data class MarketReplayReport(
    val symbol: String, val interval: String, val sourceLabel: String, val candleCount: Int,
    val firstOpenTime: Long, val lastCloseTime: Long, val splitIndex: Int,
    val training: MarketActivityEngine.ReplayStats, val outOfSample: MarketActivityEngine.ReplayStats,
    val horizonBars: Int, val feeBpsPerSide: Double, val slippageBpsPerSide: Double, val datasetSha256: String
)

/** Chronological holdout: pre-split bars warm indicators, but only post-split setups are scored. */
object MarketReplayRunner {
    fun run(symbol: String, interval: String, sourceLabel: String, candles: List<MarketCandle>,
        horizonBars: Int = 12, feeBpsPerSide: Double = 8.0, slippageBpsPerSide: Double = 5.0,
        trainingFraction: Double = 0.70): MarketReplayReport {
        require(symbol.matches(Regex("[A-Z0-9]{5,20}"))) { "Use a valid exchange symbol." }
        require(interval.isNotBlank() && sourceLabel.isNotBlank()) { "Interval and source label are required." }
        require(horizonBars > 0 && feeBpsPerSide >= 0.0 && slippageBpsPerSide >= 0.0)
        require(trainingFraction in 0.50..0.90) { "Training fraction must be between 50% and 90%." }
        require(candles.size > 60 + horizonBars) { "At least 61 plus the holding horizon candles are required." }
        require(candles.zipWithNext().all { (a, b) -> b.openTime > a.openTime && b.closeTime > a.closeTime && b.openTime > a.closeTime }) {
            "Replay candles must be strictly chronological, non-overlapping and unique."
        }
        require(candles.all {
            it.openTime >= 0L && it.closeTime > it.openTime &&
                listOf(it.open, it.high, it.low, it.close, it.volume, it.quoteVolume).all(Double::isFinite) &&
                it.open > 0.0 && it.close > 0.0 && it.low > 0.0 &&
                it.high >= maxOf(it.open, it.close) && it.low <= minOf(it.open, it.close) &&
                it.volume >= 0.0 && it.quoteVolume >= 0.0
        }) { "Replay dataset contains invalid OHLCV rows." }
        val split = (candles.size * trainingFraction).toInt().coerceIn(31, candles.size - horizonBars - 1)
        val training = MarketActivityEngine.replay(candles.take(split), horizonBars, feeBpsPerSide, slippageBpsPerSide)
        val test = MarketActivityEngine.replay(candles, horizonBars, feeBpsPerSide, slippageBpsPerSide, evaluationStartIndex = split)
        val canonical = candles.joinToString("\n") {
            String.format(Locale.US, "%d,%.12g,%.12g,%.12g,%.12g,%.12g,%d,%.12g",
                it.openTime, it.open, it.high, it.low, it.close, it.volume, it.closeTime, it.quoteVolume)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MarketReplayReport(symbol, interval, sourceLabel, candles.size, candles.first().openTime,
            candles.last().closeTime, split, training, test, horizonBars, feeBpsPerSide, slippageBpsPerSide, digest)
    }
}
