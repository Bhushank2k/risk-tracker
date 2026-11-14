package com.risktracker.app

enum class SequentialSignalStage { H4_BIAS, H1_STRUCTURE, M15_SETUP, M5_ENTRY }

data class SequentialSignalEvent(val stage: SequentialSignalStage, val direction: SmcDirection, val closeTime: Long)
data class SequentialConfluenceResult(val ready: Boolean, val reason: String, val events: List<SequentialSignalEvent>)

/** Validates timestamped closed-candle signals; each stage persists only within its expiry. */
fun evaluateSequentialConfluence(
    events: List<SequentialSignalEvent>,
    direction: SmcDirection,
    decisionTime: Long,
    maxAgeMillis: Map<SequentialSignalStage, Long> = mapOf(
        SequentialSignalStage.H4_BIAS to 12 * 60 * 60_000L,
        SequentialSignalStage.H1_STRUCTURE to 6 * 60 * 60_000L,
        SequentialSignalStage.M15_SETUP to 2 * 60 * 60_000L,
        SequentialSignalStage.M5_ENTRY to 15 * 60_000L
    ),
    allowNeutralContext: Boolean = false
): SequentialConfluenceResult {
    if (direction == SmcDirection.NEUTRAL || decisionTime < 0L)
        return SequentialConfluenceResult(false, "Direction or decision timestamp is invalid.", emptyList())
    val selected = mutableListOf<SequentialSignalEvent>()
    for (stage in SequentialSignalStage.values()) {
        val latest = events.filter { it.stage == stage && it.closeTime <= decisionTime }.maxByOrNull { it.closeTime }
            ?: return SequentialConfluenceResult(false, "Waiting for ${stage.name.replace('_', ' ')} confirmation.", selected)
        val neutralContextAllowed = allowNeutralContext &&
            stage in setOf(SequentialSignalStage.H4_BIAS, SequentialSignalStage.H1_STRUCTURE) &&
            latest.direction == SmcDirection.NEUTRAL
        if (latest.direction != direction && !neutralContextAllowed)
            return SequentialConfluenceResult(false, "${stage.name.replace('_', ' ')} conflicts with trade direction.", selected)
        val maxAge = maxAgeMillis[stage] ?: return SequentialConfluenceResult(false, "Missing expiry policy for ${stage.name}.", selected)
        if (maxAge <= 0L || latest.closeTime < 0L || decisionTime - latest.closeTime > maxAge)
            return SequentialConfluenceResult(false, "${stage.name.replace('_', ' ')} confirmation expired.", selected)
        if (selected.isNotEmpty() && latest.closeTime < selected.last().closeTime)
            return SequentialConfluenceResult(false, "${stage.name.replace('_', ' ')} occurred before the prior stage.", selected)
        selected += latest
    }
    return SequentialConfluenceResult(true, "All four confirmations are valid in sequence.", selected)
}


private fun hasValidSequentialCandleHistory(candles: List<V2Candle>): Boolean =
    candles.all { candle ->
        candle.closeTime >= 0L &&
            candle.open.isFinite() && candle.high.isFinite() && candle.low.isFinite() &&
            candle.close.isFinite() && candle.volume.isFinite() && candle.volume >= 0.0 &&
            candle.high >= candle.low && candle.open in candle.low..candle.high &&
            candle.close in candle.low..candle.high
    } && candles.zipWithNext().all { (previous, current) ->
        current.closeTime > previous.closeTime
    }

/** Incremental EMA with the same SMA seed and recursive updates as sequentialEma. */
private class SequentialEma(private val period: Int) {
    private var count = 0
    private var seedSum = 0.0
    private var current: Double? = null

    fun add(value: Double): Double? {
        count++
        val previous = current
        if (previous == null) {
            seedSum += value
            if (count == period) current = seedSum / period
        } else {
            val alpha = 2.0 / (period + 1)
            current = value * alpha + previous * (1.0 - alpha)
        }
        return current?.takeIf { it.isFinite() }
    }
}

