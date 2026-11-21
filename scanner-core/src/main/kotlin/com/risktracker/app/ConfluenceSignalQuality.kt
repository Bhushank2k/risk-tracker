package com.risktracker.app

/** RSI-only momentum confirmation; MACD is intentionally no longer a scored dependency. */
 fun confluenceRsiMomentumConfirmed(side: String, rangeReversal: Boolean, rsi: Double): Boolean {
    if (!rsi.isFinite()) return false
    return when (side) {
        "LONG" -> if (rangeReversal) rsi <= 42.0 else rsi in 38.0..72.0
        "SHORT" -> if (rangeReversal) rsi >= 58.0 else rsi in 28.0..62.0
        else -> false
    }
}

/** Detect a large completed-candle BTC impulse; this is a warning, never a trade veto. */
 fun detectDirectionalShock(
    candles: List<V2Candle>, now: Long, maxAgeMillis: Long = 30 * 60_000L,
    thresholdPct: Double = 1.0
): String? {
    if (candles.size < 2 || maxAgeMillis < 0L || !thresholdPct.isFinite() || thresholdPct <= 0.0) return null
    val latest = candles.last()
    val previous = candles[candles.lastIndex - 1]
    if (!isTradeIdeaCandleClosed(latest.closeTime, now) ||
        now - latest.closeTime > maxAgeMillis || previous.close <= 0.0 ||
        !latest.close.isFinite() || !previous.close.isFinite()) return null
    val movePct = (latest.close - previous.close) / previous.close * 100.0
    return when {
        !movePct.isFinite() || abs(movePct) < thresholdPct -> null
        movePct > 0.0 -> "Bullish"
        else -> "Bearish"
    }
}

/**
 * Legacy MACD + RSI confirmation retained for compatibility with existing callers.
 * The current Confluence V4 scanner uses confluenceRsiMomentumConfirmed instead.
 * Applies momentum confirmation appropriate to the setup family.
 *
 * Trend continuation requires MACD on the side of zero and moving with the trade.
 * A range reversal instead requires MACD to turn toward the trade; requiring it
 * to already be above/below zero would systematically reject early reversals.
 */
 fun confluenceMomentumConfirmed(
    side: String,
    rangeReversal: Boolean,
    rsi: Double,
    macd: Double,
    previousMacd: Double
): Boolean {
    if (!rsi.isFinite() || !macd.isFinite() || !previousMacd.isFinite()) return false
    return when (side) {
        "LONG" -> if (rangeReversal) rsi <= 42.0 && macd >= previousMacd
            else rsi in 38.0..72.0 && macd > 0.0 && macd >= previousMacd
        "SHORT" -> if (rangeReversal) rsi >= 58.0 && macd <= previousMacd
            else rsi in 28.0..62.0 && macd < 0.0 && macd <= previousMacd
        else -> false
    }
}

/**
 * A transparent 100-point confluence score. Each decision family earns points once:
 * regime, confirmed swing structure, execution, volume, momentum, liquidity, BTC context,
 * stop geometry and ADX/DI. Correlated raw indicators are combined within their family
 * (for example +DI/-DI with ADX and spread + depth + imbalance for liquidity), never
 * awarded separate points. Mandatory safety gates remain independent of this score.
 */
 fun confluenceScore(
    regimeConfirmed: Boolean,
    structureConfirmed: Boolean,
    entryConfirmed: Boolean,
    relativeVolume: Double,
    momentumConfirmed: Boolean,
    liquidityConfirmed: Boolean,
    btcAgainst: Boolean,
    stopGeometryConfirmed: Boolean,
    adxConfirmed: Boolean = true,
    btcContextAvailable: Boolean = true
): Int {
    val volumePoints = when {
        !relativeVolume.isFinite() -> 0
        relativeVolume >= 1.5 -> 10
        relativeVolume >= 1.2 -> 7
        relativeVolume >= 1.0 -> 4
        relativeVolume >= 0.9 -> 2
        else -> 0
    }
    return (if (regimeConfirmed) 15 else 0) +
        (if (structureConfirmed) 25 else 0) +
        (if (entryConfirmed) 15 else 0) +
        volumePoints +
        (if (momentumConfirmed) 10 else 0) +
        (if (liquidityConfirmed) 10 else 0) +
        (if (!btcContextAvailable) 0 else if (btcAgainst) 2 else 5) +
        (if (stopGeometryConfirmed) 5 else 0) +
        (if (adxConfirmed) 5 else 0)
}

/** Require quiet pullback volume followed by renewed participation on the reclaim candle. */
 fun confluencePullbackVolumeConfirmed(previousVolume: Double, reclaimVolume: Double, baselineVolume: Double): Boolean =
    previousVolume.isFinite() && reclaimVolume.isFinite() && baselineVolume.isFinite() &&
        previousVolume >= 0.0 && baselineVolume > 0.0 &&
        previousVolume < baselineVolume &&
        reclaimVolume >= baselineVolume * 0.90 &&
        reclaimVolume >= previousVolume * 0.90

/** Reject sudden volatility expansion relative to the preceding ATR window. */
 fun confluenceVolatilityExpansionAcceptable(currentAtr: Double, baselineAtr: Double, maxExpansion: Double = 1.8): Boolean =
    currentAtr.isFinite() && baselineAtr.isFinite() && maxExpansion.isFinite() &&
        currentAtr > 0.0 && baselineAtr > 0.0 && maxExpansion >= 1.0 && currentAtr <= baselineAtr * maxExpansion

/** A candle is confirmed only after its exchange close timestamp has passed. */
 fun isTradeIdeaCandleClosed(closeTime: Long, now: Long): Boolean = closeTime > 0L && closeTime < now

/** Reject an execution price that has already run too far from the setup level. */
 fun confluenceEntryWithinExtension(entry: Double, setupLevel: Double, atr: Double): Boolean =
    entry.isFinite() && setupLevel.isFinite() && atr.isFinite() && atr > 0.0 &&
        kotlin.math.abs(entry - setupLevel) <= atr * 0.9

/** Minimum relative volume required on the execution candle for this setup family. */
 fun confluenceExecutionVolumeThreshold(setup: String): Double =
    if (setup.contains("breakout", ignoreCase = true)) 1.20 else 0.90
