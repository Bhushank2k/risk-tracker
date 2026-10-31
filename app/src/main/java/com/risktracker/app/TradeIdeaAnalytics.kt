package com.risktracker.app

import kotlin.math.abs

internal data class TradeSizingResult(
    val quantity: Double,
    val notional: Double,
    val marginAtMaxLeverage: Double,
    val riskAmount: Double,
    val actualRiskPercent: Double
)

internal object TradeSizing {
    fun calculate(
        equityUsdt: Double,
        entry: Double,
        stop: Double,
        riskPercent: Double = 0.5,
        maxLeverage: Double = 3.0
    ): TradeSizingResult? {
        if (!equityUsdt.isFinite() || equityUsdt <= 0.0 ||
            !entry.isFinite() || entry <= 0.0 ||
            !stop.isFinite() || stop <= 0.0 ||
            !riskPercent.isFinite() || riskPercent <= 0.0 ||
            !maxLeverage.isFinite() || maxLeverage <= 0.0
        ) return null
        val distance = abs(entry - stop)
        if (distance <= 0.0) return null
        val riskBudget = equityUsdt * riskPercent / 100.0
        val rawQuantity = riskBudget / distance
        val maxQuantity = equityUsdt * maxLeverage / entry
        val quantity = minOf(rawQuantity, maxQuantity)
        val notional = quantity * entry
        val riskAmount = quantity * distance
        return TradeSizingResult(
            quantity = quantity,
            notional = notional,
            marginAtMaxLeverage = notional / maxLeverage,
            riskAmount = riskAmount,
            actualRiskPercent = riskAmount / equityUsdt * 100.0
        )
    }
}

internal data class BacktestCandle(
    val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long
)

internal data class TradeIdeaBacktestStats(
    val trades: Int = 0,
    val wins: Int = 0,
    val averageR: Double = 0.0
) {
    val winRatePercent: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades * 100.0
}

internal object TradeIdeaBacktest {
    fun run(h4: List<BacktestCandle>, h1: List<BacktestCandle>, m15: List<BacktestCandle>): TradeIdeaBacktestStats {
        if (m15.size < 80 || h4.size < 55 || h1.size < 25) return TradeIdeaBacktestStats()
        val outcomes = mutableListOf<Double>()
        var i = 60
        while (i < m15.lastIndex) {
            val time = m15[i].closeTime
            val fourHour = h4.filter { it.closeTime <= time }
            val oneHour = h1.filter { it.closeTime <= time }
            if (fourHour.size < 55 || oneHour.size < 25) { i++; continue }
            val h4Close = fourHour.map { it.close }
            val h1Close = oneHour.map { it.close }
            val recent = m15.subList(0, i + 1)
            val mClose = recent.map { it.close }
            val e4FastValue = ema(h4Close, 9)
            val e4SlowValue = ema(h4Close, 21)
            val e4BaseValue = ema(h4Close, 50)
            val e1FastValue = ema(h1Close, 9)
            val e1SlowValue = ema(h1Close, 21)
            val mFastValue = ema(mClose, 9)
            val mSlowValue = ema(mClose, 21)
            if (e4FastValue == null || e4SlowValue == null || e4BaseValue == null ||
                e1FastValue == null || e1SlowValue == null || mFastValue == null || mSlowValue == null) {
                i++
                continue
            }
            val e4Fast = e4FastValue
            val e4Slow = e4SlowValue
            val e4Base = e4BaseValue
            val e1Fast = e1FastValue
            val e1Slow = e1SlowValue
            val mFast = mFastValue
            val mSlow = mSlowValue
            val vwapRows = recent.takeLast(24)
            val totalVolume = vwapRows.sumOf { it.volume }
            if (totalVolume <= 0.0) { i++; continue }
            val rollingVwap = vwapRows.sumOf { it.close * it.volume } / totalVolume
            val priorVolume = recent.dropLast(1).takeLast(20).map { it.volume }.average()
            val volumeRatio = if (priorVolume > 0.0) recent.last().volume / priorVolume else 0.0
            val bull = fourHour.last().close > e4Base && e4Fast > e4Slow && e4Slow > e4Base &&
                oneHour.last().close > e1Slow && e1Fast > e1Slow &&
                recent.last().close > rollingVwap && mFast > mSlow
            val bear = fourHour.last().close < e4Base && e4Fast < e4Slow && e4Slow < e4Base &&
                oneHour.last().close < e1Slow && e1Fast < e1Slow &&
                recent.last().close < rollingVwap && mFast < mSlow
            val triggerRows = m15.subList(i - 5, i)
            val previousClose = m15[i - 1].close
            val last = m15[i]
            val side = when {
                bull && last.close > triggerRows.maxOf { it.high } &&
                    previousClose <= triggerRows.maxOf { it.high } && volumeRatio >= 1.2 -> 1
                bear && last.close < triggerRows.minOf { it.low } &&
                    previousClose >= triggerRows.minOf { it.low } && volumeRatio >= 1.2 -> -1
                else -> 0
            }
            if (side == 0) { i++; continue }
            val atrRows = recent.zipWithNext { p, c ->
                maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
            }.takeLast(14)
            if (atrRows.size < 14) { i++; continue }
            val riskDistance = atrRows.average() * 1.5
            if (!riskDistance.isFinite() || riskDistance <= 0.0) { i++; continue }
            val entry = if (side > 0) triggerRows.maxOf { it.high } else triggerRows.minOf { it.low }
            val stop = entry - side * riskDistance
            val target1 = entry + side * riskDistance * 1.5
            val target2 = entry + side * riskDistance * 2.5
            var target1Reached = false
            var exitIndex = -1
            var resultR = 0.0
            for (j in i + 1 until m15.size) {
                val candle = m15[j]
                val stopHit = if (side > 0) candle.low <= stop else candle.high >= stop
                val target1Hit = if (side > 0) candle.high >= target1 else candle.low <= target1
                val target2Hit = if (side > 0) candle.high >= target2 else candle.low <= target2
                if (stopHit) {
                    exitIndex = j
                    resultR = if (target1Reached) 0.25 else -1.0
                    break
                }
                if (target2Hit) {
                    exitIndex = j
                    resultR = 2.0
                    break
                }
                if (target1Hit) target1Reached = true
            }
            if (exitIndex >= 0) {
                outcomes.add(resultR)
                i = exitIndex + 1
            } else i++
        }
        return if (outcomes.isEmpty()) TradeIdeaBacktestStats()
        else TradeIdeaBacktestStats(outcomes.size, outcomes.count { it > 0.0 }, outcomes.average())
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        values.drop(period).forEach { value = it * alpha + value * (1.0 - alpha) }
        return value.takeIf { it.isFinite() && it > 0.0 }
    }
}
