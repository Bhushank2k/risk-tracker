package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class V2Candle(
    val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long, val open: Double = close
)
internal data class V2Microstructure(val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double? = null, val oiChangePct: Double? = null)

internal data class V2TradePlan(
    val side: String,
    val score: Int,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val entryTriggered: Boolean,
    val setup: String,
    val warning: String,
    val horizon: String,
    val bias4h: String,
    val bias1h: String,
    val bias5m: String,
    val reasons: List<String>
)

/**
 * Advisory-only strategy rules. It never submits, edits, or cancels exchange orders.
 * Scalping uses a 15m breakout/retest with 5m confirmation; Swing uses 1h setup with 15m confirmation.
 */
internal object TradeIdeasV2 {
    const val RISK_PERCENT = 0.5
    const val MAX_LEVERAGE = 3.0
    const val MAX_SPREAD_BPS = 10.0
    const val MIN_SCORE = 75
    private const val MIN_QUOTE_VOLUME = 5_000_000.0

    fun evaluate(
        h4: List<V2Candle>,
        h1: List<V2Candle>,
        m15: List<V2Candle>,
        m5: List<V2Candle>,
        micro: V2Microstructure,
        quoteVolume: Double,
        changePct: Double,
        swingMode: Boolean = false,
        btcBias: String? = null
    ): V2TradePlan? {
        val setupCandles = if (swingMode) h1 else m15
        val executionCandles = if (swingMode) m15 else m5
        if (h4.size < 200 || h1.size < 50 || setupCandles.size < 40 || executionCandles.size < 25) return null
        if (!validCandles(h4) || !validCandles(h1) || !validCandles(m15) || !validCandles(m5)) return null
        val now = System.currentTimeMillis()
        // Reject stale or future-dated bars on every timeframe, not only execution frames.
        if (!isFresh(h4.last().closeTime, now, 8 * 60 * 60_000L) ||
            !isFresh(h1.last().closeTime, now, 2 * 60 * 60_000L) ||
            !isFresh(setupCandles.last().closeTime, now, if (swingMode) 2 * 60 * 60_000L else 30 * 60_000L) ||
            !isFresh(executionCandles.last().closeTime, now, if (swingMode) 30 * 60_000L else 15 * 60_000L)) return null
        if (quoteVolume < MIN_QUOTE_VOLUME || !quoteVolume.isFinite()) return null

        val e4_20 = ema(h4.map { it.close }, 20) ?: return null
        val e4_50 = ema(h4.map { it.close }, 50) ?: return null
        val e4_200 = ema(h4.map { it.close }, 200) ?: return null
        val e1_20 = ema(h1.map { it.close }, 20) ?: return null
        val e1_50 = ema(h1.map { it.close }, 50) ?: return null
        val executionFast = ema(executionCandles.map { it.close }, 9) ?: return null
        val executionSlow = ema(executionCandles.map { it.close }, 21) ?: return null
        val current = setupCandles.last()
        val previous = setupCandles[setupCandles.lastIndex - 1]
        val atr = atr(setupCandles) ?: return null
        val e1_200 = ema(h1.map { it.close }, 200) ?: return null
        val longBias = if (swingMode) {
            h4.last().close > e4_200 && e4_20 > e4_50 && e4_50 > e4_200 &&
                h1.last().close > e1_50 && e1_20 > e1_50
        } else h1.last().close > e1_200 && e1_20 > e1_50 && e1_50 > e1_200
        val shortBias = if (swingMode) {
            h4.last().close < e4_200 && e4_20 < e4_50 && e4_50 < e4_200 &&
                h1.last().close < e1_50 && e1_20 < e1_50
        } else h1.last().close < e1_200 && e1_20 < e1_50 && e1_50 < e1_200
        val setupTf = if (swingMode) "1H" else "15M"
        val executionTf = if (swingMode) "15M" else "5M"
        val strategyHorizon = if (swingMode) "Swing · H1 setup / M15 execution" else "Scalp · M15 setup / M5 execution"
        if (!longBias && !shortBias) return V2TradePlan(
            "WAIT", 0, current.close, 0.0, 0.0, 0.0, false, "Higher-timeframe disagreement",
            "BLOCK: trend filters do not agree.", strategyHorizon,
            if (h4.last().close >= e4_200) "Bullish" else "Bearish",
            if (h1.last().close >= e1_50) "Bullish" else "Bearish",
            if (executionCandles.last().close >= executionFast) "Bullish" else "Bearish",
            listOf(if (swingMode) "Require aligned 4H and 1H EMA trends" else "Require aligned 1H EMA20/50/200 trend")
        )
        val side = if (longBias) "LONG" else "SHORT"
        val sign = if (longBias) 1.0 else -1.0
        val level = if (longBias) setupCandles.dropLast(1).takeLast(20).maxOf { it.high }
            else setupCandles.dropLast(1).takeLast(20).minOf { it.low }
        var breakoutIndex = -1
        val first = (setupCandles.lastIndex - 4).coerceAtLeast(20)
        for (index in first until setupCandles.lastIndex) {
            val history = setupCandles.subList(index - 20, index)
            val breakoutLevel = if (longBias) history.maxOf { it.high } else history.minOf { it.low }
            val avgVolume = history.takeLast(20).map { it.volume }.average()
            val ratio = if (avgVolume > 0.0) setupCandles[index].volume / avgVolume else 0.0
            val broke = if (longBias) setupCandles[index].close > breakoutLevel else setupCandles[index].close < breakoutLevel
            if (broke && ratio >= 1.5) breakoutIndex = index
        }
        val breakoutLevel = if (breakoutIndex >= 0) {
            val history = setupCandles.subList(breakoutIndex - 20, breakoutIndex)
            if (longBias) history.maxOf { it.high } else history.minOf { it.low }
        } else level
        val setupEma20 = ema(setupCandles.map { it.close }, 20) ?: return null
        val pullbackTouched = if (longBias) current.low <= setupEma20 + atr * 0.20 && current.low >= setupEma20 - atr * 0.60
            else current.high >= setupEma20 - atr * 0.20 && current.high <= setupEma20 + atr * 0.60
        val pullbackHeld = if (longBias) current.close > setupEma20 && current.close > previous.close
            else current.close < setupEma20 && current.close < previous.close
        val sweepHistory = setupCandles.dropLast(1).takeLast(10)
        val sweepLevel = if (longBias) sweepHistory.minOf { it.low } else sweepHistory.maxOf { it.high }
        val candleBody = abs(current.close - current.open).coerceAtLeast(atr * 0.02)
        val sweptAndReclaimed = if (longBias) {
            current.low < sweepLevel && current.close > sweepLevel && (min(current.close, current.open) - current.low) >= candleBody * 0.8
        } else {
            current.high > sweepLevel && current.close < sweepLevel && (current.high - max(current.close, current.open)) >= candleBody * 0.8
        }
        val retestLevel = when {
            breakoutIndex >= 0 -> breakoutLevel
            pullbackTouched -> setupEma20
            sweptAndReclaimed -> sweepLevel
            else -> setupEma20
        }
        val strategySetup = when {
            breakoutIndex >= 0 -> "Breakout retest"
            pullbackTouched && pullbackHeld -> "Trend pullback"
            sweptAndReclaimed -> "Liquidity sweep reclaim"
            else -> "Trend pullback watch"
        }
        val patternDetected = breakoutIndex >= 0 || (pullbackTouched && pullbackHeld) || sweptAndReclaimed
        val retestTouched = when {
            breakoutIndex >= 0 -> if (longBias) current.low <= retestLevel + atr * 0.25 else current.high >= retestLevel - atr * 0.25
            pullbackTouched -> pullbackTouched
            else -> sweptAndReclaimed
        }
        val retestHeld = when {
            breakoutIndex >= 0 -> if (longBias) current.close >= retestLevel && current.close >= previous.close
                else current.close <= retestLevel && current.close <= previous.close
            pullbackTouched -> pullbackHeld
            else -> sweptAndReclaimed
        }
        val breakoutInvalidated = breakoutIndex >= 0 && setupCandles.subList(breakoutIndex + 1, setupCandles.lastIndex).any {
            if (longBias) it.close < retestLevel - atr * 0.25 else it.close > retestLevel + atr * 0.25
        }
        val executionConfirmed = if (longBias) executionCandles.last().close > executionFast && executionFast > executionSlow
            else executionCandles.last().close < executionFast && executionFast < executionSlow
        val averageSetupVolume = setupCandles.dropLast(1).takeLast(20).map { it.volume }.average()
        val setupRelativeVolume = if (averageSetupVolume > 0.0) current.volume / averageSetupVolume else 0.0
        val setupRsi = rsi(setupCandles.map { it.close }) ?: return null
        val notOverboughtOrOversold = if (longBias) setupRsi < 78.0 else setupRsi > 22.0
        val notOverextended = abs(current.close - retestLevel) <= atr * 0.75
        val funding = micro.fundingRate
        // Funding is expressed as a fraction (0.001 = 0.1%). Missing funding is disclosed,
        // but does not by itself invalidate otherwise complete public market data.
        val fundingOkay = funding == null || !funding.isFinite() ||
            (if (longBias) funding <= 0.001 else funding >= -0.001)
        val btcAligned = btcBias.isNullOrBlank() || btcBias == "Neutral" ||
            (if (longBias) btcBias == "Bullish" else btcBias == "Bearish")
        val minRelativeVolume = if (breakoutIndex >= 0) 1.5 else 1.0
        val oiChange = micro.oiChangePct?.takeIf { it.isFinite() }
        val oiAligned = oiChange != null && oiChange >= 0.5 &&
            (if (longBias) current.close >= previous.close else current.close <= previous.close)
        val triggered = patternDetected && !breakoutInvalidated && retestTouched && retestHeld &&
            executionConfirmed && setupRelativeVolume >= minRelativeVolume &&
            notOverboughtOrOversold && notOverextended && fundingOkay && btcAligned

        val structuralStop = if (longBias) setupCandles.takeLast(3).minOf { it.low } - atr * 0.20
            else setupCandles.takeLast(3).maxOf { it.high } + atr * 0.20
        val entry = if (triggered) current.close else retestLevel
        val stop = structuralStop
        val risk = abs(entry - stop)
        val validGeometry = risk >= atr * 0.35 && risk <= atr * 2.5 &&
            (if (longBias) stop < entry else stop > entry)
        val target1 = entry + sign * risk * 1.5
        val target2 = entry + sign * risk * 2.5
        val spread = micro.spreadBps
        val imbalance = micro.bookImbalance
        val liquidityOkay = spread != null && spread.isFinite() && spread in 0.0..MAX_SPREAD_BPS &&
            imbalance != null && imbalance.isFinite() && imbalance in -1.0..1.0 &&
            (if (longBias) imbalance >= -0.15 else imbalance <= 0.15)
        val atrPct = atr / current.close * 100.0
        val minAtrPct = if (swingMode) 0.25 else 0.12
        val maxAtrPct = if (swingMode) 3.0 else 1.5
        val volatilityScore = when {
            atrPct in minAtrPct..maxAtrPct -> 15
            atrPct > maxAtrPct -> 7
            else -> 0
        }
        val trendScore = 20
        val patternScore = if (patternDetected && !breakoutInvalidated) 20 else 0
        val retestScore = if (retestTouched && retestHeld && !breakoutInvalidated) 15 else 0
        val volumeScore = when { setupRelativeVolume >= 1.5 -> 10; setupRelativeVolume >= 1.0 -> 5; else -> 0 }
        val timingScore = if (executionConfirmed) 10 else 0
        val liquidityScore = if (liquidityOkay) 5 else 0
        val oiScore = if (oiAligned) 5 else 0
        val score = (trendScore + volatilityScore + patternScore + retestScore + volumeScore + timingScore + liquidityScore + oiScore).coerceIn(0, 100)
        val reasons = mutableListOf(
            if (swingMode) "4H EMA20/50/200 and 1H EMA20/50 trend alignment" else "1H EMA20/50/200 trend alignment",
            when {
                breakoutIndex >= 0 -> "${setupTf} breakout closed beyond the prior 20-bar level on at least 1.5× volume"
                pullbackTouched && pullbackHeld -> "${setupTf} trend pullback reclaimed EMA20 with a directional close"
                sweptAndReclaimed -> "${setupTf} liquidity sweep reclaimed the prior 10-bar extreme"
                else -> "Waiting for breakout, EMA20 pullback or liquidity sweep confirmation"
            },
            if (retestTouched && retestHeld) "${setupTf} retest held the breakout level" else "Retest has not yet confirmed",
            if (executionConfirmed) "${executionTf} EMA9/21 momentum confirms execution" else "Waiting for ${executionTf} EMA9/21 confirmation",
            "Risk model: 0.5% equity, maximum 3× leverage, structural stop plus 0.2 ATR buffer",
            "${setupTf} ATR is " + "%.2f%%".format(java.util.Locale.US, atrPct) + " of price",
            "${setupTf} relative volume is " + "%.2f×".format(java.util.Locale.US, setupRelativeVolume) + " versus the prior 20 completed candles",
            "${setupTf} RSI " + "%.1f".format(java.util.Locale.US, setupRsi) +
                (if (notOverboughtOrOversold) " is within the anti-chase range" else " blocks an overbought/oversold entry"),
            "BTC market regime: " + (btcBias ?: "Unavailable")
        )
        if (liquidityOkay) reasons.add("Spread and order-book depth passed hard liquidity gates (spread ≤ 10 bps)")
        if (funding == null || !funding.isFinite()) reasons.add("Funding unavailable; no funding-based directional edge assumed")
        else reasons.add("Funding rate " + "%+.4f%%".format(java.util.Locale.US, funding * 100.0))
        if (oiChange == null) reasons.add("Open-interest confirmation unavailable; no OI score added")
        else reasons.add("Open interest changed " + "%+.2f%%".format(java.util.Locale.US, oiChange) + (if (oiAligned) " with directional price confirmation (+5 score)" else " without directional confirmation"))
        val warning = when {
            spread == null || imbalance == null -> "BLOCK: Spread or order-book depth is unavailable; missing liquidity data never passes."
            !liquidityOkay -> "BLOCK: Spread exceeds 10 bps or order-book imbalance is outside the allowed side threshold."
            breakoutInvalidated -> "INVALIDATED: Price closed back through the breakout level; wait for a fresh setup."
            !btcAligned -> "BLOCK: BTC market regime is opposed to this trade direction."
            setupRelativeVolume < 1.5 -> "BLOCK: Relative volume is below 1.5× the prior 20 completed candles."
            atrPct < minAtrPct -> "BLOCK: ATR% is too low; insufficient movement for this strategy."
            !validGeometry -> "BLOCK: Structural stop distance is invalid or outside 0.35–2.5 ATR."
            !fundingOkay -> "BLOCK: Extreme funding is adverse to this direction."
            !notOverboughtOrOversold -> "BLOCK: Momentum is extended; avoid chasing an overbought/oversold entry."
            !notOverextended -> "BLOCK: Price is too extended from the breakout retest level."
            triggered && score < MIN_SCORE -> "BLOCK: Setup score is below the 75/100 entry threshold."
            !triggered -> "Conditional only: wait for breakout, retest hold, volume and $executionTf confirmation."
            else -> (if (score >= 85) "HIGH QUALITY: Entry conditions confirmed. " else "VALID SETUP: Entry conditions confirmed. ") +
                "Backtest estimates are historical only; account for fees, funding and slippage."
        }
        val finalTriggered = triggered && liquidityOkay && validGeometry && fundingOkay &&
            notOverboughtOrOversold && notOverextended && !breakoutInvalidated && btcAligned &&
            setupRelativeVolume >= minRelativeVolume && atrPct >= minAtrPct && score >= MIN_SCORE
        return V2TradePlan(
            side, score, entry, stop,
            if (validGeometry) target1 else 0.0, if (validGeometry) target2 else 0.0,
            finalTriggered, strategySetup,
            warning, strategyHorizon,
            "Bullish".takeIf { longBias } ?: "Bearish",
            "Bullish".takeIf { longBias } ?: "Bearish",
            if (executionConfirmed) if (longBias) "Bullish" else "Bearish" else "Mixed",
            reasons
        )
    }

