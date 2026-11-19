package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ExplosionStage { BUILDING, IGNITION, CONFIRMED, REJECTED }
enum class ExplosionDirection { LONG, SHORT, NEUTRAL }

data class ExplosionConfig(
    val minimumQuoteVolumeUsd: Double = 2_000_000.0,
    val maximumSpreadBps: Double = 30.0,
    val maximumFundingRateAbs: Double = 0.0008,
    val watchScore: Int = 55,
    val ignitionScore: Int = 70,
    val lookbackBars: Int = 24,
    val volumeLookbackBars: Int = 20,
    val atrPeriod: Int = 14,
    val replayHorizonBars: Int = 12,
    val feeBpsPerSide: Double = 8.0,
    val slippageBpsPerSide: Double = 5.0
)

data class ExplosionMarketContext(
    val quoteVolume24h: Double,
    val spreadBps: Double?,
    val fundingRate: Double?,
    val btcChange24hPct: Double?,
    val openInterestChangePct: Double?,
    val openInterestAvailable: Boolean = false
)

data class ExplosionBreakdown(
    val compression: Int,
    val relativeVolume: Int,
    val resistancePressure: Int,
    val takerFlow: Int,
    val relativeStrength: Int,
    val openInterest: Int
) { val total: Int get() = compression + relativeVolume + resistancePressure + takerFlow + relativeStrength + openInterest }

data class ExplosionSignal(
    val symbol: String,
    val score: Int,
    val stage: ExplosionStage,
    val direction: ExplosionDirection,
    val lastPrice: Double,
    val triggerPrice: Double?,
    val invalidationPrice: Double?,
    val atr: Double?,
    val quoteVolume24h: Double,
    val spreadBps: Double?,
    val fundingRate: Double?,
    val openInterestChangePct: Double?,
    val breakdown: ExplosionBreakdown,
    val riskFlags: List<String>,
    val reasons: List<String>,
    val candleCloseTime: Long
)

data class ExplosionReplayStats(
    val trades: Int,
    val wins: Int,
    val winRatePct: Double?,
    val averageReturnPct: Double?,
    val profitFactor: Double?,
    val maxDrawdownPct: Double?,
    val averageMfePct: Double?,
    val averageMaePct: Double?,
    val feeBpsPerSide: Double,
    val slippageBpsPerSide: Double,
    val horizonBars: Int
)

