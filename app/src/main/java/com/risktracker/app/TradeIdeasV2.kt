package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class V2Candle(
    val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long, val open: Double = close
)
internal data class V2Microstructure(
    val spreadBps: Double?,
    val bookImbalance: Double?,
    val fundingRate: Double? = null,
    val oiChangePct: Double? = null,
    val bidDepthUsd: Double? = null,
    val askDepthUsd: Double? = null
)

internal enum class TradeIdeaRiskCode(val blocksEntry: Boolean) {
    LOW_LIQUIDITY(true),
    INVALID_STOP(true),
    INSUFFICIENT_REWARD_RISK(true),
    ADVERSE_FUNDING(true),
    FUNDING_DATA_UNAVAILABLE(true),
    UNSUPPORTED_TIMEFRAME(true),
    VOLATILITY_OUT_OF_RANGE(true),
    EXTENDED_ENTRY(true),
    LOW_RELATIVE_VOLUME(true),
    MOMENTUM_NOT_CONFIRMED(false),
    LOW_CONFLUENCE_SCORE(true),
    STALE_MARKET_DATA(true),
    WAITING_FOR_ENTRY_CONFIRMATION(false),
    BELOW_READY_SCORE_THRESHOLD(false),
    COSTS_NOT_INCLUDED(false),
    STRUCTURE_NOT_CONFIRMED(true),
    LEGACY_BLOCKING_WARNING(true)
}