    /** Causal 15m replay with 5m execution confirmation and a 24h maximum holding window. */
    fun backtest(h4: List<V2Candle>, h1: List<V2Candle>, m15: List<V2Candle>, m5: List<V2Candle>): TradeIdeaBacktestStats {
        if (m15.size < 80 || h4.size < 200 || h1.size < 50 || m5.size < 100) return TradeIdeaBacktestStats()
        if (!validCandles(h4) || !validCandles(h1) || !validCandles(m15) || !validCandles(m5)) return TradeIdeaBacktestStats()
        val outcomes = mutableListOf<Double>()
        var i = 25
        while (i < m15.lastIndex - 1) {
            val time = m15[i].closeTime
            val four = h4.filter { it.closeTime <= time }
            val one = h1.filter { it.closeTime <= time }
            if (four.size < 200 || one.size < 50) { i++; continue }
            val f4 = four.map { it.close }
            val f1 = one.map { it.close }
            val e20 = ema(f4, 20); val e50 = ema(f4, 50); val e200 = ema(f4, 200)
            val e1_20 = ema(f1, 20); val e1_50 = ema(f1, 50)
            if (e20 == null || e50 == null || e200 == null || e1_20 == null || e1_50 == null) { i++; continue }
            val long = four.last().close > e200 && e20 > e50 && e50 > e200 &&
                one.last().close > e1_50 && e1_20 > e1_50
            val short = four.last().close < e200 && e20 < e50 && e50 < e200 &&
                one.last().close < e1_50 && e1_20 < e1_50
            if (!long && !short) { i++; continue }
            val sign = if (long) 1.0 else -1.0
            val history = m15.subList(i - 20, i)
            val level = if (long) history.maxOf { it.high } else history.minOf { it.low }
            val breakout = if (long) m15[i].close > level else m15[i].close < level
            val avg = history.map { it.volume }.average()
            if (!breakout || avg <= 0.0 || m15[i].volume / avg < 1.5) { i++; continue }
            val retest = m15[i + 1]
            val atrValue = atr(m15.subList(0, i + 2))
            if (atrValue == null) { i++; continue }
            val atr = atrValue
            val touched = if (long) retest.low <= level + atr * 0.25 else retest.high >= level - atr * 0.25
            val held = if (long) retest.close >= level && retest.close >= m15[i].close
                else retest.close <= level && retest.close <= m15[i].close
            val executionBars = m5.filter { it.closeTime <= retest.closeTime }
            val executionCloses = executionBars.map { it.close }
            val executionFast = ema(executionCloses, 9)
            val executionSlow = ema(executionCloses, 21)
            val executionConfirmed = executionBars.isNotEmpty() &&
                retest.closeTime - executionBars.last().closeTime <= 10 * 60_000L &&
                executionFast != null && executionSlow != null &&
                (if (long) executionBars.last().close > executionFast && executionFast > executionSlow
                else executionBars.last().close < executionFast && executionFast < executionSlow)
            if (!touched || !held || !executionConfirmed) { i++; continue }
            val entry = retest.close
            val stop = if (long) min(retest.low, m15[i].low) - atr * 0.20
                else max(retest.high, m15[i].high) + atr * 0.20
            val risk = abs(entry - stop)
            if (risk < atr * 0.35 || risk > atr * 2.5) { i++; continue }
            val t1 = entry + sign * risk * 1.5
            val t2 = entry + sign * risk * 2.5
            var partial = false
            var result = -1.0
            var exitAt = -1
            val maxExit = min(i + 2 + 96, m15.size)
            for (j in i + 2 until maxExit) {
                val c = m15[j]
                val stopHit = if (long) c.low <= stop else c.high >= stop
                val t1Hit = if (long) c.high >= t1 else c.low <= t1
                val t2Hit = if (long) c.high >= t2 else c.low <= t2
                if (stopHit) { result = if (partial) 0.25 else -1.0; exitAt = j; break }
                if (t2Hit) { result = 2.0; exitAt = j; break }
                if (t1Hit && !partial) { partial = true; result = 0.75 }
            }
            if (exitAt >= 0) {
                outcomes.add(result)
                i = exitAt + 1
            } else if (maxExit - 1 < m15.lastIndex) {
                // Time stop: close any remaining position after 24h instead of silently
                // dropping unresolved trades (which biases the historical sample).
                val finalClose = m15[maxExit - 1].close
                val openR = (finalClose - entry) / risk * sign
                outcomes.add(if (partial) 0.75 + openR * 0.5 else openR)
                i = maxExit
            } else i++
        }
        return if (outcomes.isEmpty()) TradeIdeaBacktestStats()
        else TradeIdeaBacktestStats(outcomes.size, outcomes.count { it > 0.0 }, outcomes.average())
    }

