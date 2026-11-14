package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Causal, candle-close Smart Money Concepts primitives.
 *
 * These are OHLCV price-action classifications, not evidence of actual institutional
 * orders. A pivot is only exposed after its configured right-hand confirmation bars
 * have closed. Liquidity sweeps are measured against levels formed before the sweep bar.
 */
enum class SmcDirection { BULLISH, BEARISH, NEUTRAL }
enum class SmcStructureEventType { BOS, CHOCH }
enum class SmcLiquidityType { BUY_SIDE, SELL_SIDE }

data class SmcSwingPoint(
    val index: Int,
    val price: Double,
    val confirmedAtIndex: Int,
    val isHigh: Boolean
)

data class SmcStructureEvent(
    val type: SmcStructureEventType,
    val direction: SmcDirection,
    val brokenLevel: Double,
    val candleIndex: Int
)

data class SmcStructureSnapshot(
    val bias: SmcDirection,
    val swingHighs: List<SmcSwingPoint>,
    val swingLows: List<SmcSwingPoint>,
    val event: SmcStructureEvent? = null
)

data class SmcLiquidityLevel(
    val type: SmcLiquidityType,
    val price: Double,
    val touches: Int,
    val firstIndex: Int,
    val lastIndex: Int
)

data class SmcLiquiditySweep(
    val type: SmcLiquidityType,
    val level: Double,
    val extreme: Double,
    val reclaimClose: Double,
    val candleIndex: Int
)

private fun validSmcCandles(candles: List<V2Candle>): Boolean =
    candles.size >= 2 && candles.all {
        it.open.isFinite() && it.high.isFinite() && it.low.isFinite() &&
            it.close.isFinite() && it.volume.isFinite() &&
            it.high >= it.low && it.open in it.low..it.high &&
            it.close in it.low..it.high && it.volume >= 0.0
    }

/** Return only pivots with all right-side confirmation candles already present. */
fun confirmedSmcSwings(
    candles: List<V2Candle>,
    leftBars: Int = 2,
    rightBars: Int = 2
): List<SmcSwingPoint> {
    if (!validSmcCandles(candles) || leftBars < 1 || rightBars < 1 ||
        candles.size < leftBars + rightBars + 1) return emptyList()

    val result = mutableListOf<SmcSwingPoint>()
    val lastCandidate = candles.lastIndex - rightBars
    for (i in leftBars..lastCandidate) {
        val neighbours = (i - leftBars..i + rightBars).filter { it != i }
        val high = neighbours.all { candles[i].high > candles[it].high }
        val low = neighbours.all { candles[i].low < candles[it].low }
        if (high) result += SmcSwingPoint(i, candles[i].high, i + rightBars, true)
        if (low) result += SmcSwingPoint(i, candles[i].low, i + rightBars, false)
    }
    return result.sortedBy { it.index }
}

/**
 * Determine swing bias and the latest close-confirmed break.
 * A break against the prior HH/HL or LH/LL bias is CHoCH; a break with it is BOS.
 */
fun analyzeSmcStructure(
    candles: List<V2Candle>,
    leftBars: Int = 2,
    rightBars: Int = 2,
    atr: Double? = null,
    minimumBreakAtr: Double = 0.05
): SmcStructureSnapshot {
    if (!validSmcCandles(candles) || !minimumBreakAtr.isFinite() || minimumBreakAtr < 0.0 ||
        (atr != null && (!atr.isFinite() || atr < 0.0))) {
        return SmcStructureSnapshot(SmcDirection.NEUTRAL, emptyList(), emptyList())
    }
    val swings = confirmedSmcSwings(candles, leftBars, rightBars)
    val highs = swings.filter { it.isHigh }
    val lows = swings.filterNot { it.isHigh }
    val bias = if (highs.size < 2 || lows.size < 2) SmcDirection.NEUTRAL else {
        val previousHigh = highs[highs.lastIndex - 1].price
        val latestHigh = highs.last().price
        val previousLow = lows[lows.lastIndex - 1].price
        val latestLow = lows.last().price
        when {
            latestHigh > previousHigh && latestLow > previousLow -> SmcDirection.BULLISH
            latestHigh < previousHigh && latestLow < previousLow -> SmcDirection.BEARISH
            else -> SmcDirection.NEUTRAL
        }
    }

    val last = candles.last()
    val minDistance = (atr ?: 0.0) * minimumBreakAtr
    val latestHigh = highs.lastOrNull()
    val latestLow = lows.lastOrNull()
    val bullishBreak = latestHigh != null && last.close > latestHigh.price &&
        last.close - latestHigh.price >= minDistance
    val bearishBreak = latestLow != null && last.close < latestLow.price &&
        latestLow.price - last.close >= minDistance
    val event = when {
        bullishBreak -> SmcStructureEvent(
            if (bias == SmcDirection.BEARISH) SmcStructureEventType.CHOCH else SmcStructureEventType.BOS,
            SmcDirection.BULLISH, latestHigh!!.price, candles.lastIndex
        )
        bearishBreak -> SmcStructureEvent(
            if (bias == SmcDirection.BULLISH) SmcStructureEventType.CHOCH else SmcStructureEventType.BOS,
            SmcDirection.BEARISH, latestLow!!.price, candles.lastIndex
        )
        else -> null
    }
    return SmcStructureSnapshot(bias, highs, lows, event)
}

