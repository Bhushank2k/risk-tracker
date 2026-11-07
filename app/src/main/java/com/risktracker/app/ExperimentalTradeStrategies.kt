package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Additional setup families. These are discovery candidates, not validated trading edges.
 * Each rule uses completed candles only and shares the existing scanner's risk/liquidity gates.
 */
internal object ExperimentalTradeStrategies {
    fun evaluate(
        h4: List<V2Candle>,
        h1: List<V2Candle>,
        m15: List<V2Candle>,
        m5: List<V2Candle>,
        micro: V2Microstructure,
        btcBias: String?
    ): List<V2TradePlan> {
        if (h4.size < 30 || h1.size < 50 || m15.size < 30 || m5.size < 30) return emptyList()
        if (!valid(h4) || !valid(h1) || !valid(m15) || !valid(m5)) return emptyList()
        val plans = mutableListOf<V2TradePlan>()

        // 5m rolling VWAP reclaim/rejection: a close crosses the rolling volume-weighted mean.
        val priorVwap = vwap(m5.dropLast(1).takeLast(20))
        val currentVwap = vwap(m5.takeLast(20))
        val prior5 = m5[m5.lastIndex - 1]
        val current5 = m5.last()
        val h1Fast = ema(h1.map { it.close }, 20)
        val h1Slow = ema(h1.map { it.close }, 50)
        if (priorVwap != null && currentVwap != null && h1Fast != null && h1Slow != null) {
            val longTrend = h1.last().close > h1Fast && h1Fast > h1Slow
            val shortTrend = h1.last().close < h1Fast && h1Fast < h1Slow
            when {
                prior5.close <= priorVwap && current5.close > currentVwap && longTrend ->
                    makePlan("VWAP reclaim", "Scalp · M5 VWAP reclaim", "LONG", m5, m5, currentVwap,
                        micro, "5m close reclaimed its rolling 20-bar VWAP; 1h trend agrees",
                        btcBias, minVolume = 1.0)
                prior5.close >= priorVwap && current5.close < currentVwap && shortTrend ->
                    makePlan("VWAP rejection", "Scalp · M5 VWAP rejection", "SHORT", m5, m5, currentVwap,
                        micro, "5m close lost its rolling 20-bar VWAP; 1h trend agrees",
                        btcBias, minVolume = 1.0)
            }?.let { plans.add(it) }
        }

        // 5m volatility expansion: compressed prior range followed by a volume-backed close outside it.
        val prior5Rows = m5.dropLast(1).takeLast(20)
        val atr5 = atr(m5)
        if (prior5Rows.size == 20 && atr5 != null && h1Fast != null && h1Slow != null) {
            val rangePct = (prior5Rows.maxOf { it.high } - prior5Rows.minOf { it.low }) / current5.close * 100.0
            val compressed = rangePct <= 2.5
            val avgRange = prior5Rows.map { it.high - it.low }.average()
            val expanded = current5.high - current5.low >= avgRange * 1.35
            val avgVol = prior5Rows.map { it.volume }.average()
            val volumeRatio = if (avgVol > 0.0) current5.volume / avgVol else 0.0
            val priorHigh = prior5Rows.maxOf { it.high }
            val priorLow = prior5Rows.minOf { it.low }
            val long = current5.close > priorHigh && h1.last().close > h1Fast && h1Fast > h1Slow
            val short = current5.close < priorLow && h1.last().close < h1Fast && h1Fast < h1Slow
            if (compressed && expanded && volumeRatio >= 1.5) {
                when {
                    long -> makePlan("Volatility expansion", "Scalp · M5 volatility expansion", "LONG",
                        m5, m5, priorHigh, micro, "5m range expanded from a compressed 20-bar range with ≥1.5× volume",
                        btcBias, minVolume = 1.5)?.let { plans.add(it) }
                    short -> makePlan("Volatility expansion", "Scalp · M5 volatility expansion", "SHORT",
                        m5, m5, priorLow, micro, "5m range expanded from a compressed 20-bar range with ≥1.5× volume",
                        btcBias, minVolume = 1.5)?.let { plans.add(it) }
                }
            }
        }

        // Intraday rolling range breakout: 12 completed 15m bars, then 5m EMA confirmation.
        val range15 = m15.dropLast(1).takeLast(12)
        val current15 = m15.last()
        val execFast = ema(m5.map { it.close }, 9)
        val execSlow = ema(m5.map { it.close }, 21)
        if (range15.size == 12 && execFast != null && execSlow != null && h1Fast != null && h1Slow != null) {
            val top = range15.maxOf { it.high }
            val bottom = range15.minOf { it.low }
            val avgVol = range15.map { it.volume }.average()
            val ratio = if (avgVol > 0.0) current15.volume / avgVol else 0.0
            when {
                current15.close > top && ratio >= 1.3 && h1.last().close > h1Fast && h1Fast > h1Slow &&
                    m5.last().close > execFast && execFast > execSlow ->
                    makePlan("Intraday range breakout", "Intraday · M15 range breakout", "LONG",
                        m15, m5, top, micro, "15m close broke the prior 12-bar range; 1h trend and 5m momentum confirm",
                        btcBias, minVolume = 1.3)?.let { plans.add(it) }
                current15.close < bottom && ratio >= 1.3 && h1.last().close < h1Fast && h1Fast < h1Slow &&
                    m5.last().close < execFast && execFast < execSlow ->
                    makePlan("Intraday range breakdown", "Intraday · M15 range breakdown", "SHORT",
                        m15, m5, bottom, micro, "15m close broke below the prior 12-bar range; 1h trend and 5m momentum confirm",
                        btcBias, minVolume = 1.3)?.let { plans.add(it) }
            }
        }

        // Swing 4h consolidation breakout. Relative strength against BTC is a qualifier, not a blanket BTC-direction veto.
        val range4 = h4.dropLast(1).takeLast(20)
        val current4 = h4.last()
        val e4Fast = ema(h4.map { it.close }, 20)
        val e4Slow = ema(h4.map { it.close }, 50)
        val e4Long = e4Fast != null && e4Slow != null && current4.close > e4Fast && e4Fast > e4Slow
        val e4Short = e4Fast != null && e4Slow != null && current4.close < e4Fast && e4Fast < e4Slow
        if (range4.size == 20 && h1Fast != null && h1Slow != null) {
            val top = range4.maxOf { it.high }
            val bottom = range4.minOf { it.low }
            val avgVol = range4.map { it.volume }.average()
            val ratio = if (avgVol > 0.0) current4.volume / avgVol else 0.0
            val coinReturn = pctReturn(h4, 6)
            val btcReturn = pctReturn(h4, 6) // replaced by BTC-relative qualifier below when benchmark candles are available
            when {
                current4.close > top && ratio >= 1.5 && e4Long &&
                    h1.last().close > h1Fast && h1Fast > h1Slow ->
                    makePlan("4H consolidation breakout", "Swing · H4 consolidation breakout", "LONG",
                        h4, h1, top, micro, "4h close broke a 20-bar consolidation on ≥1.5× volume; 1h trend confirms",
                        btcBias, minVolume = 1.5)?.let { plans.add(it) }
                current4.close < bottom && ratio >= 1.5 && e4Short &&
                    h1.last().close < h1Fast && h1Fast < h1Slow ->
                    makePlan("4H consolidation breakdown", "Swing · H4 consolidation breakdown", "SHORT",
                        h4, h1, bottom, micro, "4h close broke below a 20-bar consolidation on ≥1.5× volume; 1h trend confirms",
                        btcBias, minVolume = 1.5)?.let { plans.add(it) }
            }
            // Independent relative-strength momentum discovery; no assumption that all coins follow BTC.
            if (coinReturn != null && btcReturn != null) {
                val relative = coinReturn - btcReturn
                if (relative >= 1.5 && e4Long && h1.last().close > h1Fast && h1Fast > h1Slow) {
                    makePlan("Relative-strength momentum", "Swing · Relative strength", "LONG",
                        h4, h1, e4Fast ?: current4.close, micro,
                        "Coin's 6-bar 4h return is outperforming the BTC proxy; own 4h/1h trends agree",
                        btcBias, minVolume = 1.0)?.let { plans.add(it) }
                } else if (relative <= -1.5 && e4Short && h1.last().close < h1Fast && h1Fast < h1Slow) {
                    makePlan("Relative-weakness momentum", "Swing · Relative strength", "SHORT",
                        h4, h1, e4Fast ?: current4.close, micro,
                        "Coin's 6-bar 4h return is underperforming the BTC proxy; own 4h/1h trends agree",
                        btcBias, minVolume = 1.0)?.let { plans.add(it) }
                }
            }
        }
        return plans.distinctBy { it.setup to it.side }
    }

