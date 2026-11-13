package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Soft Smart Money Concepts evidence. These are price-action proxies, not proof
 * of institutional orders. They add score only; they never create a setup or
 * replace the scanner's mandatory liquidity, funding, stop, entry or 2R gates.
 */
data class TradeIdeaSmcEvidence(
    val liquiditySweep: Boolean = false,
    val structureBreak: Boolean = false,
    val fairValueGapRetest: Boolean = false,
    val orderBlockRetest: Boolean = false,
    val premiumDiscountAligned: Boolean = false
) {
    val points: Int
        get() = (if (liquiditySweep) 3 else 0) +
            (if (structureBreak) 2 else 0) +
            (if (fairValueGapRetest) 2 else 0) +
            (if (orderBlockRetest) 1 else 0) +
            (if (premiumDiscountAligned) 2 else 0)

    val explanation: String
        get() = listOfNotNull(
            if (liquiditySweep) "liquidity sweep/reclaim" else null,
            if (structureBreak) "BOS/CHoCH close" else null,
            if (fairValueGapRetest) "FVG retest" else null,
            if (orderBlockRetest) "order-block retest" else null,
            if (premiumDiscountAligned) "premium/discount alignment" else null
        ).ifEmpty { listOf("no additional SMC confirmation") }.joinToString(", ")
}

/** Compute a capped 0–10 SMC bonus from confirmed 15M candles. */
fun evaluateTradeIdeaSmc(
    candles: List<V2Candle>,
    side: String,
    atr: Double,
    lookback: Int = 30
): TradeIdeaSmcEvidence {
    if (side !in setOf("LONG", "SHORT") || candles.size < 12 ||
        !atr.isFinite() || atr <= 0.0 || lookback < 5 ||
        candles.any { !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() ||
            !it.close.isFinite() || it.high < it.low }) return TradeIdeaSmcEvidence()

    val long = side == "LONG"
    val sweepType = detectLatestSmcLiquiditySweep(candles, atr)?.type
    val sweep = if (side == "LONG") sweepType == SmcLiquidityType.SELL_SIDE
        else sweepType == SmcLiquidityType.BUY_SIDE
    val structure = hasTradeIdeaSmcStructureBreak(candles, side, atr)
    val fvg = hasTradeIdeaSmcFvgRetest(candles, side, lookback)
    val orderBlock = hasTradeIdeaSmcOrderBlockRetest(candles, side, atr, lookback)
    val range = candles.takeLast(lookback)
    val high = range.maxOf { it.high }
    val low = range.minOf { it.low }
    val midpoint = (high + low) / 2.0
    val currentClose = candles.last().close
    val premiumDiscount = if (long) currentClose <= midpoint else currentClose >= midpoint

    return TradeIdeaSmcEvidence(sweep, structure, fvg, orderBlock, premiumDiscount)
}

/** A close beyond the latest confirmed two-left/two-right swing level. */
fun hasTradeIdeaSmcStructureBreak(candles: List<V2Candle>, side: String, atr: Double? = null): Boolean {
    if (side !in setOf("LONG", "SHORT")) return false
    val event = analyzeSmcStructure(candles, atr = atr).event ?: return false
    return if (side == "LONG") event.direction == SmcDirection.BULLISH
    else event.direction == SmcDirection.BEARISH
}

/** Detect a recent three-candle imbalance whose zone is being revisited now. */
fun hasTradeIdeaSmcFvgRetest(candles: List<V2Candle>, side: String, lookback: Int = 30): Boolean {
    if (side !in setOf("LONG", "SHORT") || candles.size < 4 || lookback < 2) return false
    val current = candles.last()
    val start = (candles.size - lookback).coerceAtLeast(2)
    for (i in start until candles.lastIndex) {
        val first = candles[i - 2]
        val third = candles[i]
        val bullishGap = third.low > first.high
        val bearishGap = third.high < first.low
        if (side == "LONG" && bullishGap) {
            val zoneLow = first.high
            val zoneHigh = third.low
            if (current.low <= zoneHigh && current.high >= zoneLow && current.close >= zoneLow) return true
        }
        if (side == "SHORT" && bearishGap) {
            val zoneLow = third.high
            val zoneHigh = first.low
            if (current.high >= zoneLow && current.low <= zoneHigh && current.close <= zoneHigh) return true
        }
    }
    return false
}

/**
 * Find an opposing candle followed by an ATR-sized displacement through its
 * far edge, then require the current candle to revisit that candle's body zone.
 */
fun hasTradeIdeaSmcOrderBlockRetest(
    candles: List<V2Candle>,
    side: String,
    atr: Double,
    lookback: Int = 30
): Boolean {
    if (side !in setOf("LONG", "SHORT") || candles.size < 5 ||
        !atr.isFinite() || atr <= 0.0 || lookback < 3) return false
    val current = candles.last()
    val start = (candles.size - lookback - 1).coerceAtLeast(0)
    for (i in start until candles.lastIndex - 1) {
        val block = candles[i]
        val opposing = if (side == "LONG") block.close < block.open else block.close > block.open
        if (!opposing) continue
        val displacement = (i + 1 until candles.lastIndex).any { j ->
            val candle = candles[j]
            val body = abs(candle.close - candle.open)
            if (side == "LONG") candle.close > block.high && body >= atr * 0.8
            else candle.close < block.low && body >= atr * 0.8
        }
        if (!displacement) continue
        val zoneLow = min(block.open, block.close)
        val zoneHigh = max(block.open, block.close)
        if (current.low <= zoneHigh && current.high >= zoneLow) return true
    }
    return false
}