internal fun isFundingDataAcceptable(side: String, fundingRate: Double?): Boolean {
    if (side !in setOf("LONG", "SHORT") || fundingRate == null || !fundingRate.isFinite()) return false
    return if (side == "LONG") fundingRate <= 0.001 else fundingRate >= -0.001
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

/**
 * Converts a required net R multiple into the gross target multiple needed after the
 * same estimated round-trip fees, slippage and holding-period funding costs.
 */
internal fun tradeIdeaGrossRForMinimumNetReturn(
    minimumNetR: Double, entry: Double, riskDistance: Double, heldBars: Int, setupBarMinutes: Int,
    roundTripFeeBps: Double = 8.0, slippageBps: Double = 4.0, fundingBpsPer8h: Double = 1.0
): Double {
    if (!minimumNetR.isFinite() || minimumNetR < 0.0) return Double.NaN
    val netAtZeroGross = tradeIdeaNetReturnR(
        grossR = 0.0, entry = entry, riskDistance = riskDistance, heldBars = heldBars,
        setupBarMinutes = setupBarMinutes, roundTripFeeBps = roundTripFeeBps,
        slippageBps = slippageBps, fundingBpsPer8h = fundingBpsPer8h
    )
    return if (netAtZeroGross.isFinite()) minimumNetR - netAtZeroGross else Double.NaN
}

internal fun isReplayTrendPullback(
    isLong: Boolean, current: V2Candle, previous: V2Candle, ema20: Double, atr: Double
): Boolean {
    if (!ema20.isFinite() || !atr.isFinite() || atr <= 0.0) return false
    return if (isLong) {
        current.low in (ema20 - atr * 0.7)..(ema20 + atr * 0.2) &&
            current.close > ema20 && current.close > previous.close
    } else {
        current.high in (ema20 - atr * 0.2)..(ema20 + atr * 0.7) &&
            current.close < ema20 && current.close < previous.close
    }
}

internal fun isReplayRangeReversal(
    isLong: Boolean, rangeMode: Boolean, current: V2Candle,
    rangeHigh: Double, rangeLow: Double, atr: Double, rsi: Double
): Boolean {
    if (!rangeMode || !rangeHigh.isFinite() || !rangeLow.isFinite() || !atr.isFinite() ||
        !rsi.isFinite() || atr <= 0.0 || rangeHigh <= rangeLow) return false
    val width = rangeHigh - rangeLow
    if (width <= atr * 2.0) return false
    return if (isLong) {
        val lowerWick = min(current.open, current.close) - current.low
        current.low <= rangeLow + width * 0.12 && current.close > rangeLow &&
            lowerWick >= abs(current.close - current.open) * 0.8 && rsi <= 42.0
    } else {
        val upperWick = current.high - max(current.open, current.close)
        current.high >= rangeHigh - width * 0.12 && current.close < rangeHigh &&
            upperWick >= abs(current.close - current.open) * 0.8 && rsi >= 58.0
    }
}


internal data class TradeReplayBarResult(val exitR: Double? = null, val reachedTarget1: Boolean = false)

/** Resolves one OHLC bar conservatively: an active stop wins if stop and target are both touched. */
internal fun resolveTradeReplayBar(
    sideLong: Boolean, entry: Double, stop: Double, risk: Double, partial: Boolean, candle: V2Candle
): TradeReplayBarResult {
    if (!entry.isFinite() || !stop.isFinite() || !risk.isFinite() || risk <= 0.0 ||
        !candle.high.isFinite() || !candle.low.isFinite() || candle.high < candle.low) {
        return TradeReplayBarResult(exitR = if (partial) 1.0 else -1.0)
    }
    val activeStop = if (partial) entry else stop
    val stopHit = if (sideLong) candle.low <= activeStop else candle.high >= activeStop
    if (stopHit) return TradeReplayBarResult(exitR = if (partial) 1.0 else -1.0)
    val target1Hit = if (sideLong) candle.high >= entry + risk * 2.0 else candle.low <= entry - risk * 2.0
    val target2Hit = if (sideLong) candle.high >= entry + risk * 3.0 else candle.low <= entry - risk * 3.0
    if (target2Hit) return TradeReplayBarResult(exitR = 2.5)
    return TradeReplayBarResult(reachedTarget1 = target1Hit && !partial)
}


/** Only candles whose close is known at the decision timestamp may inform replay signals. */
internal fun closedReplayCandlesAtOrBefore(candles: List<V2Candle>, decisionCloseTime: Long): List<V2Candle> =
    candles.filter { it.closeTime <= decisionCloseTime }

internal object TradeIdeasV2 {
    const val RISK_PERCENT = 0.5
    const val MAX_LEVERAGE = 3.0
    const val MAX_SPREAD_BPS = 10.0
    const val MIN_SCORE = 75
    private const val MIN_QUOTE_VOLUME = MIN_TRADE_QUOTE_VOLUME_USD

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
    ): V2TradePlan? = ConfluenceScannerV4.evaluate(
        h4, h1, m15, m5, micro, quoteVolume, btcBias,
        horizon = if (swingMode) "Swing" else "Intraday"
    )

    /** Causal replay for trend pullback, breakout/retest and range reversal setups. */
    fun backtest(
        h4: List<V2Candle>, h1: List<V2Candle>, m15: List<V2Candle>, m5: List<V2Candle>,
        maxHoldingBars: Int = 96, setupBarMinutes: Int = 15, executionBarMinutes: Int = 5,
        roundTripFeeBps: Double = 8.0, slippageBps: Double = 4.0, fundingBpsPer8h: Double = 1.0
    ): TradeIdeaBacktestStats {
        if (m15.size < 80 || h4.size < 200 || h1.size < 50 || m5.size < 100 || maxHoldingBars <= 0 || setupBarMinutes <= 0 || executionBarMinutes <= 0) return TradeIdeaBacktestStats()
        if (!validCandles(h4) || !validCandles(h1) || !validCandles(m15) || !validCandles(m5)) return TradeIdeaBacktestStats()
        if (listOf(roundTripFeeBps, slippageBps, fundingBpsPer8h).any { !it.isFinite() || it < 0.0 }) return TradeIdeaBacktestStats()
        val outcomes = mutableListOf<TradeIdeaReplayOutcome>()
        var i = 30
        // Require a full forward window to avoid right-edge censoring and sample bias.
        while (hasCompleteTradeHorizon(i, m15.size, maxHoldingBars)) {
            val time = m15[i].closeTime
            val four = closedReplayCandlesAtOrBefore(h4, time)
            val one = closedReplayCandlesAtOrBefore(h1, time)
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
            val rangeMode = !long && !short &&
                abs(e20 - e50) / four.last().close * 100.0 <= 1.2 &&
                abs(e1_20 - e1_50) / one.last().close * 100.0 <= 0.8
            val history = m15.subList(i - 20, i)
            val avgVolume = history.map { it.volume }.average()
            val relativeVolume = if (avgVolume > 0.0) m15[i].volume / avgVolume else 0.0
            val atrValue = atr(m15.subList(0, i + 1))
            val setupEma = ema(m15.subList(0, i + 1).map { it.close }, 20)
            val setupRsi = rsi(m15.subList(0, i + 1).map { it.close })
            if (atrValue == null || setupEma == null || setupRsi == null) { i++; continue }
            val atr = atrValue
            val previous = m15[i - 1]
            val current = m15[i]
            val breakoutLevel = if (long) history.maxOf { it.high } else history.minOf { it.low }
            val breakout = (long || short) && relativeVolume >= 1.5 &&
                (if (long) current.close > breakoutLevel else current.close < breakoutLevel)
            val retest = if (i + 1 < m15.size) m15[i + 1] else current
            val breakoutTouched = if (long) retest.low <= breakoutLevel + atr * 0.25
                else retest.high >= breakoutLevel - atr * 0.25
            val breakoutHeld = if (long) retest.close >= breakoutLevel && retest.close >= current.close
                else retest.close <= breakoutLevel && retest.close <= current.close

            val pullback = (long && isReplayTrendPullback(true, current, previous, setupEma, atr)) ||
                (short && isReplayTrendPullback(false, current, previous, setupEma, atr))

            val rangeBars = m15.subList(i - 30, i)
            val rangeHigh = rangeBars.maxOf { it.high }
            val rangeLow = rangeBars.minOf { it.low }
            val rangeLong = isReplayRangeReversal(true, rangeMode, current, rangeHigh, rangeLow, atr, setupRsi)
            val rangeShort = isReplayRangeReversal(false, rangeMode, current, rangeHigh, rangeLow, atr, setupRsi)

            val sideLong: Boolean
            val entryCandle: V2Candle
            val entryIndex: Int
            val stopReference: List<V2Candle>
            val setupFamily: String
            when {
                breakout && breakoutTouched && breakoutHeld -> {
                    sideLong = long
                    entryCandle = retest
                    entryIndex = i + 1
                    stopReference = listOf(current, retest)
                    setupFamily = "BREAKOUT"
                }
                pullback && relativeVolume >= 1.2 -> {
                    sideLong = long
                    entryCandle = current
                    entryIndex = i
                    stopReference = m15.subList((i - 2).coerceAtLeast(0), i + 1)
                    setupFamily = "PULLBACK"
                }
                (rangeLong || rangeShort) && relativeVolume >= 1.2 -> {
                    sideLong = rangeLong
                    entryCandle = current
                    entryIndex = i
                    stopReference = m15.subList((i - 2).coerceAtLeast(0), i + 1)
                    setupFamily = "REVERSAL"
                }
                else -> { i++; continue }
            }
            val executionBars = closedReplayCandlesAtOrBefore(m5, entryCandle.closeTime)
            val executionCloses = executionBars.map { it.close }
            val executionFast = ema(executionCloses, 9)
            val executionSlow = ema(executionCloses, 21)
            val executionConfirmed = executionBars.isNotEmpty() &&
                entryCandle.closeTime - executionBars.last().closeTime <= maxOf(10, executionBarMinutes * 2) * 60_000L &&
                executionFast != null && executionSlow != null &&
                (if (sideLong) executionBars.last().close > executionFast && executionFast > executionSlow
                else executionBars.last().close < executionFast && executionFast < executionSlow)
            if (!executionConfirmed) { i++; continue }

            val sign = if (sideLong) 1.0 else -1.0
            val entry = entryCandle.close
            val stop = if (sideLong) stopReference.minOf { it.low } - atr * 0.20
                else stopReference.maxOf { it.high } + atr * 0.20
            val risk = abs(entry - stop)
            if (risk < atr * 0.35 || risk > atr * 2.5) { i++; continue }

            var partial = false
            var result = -1.0
            var exitAt = -1
            val maxExit = min(entryIndex + 1 + maxHoldingBars, m15.size)
            for (j in entryIndex + 1 until maxExit) {
                val candle = m15[j]
                val barResult = resolveTradeReplayBar(sideLong, entry, stop, risk, partial, candle)
                if (barResult.exitR != null) { result = barResult.exitR; exitAt = j; break }
                if (barResult.reachedTarget1) { partial = true; result = 1.0 }
            }
            if (exitAt >= 0) {
                val heldBars = (exitAt - entryIndex).coerceAtLeast(0)
                outcomes.add(TradeIdeaReplayOutcome(if (sideLong) "LONG" else "SHORT", if (rangeMode) "RANGE" else "TREND", setupFamily,
                    tradeIdeaNetReturnR(result, entry, risk, heldBars, setupBarMinutes, roundTripFeeBps, slippageBps, fundingBpsPer8h)))
                i = exitAt + 1
            } else {
                // The complete window is present by the loop guard; mark any open remainder at its time stop.
                val finalIndex = maxExit - 1
                val openR = (m15[finalIndex].close - entry) / risk * sign
                val heldBars = (finalIndex - entryIndex).coerceAtLeast(0)
                outcomes.add(TradeIdeaReplayOutcome(if (sideLong) "LONG" else "SHORT", if (rangeMode) "RANGE" else "TREND", setupFamily,
                    tradeIdeaNetReturnR(if (partial) 1.0 + openR * 0.5 else openR,
                        entry, risk, heldBars, setupBarMinutes, roundTripFeeBps, slippageBps, fundingBpsPer8h)))
                i = maxOf(entryIndex + 1, maxExit)
            }
        }
        return TradeIdeaBacktestStats.fromOutcomes(outcomes)
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