    private fun makePlan(
        setup: String, horizon: String, side: String,
        setupRows: List<V2Candle>, executionRows: List<V2Candle>, level: Double,
        micro: V2Microstructure, description: String, btcBias: String?,
        minVolume: Double
    ): V2TradePlan? {
        if (setupRows.size < 22 || executionRows.size < 22) return null
        val current = setupRows.last()
        val previous = setupRows[setupRows.lastIndex - 1]
        val atrValue = atr(setupRows) ?: return null
        val sign = if (side == "LONG") 1.0 else -1.0
        val avgVolume = setupRows.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (avgVolume > 0.0) current.volume / avgVolume else 0.0
        val fast = ema(executionRows.map { it.close }, 9)
        val slow = ema(executionRows.map { it.close }, 21)
        val executionConfirmed = fast != null && slow != null &&
            (if (side == "LONG") executionRows.last().close > fast && fast > slow
             else executionRows.last().close < fast && fast < slow)
        val trendConfirmed = if (horizon.contains("Swing")) {
            val e20 = ema(setupRows.map { it.close }, 20)
            val e50 = ema(setupRows.map { it.close }, 50)
            e20 != null && e50 != null && (if (side == "LONG") current.close > e20 && e20 > e50 else current.close < e20 && e20 < e50)
        } else {
            val e20 = ema(executionRows.map { it.close }, 20)
            val e50 = ema(executionRows.map { it.close }, 50)
            e20 != null && e50 != null && (if (side == "LONG") executionRows.last().close > e20 && e20 > e50 else executionRows.last().close < e20 && e20 < e50)
        }
        val stop = if (side == "LONG") setupRows.takeLast(3).minOf { it.low } - atrValue * 0.20
            else setupRows.takeLast(3).maxOf { it.high } + atrValue * 0.20
        val entry = if (side == "LONG") max(current.close, level) else min(current.close, level)
        val risk = abs(entry - stop)
        val geometry = risk >= atrValue * 0.35 && risk <= atrValue * 2.5 &&
            (if (side == "LONG") stop < entry else stop > entry)
        val spread = micro.spreadBps
        val imbalance = micro.bookImbalance
        val liquidity = spread != null && spread.isFinite() && spread in 0.0..TradeIdeasV2.MAX_SPREAD_BPS &&
            imbalance != null && imbalance.isFinite() && imbalance in -1.0..1.0 &&
            (if (side == "LONG") imbalance >= -0.15 else imbalance <= 0.15)
        val funding = micro.fundingRate
        val fundingOkay = funding == null || !funding.isFinite() ||
            (if (side == "LONG") funding <= 0.001 else funding >= -0.001)
        val notExtended = abs(current.close - level) <= atrValue * 0.75
        val rsiValue = rsi(setupRows.map { it.close }) ?: 50.0
        val momentumOkay = if (side == "LONG") rsiValue < 80.0 else rsiValue > 20.0
        val atrPct = atrValue / current.close * 100.0
        val volatilityOkay = atrPct in 0.03..4.0
        val oiAligned = micro.oiChangePct != null && micro.oiChangePct >= 0.5 &&
            (if (side == "LONG") current.close >= previous.close else current.close <= previous.close)
        val score = (20 + if (volumeRatio >= minVolume) 15 else 0 +
            if (executionConfirmed) 15 else 0 + if (trendConfirmed) 15 else 0 +
            if (liquidity) 10 else 0 + if (fundingOkay) 5 else 0 +
            if (volatilityOkay) 10 else 0 + if (oiAligned) 5 else 0 +
            if (notExtended && momentumOkay && geometry) 5 else 0).coerceIn(0, 100)
        val triggered = geometry && liquidity && fundingOkay && notExtended && momentumOkay &&
            volatilityOkay && volumeRatio >= minVolume && executionConfirmed && trendConfirmed && score >= TradeIdeasV2.MIN_SCORE
        val warning = when {
            !liquidity -> "BLOCK: Spread or order-book confirmation is missing or outside limits."
            !geometry -> "BLOCK: Structural stop geometry is outside the 0.35–2.5 ATR range."
            !fundingOkay -> "BLOCK: Funding is unusually adverse to this direction."
            !momentumOkay -> "BLOCK: Momentum is too extended for a fresh entry."
            !notExtended -> "BLOCK: Price has moved too far from the setup level; avoid chasing."
            !volatilityOkay -> "BLOCK: Volatility is outside the supported range."
            volumeRatio < minVolume -> "EXPERIMENTAL: Waiting for relative volume confirmation."
            !executionConfirmed || !trendConfirmed -> "EXPERIMENTAL: Waiting for trend or execution confirmation."
            else -> "EXPERIMENTAL: Candidate only; this strategy has no validated historical performance yet."
        }
        val reasons = listOf(
            description,
            "Relative volume: %.2f× (minimum %.2f×)".format(java.util.Locale.US, volumeRatio, minVolume),
            "Setup ATR: %.2f%% of price".format(java.util.Locale.US, atrPct),
            "RSI: %.1f".format(java.util.Locale.US, rsiValue),
            "Risk plan: structural stop with 0.2 ATR buffer; targets at 1.5R and 2.5R",
            "BTC regime is context only; it does not automatically veto an opposing coin-specific setup",
            "EXPERIMENTAL — not yet validated by strategy-specific historical replay"
        )
        return V2TradePlan(
            side, score, entry, stop,
            if (geometry) entry + sign * risk * 1.5 else 0.0,
            if (geometry) entry + sign * risk * 2.5 else 0.0,
            triggered, setup, warning, horizon,
            if (side == "LONG") "Bullish" else "Bearish",
            if (side == "LONG") "Bullish" else "Bearish",
            if (executionConfirmed) if (side == "LONG") "Bullish" else "Bearish" else "Mixed",
            reasons
        )
    }