object CoinExplosionRadarEngine {
    fun analyze(
        symbol: String,
        candles: List<MarketCandle>,
        context: ExplosionMarketContext,
        config: ExplosionConfig = ExplosionConfig()
    ): ExplosionSignal? {
        val rows = candles.filter { c ->
            listOf(c.open, c.high, c.low, c.close, c.volume, c.quoteVolume).all(Double::isFinite) &&
                c.open > 0.0 && c.close > 0.0 && c.low > 0.0 && c.high >= max(c.open, c.close) &&
                c.low <= min(c.open, c.close) && c.volume >= 0.0 && c.quoteVolume >= 0.0
        }
        val need = max(config.lookbackBars, config.volumeLookbackBars) + config.atrPeriod + 2
        if (rows.size < need || !symbol.matches(Regex("[A-Z0-9]{5,20}"))) return null
        val last = rows.last()
        val prior = rows.dropLast(1)
        val rangeBars = prior.takeLast(config.lookbackBars)
        val volumeBars = prior.takeLast(config.volumeLookbackBars)
        val high = rangeBars.maxOf { it.high }
        val low = rangeBars.minOf { it.low }
        val mid = (high + low) / 2.0
        val rangePct = if (mid > 0.0) (high - low) / mid * 100.0 else Double.POSITIVE_INFINITY
        val volumeBase = volumeBars.map { it.quoteVolume }.average()
        val relativeVolume = if (volumeBase > 0.0) last.quoteVolume / volumeBase else 0.0
        val atr = atr(rows, config.atrPeriod)
        val atrPct = atr?.let { it / last.close * 100.0 }
        val rangeCompression = when {
            rangePct <= 2.0 -> 20
            rangePct <= 3.5 -> 16
            rangePct <= 5.0 -> 10
            rangePct <= 7.0 -> 5
            else -> 0
        }
        val volumePoints = when {
            relativeVolume >= 2.5 -> 20
            relativeVolume >= 1.8 -> 16
            relativeVolume >= 1.4 -> 11
            relativeVolume >= 1.15 -> 6
            else -> 0
        }
        val distanceToHighPct = (high - last.close) / last.close * 100.0
        val distanceToLowPct = (last.close - low) / last.close * 100.0
        val pressurePoints = when {
            distanceToHighPct in 0.0..0.7 -> 15
            distanceToHighPct in 0.0..1.5 -> 11
            distanceToLowPct in 0.0..0.7 -> 15
            distanceToLowPct in 0.0..1.5 -> 11
            else -> 0
        }
        val takerRatio = if (last.quoteVolume > 0.0) (last.takerBuyQuoteVolume / last.quoteVolume).coerceIn(0.0, 1.0) else 0.5
        val takerPoints = when {
            takerRatio >= 0.62 || takerRatio <= 0.38 -> 10
            takerRatio >= 0.56 || takerRatio <= 0.44 -> 7
            takerRatio >= 0.52 || takerRatio <= 0.48 -> 3
            else -> 0
        }
        val rs = context.btcChange24hPct
        val coinChangeProxy = if (rows.size >= 25) (last.close / rows[rows.size - 25].close - 1.0) * 100.0 else 0.0
        val relativeStrength = if (rs != null) when {
            coinChangeProxy - rs >= 5.0 -> 20
            coinChangeProxy - rs >= 2.5 -> 14
            coinChangeProxy - rs >= 1.0 -> 8
            else -> 0
        } else 0
        val oi = context.openInterestChangePct
        val oiPoints = if (!context.openInterestAvailable || oi == null) 0 else when {
            oi >= 5.0 -> 15
            oi >= 2.5 -> 11
            oi >= 1.0 -> 7
            oi > 0.0 -> 3
            else -> 0
        }
        val breakdown = ExplosionBreakdown(rangeCompression, volumePoints, pressurePoints, takerPoints, relativeStrength, oiPoints)
        val score = breakdown.total.coerceIn(0, 100)
        val longPressure = distanceToHighPct <= distanceToLowPct
        val flowLong = takerRatio >= 0.52
        val flowShort = takerRatio <= 0.48
        val direction = when {
            flowLong -> ExplosionDirection.LONG
            flowShort -> ExplosionDirection.SHORT
            longPressure -> ExplosionDirection.LONG
            else -> ExplosionDirection.SHORT
        }
        val risk = mutableListOf<String>()
        if (context.quoteVolume24h < config.minimumQuoteVolumeUsd) risk += "24h quote volume below liquidity floor"
        if (context.spreadBps == null) risk += "Spread data unavailable"
        else if (context.spreadBps > config.maximumSpreadBps) risk += "Spread exceeds safety limit"
        if (context.fundingRate == null) risk += "Funding data unavailable"
        else if (abs(context.fundingRate) >= config.maximumFundingRateAbs) risk += "Extreme funding: crowded positioning risk"
        if (atrPct != null && atrPct > 8.0) risk += "Extreme 1h ATR: volatility risk"
        if (last.quoteVolume <= 0.0 || context.quoteVolume24h <= 0.0) risk += "No reliable traded volume"
        val hardReject = context.quoteVolume24h < config.minimumQuoteVolumeUsd ||
            (context.spreadBps == null || context.spreadBps > config.maximumSpreadBps) ||
            last.close <= 0.0 || last.quoteVolume <= 0.0
        val breakout = last.close > high || last.close < low
        val stage = when {
            hardReject -> ExplosionStage.REJECTED
            breakout && relativeVolume >= 1.2 && score >= config.ignitionScore -> ExplosionStage.CONFIRMED
            score >= config.ignitionScore -> ExplosionStage.IGNITION
            score >= config.watchScore -> ExplosionStage.BUILDING
            else -> ExplosionStage.BUILDING
        }
        val invalidation = if (direction == ExplosionDirection.LONG) low - (atr ?: 0.0) * 0.25 else high + (atr ?: 0.0) * 0.25
        val trigger = if (direction == ExplosionDirection.LONG) high else low
        val reasons = buildList {
            add("24-bar range %.2f%%".format(rangePct))
            add("Last-hour quote volume %.2f× its prior 20-hour average".format(relativeVolume))
            add("Taker-buy share %.1f%%".format(takerRatio * 100.0))
            if (context.openInterestAvailable && oi != null) add("Open interest change %.2f%%".format(oi))
            if (relativeStrength > 0) add("Outperforming BTC over the recent 24-bar window")
            if (breakout) add("Latest completed candle closed beyond the prior range")
        }
        return ExplosionSignal(symbol, score, stage, direction, last.close, trigger, invalidation, atr,
            context.quoteVolume24h, context.spreadBps, context.fundingRate, oi, breakdown, risk, reasons, last.closeTime)
    }

