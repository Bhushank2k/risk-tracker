package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** True only when the full forward holding window is present in the candle sample. */
internal fun hasCompleteTradeHorizon(setupIndex: Int, candleCount: Int, maxHoldingBars: Int): Boolean =
    setupIndex >= 0 && candleCount > 0 && maxHoldingBars > 0 &&
        setupIndex.toLong() + 1L + maxHoldingBars.toLong() < candleCount.toLong()

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


internal data class TradeReplayEntryFill(val candleIndex: Int, val price: Double)

/** Simulate a market entry at the next candle open after a fully closed signal candle. */
internal fun nextReplayOpenFill(candles: List<V2Candle>, signalIndex: Int): TradeReplayEntryFill? {
    if (signalIndex < 0) return null
    val fillIndex = signalIndex + 1
    val open = candles.getOrNull(fillIndex)?.open ?: return null
    if (!open.isFinite() || open <= 0.0) return null
    return TradeReplayEntryFill(fillIndex, open)
}

/** Only candles whose close is known at the decision timestamp may inform replay signals. */
internal fun closedReplayCandlesAtOrBefore(candles: List<V2Candle>, decisionCloseTime: Long): List<V2Candle> =
    candles.filter { it.closeTime <= decisionCloseTime }

internal object TradeIdeasV2 {
    const val MIN_SCORE = 70

    /** Live entry point: the same rules as the scanner, with live microstructure gates applied. */
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

    /**
     * Historical replay of the live scanner. At each closed setup-timeframe bar, [ConfluenceScannerV4.evaluate]
     * runs on candles closed by that bar's close time. A READY plan is traded at its own entry, stop and
     * targets. Microstructure gates are off because replay does not store historical spread, depth or funding,
     * so these results measure the signal rules, not the live liquidity filters.
     *
     * Parameters: [h4]/[h1] are the context and trend series, [m15] the setup series and [m5] the execution
     * series, for the given [horizon]. [maxHoldingBars] counts setup-timeframe bars.
     */
    fun backtest(
        h4: List<V2Candle>, h1: List<V2Candle>, m15: List<V2Candle>, m5: List<V2Candle>,
        maxHoldingBars: Int = 96, setupBarMinutes: Int = 15, executionBarMinutes: Int = 5,
        roundTripFeeBps: Double = 8.0, slippageBps: Double = 4.0, fundingBpsPer8h: Double = 1.0,
        horizon: String = "Intraday"
    ): TradeIdeaBacktestStats {
        if (h4.size < 50 || h1.size < 50 || m15.size < 80 || m5.size < 30 ||
            maxHoldingBars <= 0 || setupBarMinutes <= 0 || executionBarMinutes <= 0) return TradeIdeaBacktestStats()
        if (!validCandles(h4) || !validCandles(h1) || !validCandles(m15) || !validCandles(m5)) return TradeIdeaBacktestStats()
        if (listOf(roundTripFeeBps, slippageBps, fundingBpsPer8h).any { !it.isFinite() || it < 0.0 }) return TradeIdeaBacktestStats()
        val neutralMicro = V2Microstructure(null, null, null, null, null, null)
        val outcomes = mutableListOf<TradeIdeaReplayOutcome>()
        var i = 60
        // Require a full forward window so no trade is censored at the edge of the data.
        while (hasCompleteTradeHorizon(i, m15.size, maxHoldingBars)) {
            val decisionTime = m15[i].closeTime
            val plan = ConfluenceScannerV4.evaluate(
                replayPrefix(h4, decisionTime), replayPrefix(h1, decisionTime),
                m15.subList(0, i + 1), replayPrefix(m5, decisionTime),
                neutralMicro, quoteVolume = 0.0, btcBias = null, horizon = horizon, btcShock = null,
                nowMillis = decisionTime + 1, applyMarketFilters = false
            )
            // V2TradePlan.entryTriggered carries the scanner's READY flag (see ConfluenceScannerV4's constructor call).
            if (plan == null || !plan.entryTriggered) { i++; continue }
            val risk = abs(plan.entry - plan.stop)
            if (!risk.isFinite() || risk <= 0.0 || plan.target1 <= 0.0 || plan.target2 <= 0.0) { i++; continue }
            val sign = if (plan.side == "LONG") 1.0 else -1.0
            val exit = resolvePlanReplay(
                m15, startIndex = i + 1, maxBars = maxHoldingBars, sign = sign, entry = plan.entry,
                stop = plan.stop, risk = risk,
                t1R = abs(plan.target1 - plan.entry) / risk, t2R = abs(plan.target2 - plan.entry) / risk
            )
            val heldBars = (exit.index - i).coerceAtLeast(1)
            val netR = tradeIdeaNetReturnR(
                exit.grossR, plan.entry, risk, heldBars, setupBarMinutes,
                roundTripFeeBps, slippageBps, fundingBpsPer8h
            )
            if (netR.isFinite()) {
                outcomes.add(TradeIdeaReplayOutcome(
                    plan.side,
                    if (plan.setup == "Range reversal" || plan.setup == "Bollinger Band Reversal") "RANGE" else "TREND",
                    replaySetupFamily(plan.setup),
                    netR
                ))
            }
            i = exit.index + 1
        }
        return TradeIdeaBacktestStats.fromOutcomes(outcomes)
    }

    private data class PlanReplayExit(val index: Int, val grossR: Double)

    /**
     * Exits a live plan on the setup-timeframe bars that follow entry. The stop is checked before the targets
     * on each bar. Target 1 closes half the position and moves the stop to breakeven. Target 2 closes the rest.
     * Otherwise the open half is closed at the last close in the holding window.
     */
    private fun resolvePlanReplay(
        bars: List<V2Candle>, startIndex: Int, maxBars: Int, sign: Double,
        entry: Double, stop: Double, risk: Double, t1R: Double, t2R: Double
    ): PlanReplayExit {
        val last = minOf(bars.size - 1, startIndex + maxBars - 1)
        val t1Price = entry + sign * risk * t1R
        val t2Price = entry + sign * risk * t2R
        var partial = false
        var partialR = 0.0
        for (j in startIndex..last) {
            val bar = bars[j]
            val adverse = if (sign > 0) bar.low else bar.high
            val favourable = if (sign > 0) bar.high else bar.low
            val level = if (partial) entry else stop
            if (sign * (adverse - level) <= 0.0) {
                return PlanReplayExit(j, if (partial) partialR else -1.0)
            }
            if (partial) {
                if (sign * (favourable - t2Price) >= 0.0) return PlanReplayExit(j, partialR + 0.5 * t2R)
            } else if (sign * (favourable - t1Price) >= 0.0) {
                // Target 2 is not checked on the same bar, so a single bar cannot close the position twice.
                partial = true
                partialR = 0.5 * t1R
            }
        }
        val openR = sign * (bars[last].close - entry) / risk
        return PlanReplayExit(last, if (partial) partialR + 0.5 * openR else openR)
    }

    private fun replaySetupFamily(setup: String): String = when (setup) {
        "Trend pullback" -> "PULLBACK"
        "Breakout + retest" -> "BREAKOUT"
        "Range reversal" -> "REVERSAL"
        "Bollinger Band Reversal" -> "BOLLINGER_REVERSAL"
        else -> setup.uppercase()
    }

    /** Candles whose close is at or before [decisionCloseTime], found by binary search on sorted close times. */
    private fun replayPrefix(candles: List<V2Candle>, decisionCloseTime: Long): List<V2Candle> {
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (candles[mid].closeTime <= decisionCloseTime) low = mid + 1 else high = mid
        }
        return candles.subList(0, low)
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
}