    private fun validCandles(rows: List<V2Candle>): Boolean {
        if (rows.size < 2) return false
        var previousTime = Long.MIN_VALUE
        return rows.all { candle ->
            val valid = candle.high.isFinite() && candle.low.isFinite() &&
                candle.close.isFinite() && candle.volume.isFinite() &&
                candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
                candle.high >= candle.low && candle.close in candle.low..candle.high &&
                candle.volume >= 0.0 && candle.closeTime > previousTime
            previousTime = candle.closeTime
            valid
        }
    }

    private fun isFresh(closeTime: Long, now: Long, maxAge: Long): Boolean =
        closeTime <= now + 60_000L && now - closeTime <= maxAge

    private fun rsi(values: List<Double>): Double? {
        if (values.size < 15) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(14)
        val gain = changes.filter { it > 0.0 }.sum() / 14.0
        val loss = -changes.filter { it < 0.0 }.sum() / 14.0
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return (100.0 - 100.0 / (1.0 + gain / loss)).takeIf { it.isFinite() }
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        values.drop(period).forEach { result = it * alpha + result * (1.0 - alpha) }
        return result.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun atr(rows: List<V2Candle>): Double? {
        if (rows.size < 16) return null
        val trueRanges = rows.zipWithNext { prev, current ->
            max(current.high - current.low, max(abs(current.high - prev.close), abs(current.low - prev.close)))
        }.takeLast(14)
        return trueRanges.average().takeIf { it.isFinite() && it > 0.0 }
    }
}
