package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class V2Candle(
    val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long, val open: Double = close
)
internal data class V2Microstructure(val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double? = null, val oiChangePct: Double? = null)

internal enum class TradeIdeaRiskCode(val blocksEntry: Boolean) {
    LOW_LIQUIDITY(true),
    INVALID_STOP(true),
    INSUFFICIENT_REWARD_RISK(true),
    ADVERSE_FUNDING(true),
    VOLATILITY_OUT_OF_RANGE(true),
    EXTENDED_ENTRY(true),
    LOW_RELATIVE_VOLUME(true),
    MOMENTUM_NOT_CONFIRMED(true),
    LOW_CONFLUENCE_SCORE(true),
    STALE_MARKET_DATA(true),
    WAITING_FOR_ENTRY_CONFIRMATION(false),
    BELOW_READY_SCORE_THRESHOLD(false),
    COSTS_NOT_INCLUDED(false),
    LEGACY_BLOCKING_WARNING(true)
}

internal fun isTradeIdeaRiskValid(
    side: String,
    entry: Double,
    stop: Double,
    target1: Double,
    target2: Double,
    riskCodes: Set<TradeIdeaRiskCode>
): Boolean {
    if (side !in setOf("LONG", "SHORT")) return false
    if (!entry.isFinite() || !stop.isFinite() || !target1.isFinite() || !target2.isFinite()) return false
    if (entry <= 0.0 || stop <= 0.0 || target1 <= 0.0 || target2 <= 0.0) return false
    val geometryValid = if (side == "LONG") {
        stop < entry && target1 > entry && target2 > target1
    } else {
        stop > entry && target1 < entry && target2 < target1
    }
    val riskDistance = abs(entry - stop)
    val rewardRisk = if (riskDistance > 0.0) abs(target1 - entry) / riskDistance else 0.0
    return geometryValid && rewardRisk >= 2.0 && riskCodes.none { it.blocksEntry }
}

/** True only when the full forward holding window is present in the candle sample. */
internal fun hasCompleteTradeHorizon(setupIndex: Int, candleCount: Int, maxHoldingBars: Int): Boolean =
    setupIndex >= 0 && candleCount > 0 && maxHoldingBars > 0 &&
        setupIndex.toLong() + 1L + maxHoldingBars.toLong() < candleCount.toLong()

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
    val reasons: List<String>,
    val riskCodes: Set<TradeIdeaRiskCode> = emptySet()
)

/**
 * Advisory-only strategy rules. It never submits, edits, or cancels exchange orders.
 * Strategy selection and confluence scoring are owned by ConfluenceScannerV4.
 */
internal fun tradeIdeaNetReturnR(
    grossR: Double, entry: Double, riskDistance: Double, heldBars: Int, setupBarMinutes: Int,
    roundTripFeeBps: Double = 8.0, slippageBps: Double = 4.0, fundingBpsPer8h: Double = 1.0
): Double {
    if (!grossR.isFinite() || !entry.isFinite() || !riskDistance.isFinite() || entry <= 0.0 || riskDistance <= 0.0 ||
        heldBars < 0 || setupBarMinutes <= 0 ||
        listOf(roundTripFeeBps, slippageBps, fundingBpsPer8h).any { !it.isFinite() || it < 0.0 }) return Double.NaN
    val holdingHours = heldBars.toDouble() * setupBarMinutes / 60.0
    val costBps = roundTripFeeBps + slippageBps + fundingBpsPer8h * (holdingHours / 8.0)
    return grossR - entry * costBps / 10_000.0 / riskDistance
}