/** Reconstructs confirmed events in linear time while preserving closed-prefix semantics. */
fun buildSequentialSignalHistory(
    h4: List<V2Candle>,
    h1: List<V2Candle>,
    m15: List<V2Candle>,
    m5: List<V2Candle>,
    side: String
): List<SequentialSignalEvent> {
    if (!listOf(h4, h1, m15, m5).all(::hasValidSequentialCandleHistory)) return emptyList()
    if (side != "LONG" && side != "SHORT") return emptyList()
    val events = mutableListOf<SequentialSignalEvent>()

    val h4Fast = SequentialEma(20)
    val h4Slow = SequentialEma(50)
    h4.forEachIndexed { index, candle ->
        val fast = h4Fast.add(candle.close)
        val slow = h4Slow.add(candle.close)
        if (index >= 49 && fast != null && slow != null) {
            val bias = when {
                candle.close > slow && fast > slow -> SmcDirection.BULLISH
                candle.close < slow && fast < slow -> SmcDirection.BEARISH
                else -> SmcDirection.NEUTRAL
            }
            events += SequentialSignalEvent(SequentialSignalStage.H4_BIAS, bias, candle.closeTime)
        }
    }

    val pivotHighs = mutableListOf<Double>()
    val pivotLows = mutableListOf<Double>()
    h1.forEachIndexed { index, _ ->
        val center = index - 2
        if (center >= 2) {
            val candidate = h1[center]
            val neighbours = (center - 2..center + 2).filter { it != center }
            if (neighbours.all { candidate.high > h1[it].high }) {
                pivotHighs += candidate.high
                if (pivotHighs.size > 2) pivotHighs.removeAt(0)
            }
            if (neighbours.all { candidate.low < h1[it].low }) {
                pivotLows += candidate.low
                if (pivotLows.size > 2) pivotLows.removeAt(0)
            }
        }
        if (index >= 8) {
            val bias = if (pivotHighs.size < 2 || pivotLows.size < 2) {
                SmcDirection.NEUTRAL
            } else {
                val bullish = pivotHighs[1] > pivotHighs[0] && pivotLows[1] > pivotLows[0]
                val bearish = pivotHighs[1] < pivotHighs[0] && pivotLows[1] < pivotLows[0]
                when {
                    bullish -> SmcDirection.BULLISH
                    bearish -> SmcDirection.BEARISH
                    else -> SmcDirection.NEUTRAL
                }
            }
            events += SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, bias, h1[index].closeTime)
        }
    }

    val m15Ema = SequentialEma(20)
    val trueRanges = mutableListOf<Double>()
    val priorVolumes = mutableListOf<Double>()
    m15.forEachIndexed { index, current ->
        val ema20 = m15Ema.add(current.close)
        if (index > 0) {
            val previous = m15[index - 1]
            trueRanges += maxOf(
                current.high - current.low,
                kotlin.math.abs(current.high - previous.close),
                kotlin.math.abs(current.low - previous.close)
            )
            if (trueRanges.size > 14) trueRanges.removeAt(0)
        }
        if (index >= 44 && ema20 != null && trueRanges.size == 14 && priorVolumes.size == 20) {
            val atr = trueRanges.average()
            val averageVolume = priorVolumes.average()
            if (atr.isFinite() && atr > 0.0 && averageVolume > 0.0) {
                val previous = m15[index - 1]
                val participation = confluencePullbackVolumeConfirmed(previous.volume, current.volume, averageVolume)
                val bullishSetup = current.low <= ema20 + atr * .2 && current.low >= ema20 - atr * .7 &&
                    current.close > ema20 && current.close > previous.close && participation
                val bearishSetup = current.high >= ema20 - atr * .2 && current.high <= ema20 + atr * .7 &&
                    current.close < ema20 && current.close < previous.close && participation
                if (bullishSetup) events += SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, current.closeTime)
                if (bearishSetup) events += SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BEARISH, current.closeTime)
            }
        }
        priorVolumes += current.volume
        if (priorVolumes.size > 20) priorVolumes.removeAt(0)
    }

    val m5Fast = SequentialEma(9)
    val m5Slow = SequentialEma(21)
    m5.forEachIndexed { index, candle ->
        val fast = m5Fast.add(candle.close)
        val slow = m5Slow.add(candle.close)
        if (index >= 29 && fast != null && slow != null) {
            if (candle.close > fast && fast > slow) {
                events += SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, candle.closeTime)
            }
            if (candle.close < fast && fast < slow) {
                events += SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BEARISH, candle.closeTime)
            }
        }
    }
    return events.sortedBy { it.closeTime }
}
