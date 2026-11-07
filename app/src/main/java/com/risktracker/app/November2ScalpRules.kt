package com.risktracker.app

import kotlin.math.abs

/**
 * November 5 legacy scalp rules. Kept separate from the newer breakout/retest strategy.
 * Advisory only: this evaluator never places exchange orders.
 */
internal object November5ScalpRules {
    fun evaluate(
        h4: List<V2Candle>,
        h1: List<V2Candle>,
        m15: List<V2Candle>,
        m5: List<V2Candle>,
        micro: V2Microstructure
    ): V2TradePlan? {
        if (h4.size < 55 || h1.size < 25 || m15.size < 25 || m5.size < 25) return null
        val now = System.currentTimeMillis()
        if (!fresh(h4.last().closeTime, now, 8 * 60 * 60_000L) ||
            !fresh(h1.last().closeTime, now, 2 * 60 * 60_000L) ||
            !fresh(m15.last().closeTime, now, 30 * 60_000L) ||
            !fresh(m5.last().closeTime, now, 15 * 60_000L)) return null
        if (listOf(h4, h1, m15, m5).any { rows ->
                rows.any { !it.close.isFinite() || !it.high.isFinite() || !it.low.isFinite() ||
                    !it.volume.isFinite() || it.close <= 0.0 || it.high < it.low || it.volume < 0.0 }
            }) return null

        val h4Close = h4.map { it.close }
        val h1Close = h1.map { it.close }
        val m15Close = m15.map { it.close }
        val e15_9 = ema(m15Close, 9) ?: return null
        val e15_21 = ema(m15Close, 21) ?: return null
        val e4_9 = ema(h4Close, 9) ?: return null
        val e4_21 = ema(h4Close, 21) ?: return null
        val e4_50 = ema(h4Close, 50) ?: return null
        val e1_9 = ema(h1Close, 9) ?: return null
        val e1_21 = ema(h1Close, 21) ?: return null
        val longTrend = h4.last().close > e4_50 && e4_9 > e4_21 && e4_21 > e4_50 &&
            h1.last().close > e1_21 && e1_9 > e1_21 && m15.last().close > e15_21 && e15_9 > e15_21
        val shortTrend = h4.last().close < e4_50 && e4_9 < e4_21 && e4_21 < e4_50 &&
            h1.last().close < e1_21 && e1_9 < e1_21 && m15.last().close < e15_21 && e15_9 < e15_21
        if (!longTrend && !shortTrend) return null

        val longSide = longTrend
        val side = if (longSide) "LONG" else "SHORT"
        val vwapRows = m15.takeLast(24)
        val totalVolume = vwapRows.sumOf { it.volume }
        if (totalVolume <= 0.0) return null
        val vwap = vwapRows.sumOf { it.close * it.volume } / totalVolume
        val vwapConfirmed = if (longSide) m15.last().close > vwap else m15.last().close < vwap
        val priorBreakout = m15.dropLast(1).takeLast(5)
        if (priorBreakout.size < 5) return null
        val breakoutLevel = if (longSide) priorBreakout.maxOf { it.high } else priorBreakout.minOf { it.low }
        val breakoutConfirmed = if (longSide) m15.last().close > breakoutLevel else m15.last().close < breakoutLevel

        val m5Fast = ema(m5.map { it.close }, 9) ?: return null
        val m5Slow = ema(m5.map { it.close }, 21) ?: return null
        val timingConfirmed = if (longSide) m5.last().close > m5Fast && m5Fast > m5Slow
            else m5.last().close < m5Fast && m5Fast < m5Slow

        val priorVolume = m15.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (priorVolume > 0.0) m15.last().volume / priorVolume else 0.0
        val volumeConfirmed = volumeRatio >= 1.2
        val rsi = rsi(m15.map { it.close }) ?: 50.0
        val rsiPreferred = if (longSide) rsi in 42.0..68.0 else rsi in 32.0..58.0
        val rsiExtreme = if (longSide) rsi >= 70.0 else rsi <= 30.0

        val atr = atr(m15) ?: return null
        val entry = m5.last().close
        val stop = if (longSide) entry - 1.5 * atr else entry + 1.5 * atr
        val risk = abs(entry - stop)
        if (!risk.isFinite() || risk <= 0.0) return null
        val target1 = if (longSide) entry + 1.5 * risk else entry - 1.5 * risk
        val target2 = if (longSide) entry + 2.5 * risk else entry - 2.5 * risk

        val spreadOkay = micro.spreadBps == null || micro.spreadBps <= 12.0
        val imbalanceOkay = micro.bookImbalance == null ||
            (if (longSide) micro.bookImbalance >= -0.45 else micro.bookImbalance <= 0.45)
        val liquidityOkay = spreadOkay && imbalanceOkay
        val fundingAdverse = micro.fundingRate?.let { if (longSide) it > 0.001 else it < -0.001 } ?: false

        var score = 50
        score += 20 // aligned 4H and 1H EMA structure
        if (vwapConfirmed) score += 10
        if (breakoutConfirmed) score += 10
        if (timingConfirmed) score += 10
        if (volumeConfirmed) score += 10
        if (rsiPreferred) score += 5
        if (liquidityOkay) score += 5
        score = score.coerceAtMost(100)

        val triggered = vwapConfirmed && breakoutConfirmed && timingConfirmed && volumeConfirmed && liquidityOkay
        val warnings = mutableListOf<String>()
        if (!vwapConfirmed) warnings += "Waiting for 15M VWAP confirmation."
        if (!breakoutConfirmed) warnings += "Waiting for 15M close beyond the previous five-candle breakout level."
        if (!timingConfirmed) warnings += "Waiting for 5M EMA9/21 confirmation."
        if (!volumeConfirmed) warnings += "15M volume is below the 1.2× trigger."
        if (!liquidityOkay) warnings += "Spread or order-book imbalance exceeds the November 5 limit."
        if (rsiExtreme) warnings += "RSI is in the extreme zone; avoid chasing."
        else if (!rsiPreferred) warnings += "RSI is outside the preferred range."
        if (fundingAdverse) warnings += "Funding is adverse; review before entry."
        if (micro.fundingRate == null) warnings += "Funding unavailable; not used as a hard block."
        if (micro.spreadBps == null || micro.bookImbalance == null) warnings += "Some liquidity data unavailable; November 5 rules allow missing readings."
        val reasons = listOf(
            "4H EMA9/21/50, 1H EMA9/21 and 15M EMA9/21 trend alignment",
            "15M EMA9/21 alignment and VWAP direction confirmation",
            "15M close " + if (breakoutConfirmed) "broke the previous five-candle range" else "has not broken the previous five-candle range",
            "5M EMA9/21 " + if (timingConfirmed) "confirms entry momentum" else "is awaiting confirmation",
            "15M relative volume is %.2f× (1.2× trigger)".format(java.util.Locale.US, volumeRatio),
            "15M RSI is %.1f; preferred %s".format(java.util.Locale.US, rsi, if (longSide) "42–68" else "32–58"),
            "Fixed stop at 1.5× 15M ATR; targets at 1.5R and 2.5R",
            "November 5 liquidity limits: spread ≤12 bps; imbalance ±0.45 when available"
        )
        return V2TradePlan(
            side, score, entry, stop, target1, target2, triggered,
            "Multi-timeframe EMA + VWAP scalp",
            warnings.joinToString(" ").ifBlank { "November 5 scalp conditions confirmed." },
            "Daily Scalp · 4H/1H/15M EMA trend · 15M VWAP + breakout · 5M execution",
            if (longSide) "Bullish" else "Bearish",
            if (longSide) "Bullish" else "Bearish",
            if (timingConfirmed) if (longSide) "Bullish" else "Bearish" else "Mixed",
            reasons
        )
    }

    private fun fresh(time: Long, now: Long, maxAge: Long) =
        time > 0L && time <= now && now - time <= maxAge

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        values.drop(period).forEach { result = it * alpha + result * (1.0 - alpha) }
        return result.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rsi(values: List<Double>): Double? {
        if (values.size < 15) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(14)
        val gains = changes.filter { it > 0.0 }.sum() / 14.0
        val losses = -changes.filter { it < 0.0 }.sum() / 14.0
        if (losses == 0.0) return if (gains == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gains / losses)
    }

    private fun atr(rows: List<V2Candle>): Double? {
        if (rows.size < 16) return null
        val ranges = rows.zipWithNext { previous, current ->
            maxOf(current.high - current.low, abs(current.high - previous.close), abs(current.low - previous.close))
        }.takeLast(14)
        return ranges.average().takeIf { it.isFinite() && it > 0.0 }
    }
}