internal object TradeIdeasV2 {
    const val RISK_PERCENT = 0.5
    const val MAX_LEVERAGE = 3.0
    const val MAX_SPREAD_BPS = 10.0
    const val MIN_SCORE = 70
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
    ): V2TradePlan? = ConfluenceScannerV4.evaluate(h4, h1, m15, m5, micro, quoteVolume, btcBias)

    /** Causal breakout/retest replay. Inputs are horizon-specific higher, setup and execution candles. */
    fun backtest(
        h4: List<V2Candle>, h1: List<V2Candle>, m15: List<V2Candle>, m5: List<V2Candle>,
        maxHoldingBars: Int = 96, setupBarMinutes: Int = 15, executionBarMinutes: Int = 5,
        roundTripFeeBps: Double = 8.0, slippageBps: Double = 4.0, fundingBpsPer8h: Double = 1.0
    ): TradeIdeaBacktestStats {
        if (m15.size < 80 || h4.size < 200 || h1.size < 50 || m5.size < 100 || maxHoldingBars <= 0 || setupBarMinutes <= 0 || executionBarMinutes <= 0) return TradeIdeaBacktestStats()
        if (!validCandles(h4) || !validCandles(h1) || !validCandles(m15) || !validCandles(m5)) return TradeIdeaBacktestStats()
        if (listOf(roundTripFeeBps, slippageBps, fundingBpsPer8h).any { !it.isFinite() || it < 0.0 }) return TradeIdeaBacktestStats()
        val outcomes = mutableListOf<Double>()
        var i = 25
        // Exclude late setups unless every candle in their full holding window is available.
        while (hasCompleteTradeHorizon(i, m15.size, maxHoldingBars)) {
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
                retest.closeTime - executionBars.last().closeTime <= maxOf(10, executionBarMinutes * 2) * 60_000L &&
                executionFast != null && executionSlow != null &&
                (if (long) executionBars.last().close > executionFast && executionFast > executionSlow
                else executionBars.last().close < executionFast && executionFast < executionSlow)
            if (!touched || !held || !executionConfirmed) { i++; continue }
            val entry = retest.close
            val stop = if (long) min(retest.low, m15[i].low) - atr * 0.20
                else max(retest.high, m15[i].high) + atr * 0.20
            val risk = abs(entry - stop)
            if (risk < atr * 0.35 || risk > atr * 2.5) { i++; continue }
            val t1 = entry + sign * risk * 2.0
            val t2 = entry + sign * risk * 3.0
            var partial = false
            var result = -1.0
            var exitAt = -1
            val maxExit = min(i + 2 + maxHoldingBars, m15.size)
            for (j in i + 2 until maxExit) {
                val c = m15[j]
                // Match the paper plan: after the first scale-out, the remaining stop moves to breakeven.
                val activeStop = if (partial) entry else stop
                val stopHit = if (long) c.low <= activeStop else c.high >= activeStop
                val t1Hit = if (long) c.high >= t1 else c.low <= t1
                val t2Hit = if (long) c.high >= t2 else c.low <= t2
                if (stopHit) { result = if (partial) 1.0 else -1.0; exitAt = j; break }
                if (t2Hit) { result = 2.5; exitAt = j; break }
                if (t1Hit && !partial) { partial = true; result = 1.0 }
            }
            if (exitAt >= 0) {
                val heldBars = (exitAt - (i + 1)).coerceAtLeast(0)
                outcomes.add(tradeIdeaNetReturnR(result, entry, risk, heldBars, setupBarMinutes, roundTripFeeBps, slippageBps, fundingBpsPer8h))
                i = exitAt + 1
            } else if (maxExit - 1 <= m15.lastIndex) {
                // Horizon-specific time stop: mark remaining exposure at the final available candle.
                val finalClose = m15[maxExit - 1].close
                val openR = (finalClose - entry) / risk * sign
                val heldBars = (maxExit - 1 - (i + 1)).coerceAtLeast(0)
                outcomes.add(tradeIdeaNetReturnR(if (partial) 1.0 + openR * 0.5 else openR, entry, risk, heldBars, setupBarMinutes, roundTripFeeBps, slippageBps, fundingBpsPer8h))
                i = maxExit
            }
        }
        return if (outcomes.isEmpty()) TradeIdeaBacktestStats()
        else TradeIdeaBacktestStats(outcomes.size, outcomes.count { it > 0.0 }, outcomes.average())
    }

    private fun validCandles(rows: List<V2Candle>): Boolean {
        if (rows.size < 2) return false
        var previousTime = Long.MIN_VALUE
        return rows.all { candle ->
            val valid = candle.high.isFinite() && candle.low.isFinite() && candle.open.isFinite() &&
                candle.close.isFinite() && candle.volume.isFinite() &&
                candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
                candle.high >= candle.low && candle.open in candle.low..candle.high && candle.close in candle.low..candle.high &&
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
