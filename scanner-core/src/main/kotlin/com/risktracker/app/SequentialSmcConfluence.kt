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
    )
): SequentialConfluenceResult {
    if (direction == SmcDirection.NEUTRAL || decisionTime < 0L)
        return SequentialConfluenceResult(false, "Direction or decision timestamp is invalid.", emptyList())
    val selected = mutableListOf<SequentialSignalEvent>()
    for (stage in SequentialSignalStage.values()) {
        val latest = events.filter { it.stage == stage && it.closeTime <= decisionTime }.maxByOrNull { it.closeTime }
            ?: return SequentialConfluenceResult(false, "Waiting for ${stage.name.replace('_', ' ')} confirmation.", selected)
        if (latest.direction != direction)
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


/** Reconstructs the most recent confirmed event of each stage from closed candle history. */
fun buildSequentialSignalHistory(
    h4: List<V2Candle>,
    h1: List<V2Candle>,
    m15: List<V2Candle>,
    m5: List<V2Candle>,
    side: String
): List<SequentialSignalEvent> {
    val direction = when (side) {
        "LONG" -> SmcDirection.BULLISH
        "SHORT" -> SmcDirection.BEARISH
        else -> return emptyList()
    }
    val events = mutableListOf<SequentialSignalEvent>()

    // Re-evaluate only closed prefixes so every event timestamp is causal.
    for (end in 50..h4.size) {
        val bars = h4.take(end)
        val fast = sequentialEma(bars.map { it.close }, 20) ?: continue
        val slow = sequentialEma(bars.map { it.close }, 50) ?: continue
        val bias = when {
            bars.last().close > slow && fast > slow -> SmcDirection.BULLISH
            bars.last().close < slow && fast < slow -> SmcDirection.BEARISH
            else -> SmcDirection.NEUTRAL
        }
        if (bias != SmcDirection.NEUTRAL) events += SequentialSignalEvent(SequentialSignalStage.H4_BIAS, bias, bars.last().closeTime)
    }

    for (end in 9..h1.size) {
        val bars = h1.take(end)
        val structure = classifyTradeIdeaStructure(bars)
        val bias = when (structure) {
            TradeIdeaStructure.BULLISH -> SmcDirection.BULLISH
            TradeIdeaStructure.BEARISH -> SmcDirection.BEARISH
            else -> SmcDirection.NEUTRAL
        }
        if (bias != SmcDirection.NEUTRAL) events += SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, bias, bars.last().closeTime)
    }

    // A setup event is recorded only on the bar that actually meets the scanner's
    // EMA20 pullback/reclaim + participation criteria, not retroactively on later bars.
    for (end in 45..m15.size) {
        val bars = m15.take(end)
        val closes = bars.map { it.close }
        val ema20 = sequentialEma(closes, 20) ?: continue
        val atr = sequentialAtr(bars) ?: continue
        val current = bars.last()
        val previous = bars[bars.lastIndex - 1]
        val averageVolume = bars.dropLast(1).takeLast(20).map { it.volume }.average()
        if (averageVolume <= 0.0) continue
        val priorQuiet = previous.volume <= averageVolume
        val participation = current.volume >= averageVolume * 1.2 && priorQuiet
        val setup = if (direction == SmcDirection.BULLISH) {
            current.low <= ema20 + atr * .2 && current.low >= ema20 - atr * .7 &&
                current.close > ema20 && current.close > previous.close && participation
        } else {
            current.high >= ema20 - atr * .2 && current.high <= ema20 + atr * .7 &&
                current.close < ema20 && current.close < previous.close && participation
        }
        if (setup) events += SequentialSignalEvent(SequentialSignalStage.M15_SETUP, direction, current.closeTime)
    }

    // Entry event requires the completed 5M candle to be aligned with EMA9/21.
    for (end in 30..m5.size) {
        val bars = m5.take(end)
        val closes = bars.map { it.close }
        val fast = sequentialEma(closes, 9) ?: continue
        val slow = sequentialEma(closes, 21) ?: continue
        val valid = if (direction == SmcDirection.BULLISH) bars.last().close > fast && fast > slow
            else bars.last().close < fast && fast < slow
        if (valid) events += SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, direction, bars.last().closeTime)
    }
    return events.sortedBy { it.closeTime }
}

private fun sequentialEma(values: List<Double>, period: Int): Double? {
    if (values.size < period) return null
    val alpha = 2.0 / (period + 1)
    var result = values.take(period).average()
    values.drop(period).forEach { result = it * alpha + result * (1.0 - alpha) }
    return result.takeIf { it.isFinite() }
}

private fun sequentialAtr(candles: List<V2Candle>): Double? {
    if (candles.size < 16) return null
    return candles.zipWithNext { previous, current ->
        maxOf(current.high - current.low, kotlin.math.abs(current.high - previous.close), kotlin.math.abs(current.low - previous.close))
    }.takeLast(14).average().takeIf { it.isFinite() && it > 0.0 }
}