    fun replay(
        candles: List<MarketCandle>,
        config: ExplosionConfig = ExplosionConfig(),
        scoreThreshold: Int = config.watchScore
    ): ExplosionReplayStats {
        require(scoreThreshold in 0..100)
        require(config.replayHorizonBars in 1..96 && config.feeBpsPerSide >= 0.0 && config.slippageBpsPerSide >= 0.0)
        if (candles.size < 80) return emptyStats(config)
        val returns = mutableListOf<Double>()
        val mfes = mutableListOf<Double>()
        val maes = mutableListOf<Double>()
        var i = 60
        while (i < candles.size - config.replayHorizonBars - 1) {
            val history = candles.take(i + 1)
            val signal = analyze("REPLAY", history, ExplosionMarketContext(
                quoteVolume24h = 10_000_000.0, spreadBps = 5.0, fundingRate = 0.0,
                btcChange24hPct = null, openInterestChangePct = null, openInterestAvailable = false
            ), config)
            if (signal == null || signal.stage == ExplosionStage.REJECTED || signal.score < scoreThreshold) { i++; continue }
            val sign = if (signal.direction == ExplosionDirection.SHORT) -1.0 else 1.0
            val entry = candles[i + 1].open
            if (entry <= 0.0) { i++; continue }
            val path = candles.subList(i + 1, i + config.replayHorizonBars + 1)
            val stop = signal.invalidationPrice
            val stopIndex = if (stop == null) -1 else path.indexOfFirst { bar ->
                if (sign > 0) bar.low <= stop else bar.high >= stop
            }
            val outcomePath = if (stopIndex >= 0) path.take(stopIndex + 1) else path
            val exit = if (stopIndex >= 0 && stop != null) {
                val stopBar = path[stopIndex]
                if (sign > 0 && stopBar.open < stop) stopBar.open
                else if (sign < 0 && stopBar.open > stop) stopBar.open
                else stop
            } else path.last().close
            val costsPct = 2.0 * (config.feeBpsPerSide + config.slippageBpsPerSide) / 100.0
            returns += sign * (exit / entry - 1.0) * 100.0 - costsPct
            mfes += if (sign > 0) (outcomePath.maxOf { it.high } / entry - 1.0) * 100.0 else (entry / outcomePath.minOf { it.low } - 1.0) * 100.0
            maes += if (sign > 0) (outcomePath.minOf { it.low } / entry - 1.0) * 100.0 else (entry / outcomePath.maxOf { it.high } - 1.0) * 100.0
            i += config.replayHorizonBars
        }
        if (returns.isEmpty()) return emptyStats(config)
        val wins = returns.count { it > 0.0 }
        val grossProfit = returns.filter { it > 0.0 }.sum()
        val grossLoss = -returns.filter { it < 0.0 }.sum()
        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        returns.forEach { r -> equity += r; peak = max(peak, equity); maxDrawdown = max(maxDrawdown, peak - equity) }
        return ExplosionReplayStats(returns.size, wins, wins.toDouble() / returns.size * 100.0,
            returns.average(), if (grossLoss > 0.0) grossProfit / grossLoss else null,
            maxDrawdown, mfes.average(), maes.average(), config.feeBpsPerSide,
            config.slippageBpsPerSide, config.replayHorizonBars)
    }

    private fun emptyStats(c: ExplosionConfig) = ExplosionReplayStats(0, 0, null, null, null, null, null, null,
        c.feeBpsPerSide, c.slippageBpsPerSide, c.replayHorizonBars)

    private fun atr(rows: List<MarketCandle>, period: Int): Double? {
        if (rows.size < period + 1) return null
        val sample = rows.takeLast(period + 1)
        val ranges = (1 until sample.size).map { i ->
            max(sample[i].high - sample[i].low, max(abs(sample[i].high - sample[i - 1].close), abs(sample[i].low - sample[i - 1].close)))
        }
        return ranges.average().takeIf { it.isFinite() && it > 0.0 }
    }
}
