package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max

/** Shared, formula-explicit calculations. Methods preserve each scanner's existing RSI mode. */
data class DirectionalStrength(val adx: Double, val plusDi: Double, val minusDi: Double)

data class ConfirmedPricePivot(
    val index: Int,
    val price: Double,
    val confirmedAtIndex: Int,
    val isHigh: Boolean
)

object ScannerCalculationCore {
    fun emaSeries(values: List<Double>, period: Int): List<Double?> {
        require(period > 0)
        val result = MutableList<Double?>(values.size) { null }
        if (values.size < period || values.any { !it.isFinite() }) return result
        var current = values.take(period).average()
        result[period - 1] = current
        val alpha = 2.0 / (period + 1.0)
        for (i in period until values.size) {
            current = values[i] * alpha + current * (1.0 - alpha)
            result[i] = current
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): Double? =
        emaSeries(values, period).lastOrNull { it != null }

    /** Wilder-smoothed ADX and directional indicators. Requires enough bars to seed ADX. */
    fun directionalStrength(
        highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14
    ): DirectionalStrength? {
        if (period < 2 || highs.size != lows.size || highs.size != closes.size ||
            highs.size < period * 2 || (highs + lows + closes).any { !it.isFinite() } ||
            highs.indices.any { highs[it] < lows[it] || closes[it] <= 0.0 ||
                closes[it] !in lows[it]..highs[it] }) return null

        val trueRanges = mutableListOf<Double>()
        val plusMoves = mutableListOf<Double>()
        val minusMoves = mutableListOf<Double>()
        for (i in 1 until highs.size) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            trueRanges += max(highs[i] - lows[i], max(
                abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1])
            ))
            plusMoves += if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusMoves += if (downMove > upMove && downMove > 0.0) downMove else 0.0
        }

        var smoothTr = trueRanges.take(period).sum()
        var smoothPlus = plusMoves.take(period).sum()
        var smoothMinus = minusMoves.take(period).sum()
        val dxValues = mutableListOf<Double>()
        fun currentDx(): Double? {
            if (smoothTr <= 0.0) return null
            val plusDi = 100.0 * smoothPlus / smoothTr
            val minusDi = 100.0 * smoothMinus / smoothTr
            val denominator = plusDi + minusDi
            return if (denominator <= 0.0) 0.0
                else 100.0 * abs(plusDi - minusDi) / denominator
        }
        currentDx()?.let(dxValues::add)
        for (i in period until trueRanges.size) {
            smoothTr = smoothTr - smoothTr / period + trueRanges[i]
            smoothPlus = smoothPlus - smoothPlus / period + plusMoves[i]
            smoothMinus = smoothMinus - smoothMinus / period + minusMoves[i]
            currentDx()?.let(dxValues::add)
        }
        if (dxValues.size < period || smoothTr <= 0.0) return null
        var adx = dxValues.take(period).average()
        for (i in period until dxValues.size)
            adx = (adx * (period - 1) + dxValues[i]) / period
        val plusDi = 100.0 * smoothPlus / smoothTr
        val minusDi = 100.0 * smoothMinus / smoothTr
        return DirectionalStrength(adx, plusDi, minusDi).takeIf {
            it.adx.isFinite() && it.plusDi.isFinite() && it.minusDi.isFinite()
        }
    }

    /** Simple-window RSI, matching the existing Confluence/Reversal implementation. */
    fun rsiSimple(values: List<Double>, period: Int = 14): Double? {
        if (period < 1 || values.size <= period || values.any { !it.isFinite() }) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gains = changes.filter { it > 0.0 }.sum() / period
        val losses = -changes.filter { it < 0.0 }.sum() / period
        if (losses == 0.0) return if (gains == 0.0) 50.0 else 100.0
        return (100.0 - 100.0 / (1.0 + gains / losses)).takeIf { it.isFinite() }
    }

    /** Wilder RSI, matching Market Activity Lab's seeded Wilder smoothing. */
    fun rsiWilder(values: List<Double>, period: Int = 14): Double? {
        if (period < 1 || values.size <= period || values.any { !it.isFinite() }) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val delta = values[i] - values[i - 1]
            gain += max(delta, 0.0)
            loss += max(-delta, 0.0)
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (i in period + 1 until values.size) {
            val delta = values[i] - values[i - 1]
            averageGain = (averageGain * (period - 1) + max(delta, 0.0)) / period
            averageLoss = (averageLoss * (period - 1) + max(-delta, 0.0)) / period
        }
        if (averageLoss == 0.0) return if (averageGain == 0.0) 50.0 else 100.0
        return (100.0 - 100.0 / (1.0 + averageGain / averageLoss)).takeIf { it.isFinite() }
    }

    /** Simple mean true range over the last period bars; first bar uses high-low. */
    fun atr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): Double? {
        if (period < 1 || highs.size != lows.size || highs.size != closes.size ||
            highs.size < period || (highs + lows + closes).any { !it.isFinite() }) return null
        val trueRanges = highs.indices.map { i ->
            if (i == 0) highs[i] - lows[i]
            else max(highs[i] - lows[i], max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1])))
        }
        return trueRanges.takeLast(period).average().takeIf { it.isFinite() && it > 0.0 }
    }

    fun averageVolume(volumes: List<Double>, lookback: Int, excludeLast: Boolean = false): Double? {
        if (lookback < 1) return null
        val source = if (excludeLast) volumes.dropLast(1) else volumes
        if (source.size < lookback) return null
        return source.takeLast(lookback).average().takeIf { it.isFinite() && it > 0.0 }
    }

    /** Strict confirmed pivots; a point is returned only after all right bars exist. */
    fun confirmedPivots(
        highs: List<Double>, lows: List<Double>, leftBars: Int = 2, rightBars: Int = 2
    ): List<ConfirmedPricePivot> {
        if (leftBars < 1 || rightBars < 1 || highs.size != lows.size ||
            highs.size < leftBars + rightBars + 1 ||
            (highs + lows).any { !it.isFinite() }) return emptyList()
        val result = mutableListOf<ConfirmedPricePivot>()
        for (i in leftBars..(highs.lastIndex - rightBars)) {
            val neighbours = (i - leftBars..i + rightBars).filter { it != i }
            if (neighbours.all { highs[i] > highs[it] })
                result += ConfirmedPricePivot(i, highs[i], i + rightBars, true)
            if (neighbours.all { lows[i] < lows[it] })
                result += ConfirmedPricePivot(i, lows[i], i + rightBars, false)
        }
        return result.sortedBy { it.index }
    }

    fun primarySignalReason(strategy: String, side: String, evidence: List<String>): String {
        val details = evidence.filter { it.isNotBlank() }.distinct().take(3)
        return buildString {
            append(strategy).append(" · ").append(side)
            if (details.isNotEmpty()) append(": ").append(details.joinToString("; "))
        }
    }
}