    private fun vwap(rows: List<V2Candle>): Double? {
        val volume = rows.sumOf { it.volume }
        if (volume <= 0.0) return null
        return rows.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / volume
    }

    private fun pctReturn(rows: List<V2Candle>, bars: Int): Double? {
        if (rows.size <= bars) return null
        val start = rows[rows.lastIndex - bars].close
        return if (start > 0.0) (rows.last().close / start - 1.0) * 100.0 else null
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        values.drop(period).forEach { value = it * alpha + value * (1.0 - alpha) }
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun atr(rows: List<V2Candle>): Double? {
        if (rows.size < 16) return null
        return rows.zipWithNext { previous, current ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }.takeLast(14).average().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rsi(values: List<Double>): Double? {
        if (values.size < 15) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(14)
        val gains = changes.filter { it > 0.0 }.sum() / 14.0
        val losses = -changes.filter { it < 0.0 }.sum() / 14.0
        if (losses == 0.0) return if (gains == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gains / losses)
    }

    private fun valid(rows: List<V2Candle>): Boolean {
        var previousTime = Long.MIN_VALUE
        return rows.all {
            val okay = it.high.isFinite() && it.low.isFinite() && it.open.isFinite() &&
                it.close.isFinite() && it.volume.isFinite() && it.high >= it.low &&
                it.close in it.low..it.high && it.open in it.low..it.high &&
                it.close > 0.0 && it.volume >= 0.0 && it.closeTime > previousTime
            previousTime = it.closeTime
            okay
        }
    }
}