/**
 * Build equal-high/equal-low liquidity pools from already confirmed pivots.
 * A pool needs at least two touches within ATR-scaled tolerance.
 */
fun detectSmcLiquidityLevels(
    candles: List<V2Candle>,
    atr: Double,
    lookback: Int = 80,
    toleranceAtr: Double = 0.10,
    leftBars: Int = 2,
    rightBars: Int = 2
): List<SmcLiquidityLevel> {
    if (!validSmcCandles(candles) || !atr.isFinite() || atr <= 0.0 ||
        lookback < 5 || !toleranceAtr.isFinite() || toleranceAtr <= 0.0) return emptyList()
    val start = (candles.size - lookback).coerceAtLeast(0)
    val tolerance = atr * toleranceAtr
    val swings = confirmedSmcSwings(candles, leftBars, rightBars)
        .filter { it.index >= start && it.confirmedAtIndex < candles.lastIndex }
    val output = mutableListOf<SmcLiquidityLevel>()

    for (isHigh in listOf(true, false)) {
        val points = swings.filter { it.isHigh == isHigh }.sortedBy { it.price }
        val used = mutableSetOf<Int>()
        for (i in points.indices) {
            if (i in used) continue
            val cluster = points.indices.filter { j ->
                j !in used && abs(points[j].price - points[i].price) <= tolerance
            }
            if (cluster.size < 2) continue
            cluster.forEach { used += it }
            val members = cluster.map { points[it] }
            val level = members.map { it.price }.average()
            output += SmcLiquidityLevel(
                if (isHigh) SmcLiquidityType.BUY_SIDE else SmcLiquidityType.SELL_SIDE,
                level, members.size, members.minOf { it.index }, members.maxOf { it.index }
            )
        }
    }
    return output
}

/**
 * Detect a sweep on the newest completed candle using levels formed strictly
 * before that candle. A wick beyond the level and close back through it are required.
 */
fun detectLatestSmcLiquiditySweep(
    candles: List<V2Candle>,
    atr: Double,
    lookback: Int = 20,
    minimumPenetrationAtr: Double = 0.0
): SmcLiquiditySweep? {
    if (!validSmcCandles(candles) || candles.size < lookback + 1 ||
        !atr.isFinite() || atr <= 0.0 || lookback < 2 ||
        !minimumPenetrationAtr.isFinite() || minimumPenetrationAtr < 0.0) return null
    val current = candles.last()
    val prior = candles.subList((candles.size - lookback - 1).coerceAtLeast(0), candles.lastIndex)
    if (prior.isEmpty()) return null
    val priorHigh = prior.maxOf { it.high }
    val priorLow = prior.minOf { it.low }
    val minimumPenetration = atr * minimumPenetrationAtr
    val body = abs(current.close - current.open)
    val lowerWick = min(current.open, current.close) - current.low
    val upperWick = current.high - max(current.open, current.close)

    return when {
        current.low < priorLow - minimumPenetration && current.close > priorLow &&
            lowerWick > 0.0 && lowerWick >= body * 0.5 ->
            SmcLiquiditySweep(SmcLiquidityType.SELL_SIDE, priorLow, current.low, current.close, candles.lastIndex)
        current.high > priorHigh + minimumPenetration && current.close < priorHigh &&
            upperWick > 0.0 && upperWick >= body * 0.5 ->
            SmcLiquiditySweep(SmcLiquidityType.BUY_SIDE, priorHigh, current.high, current.close, candles.lastIndex)
        else -> null
    }
}
