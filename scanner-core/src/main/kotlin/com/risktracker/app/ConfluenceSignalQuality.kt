package com.risktracker.app

/**
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
 * regime, confirmed swing structure, execution, volume, momentum, liquidity, BTC context
 * and stop geometry. Correlated raw indicators are combined within their family (for
 * example RSI + MACD as momentum and spread + depth + imbalance as liquidity), never
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
    stopGeometryConfirmed: Boolean
): Int {
    val volumePoints = when {
        !relativeVolume.isFinite() -> 0
        relativeVolume >= 1.5 -> 10
        relativeVolume >= 1.2 -> 7
        relativeVolume >= 1.0 -> 4
        relativeVolume >= 0.9 -> 2
        else -> 0
    }
    return (if (regimeConfirmed) 20 else 0) +
        (if (structureConfirmed) 25 else 0) +
        (if (entryConfirmed) 15 else 0) +
        volumePoints +
        (if (momentumConfirmed) 10 else 0) +
        (if (liquidityConfirmed) 10 else 0) +
        (if (btcAgainst) 2 else 5) +
        (if (stopGeometryConfirmed) 5 else 0)
}

/** Require quiet pullback volume followed by renewed participation on the reclaim candle. */
 fun confluencePullbackVolumeConfirmed(previousVolume: Double, reclaimVolume: Double, baselineVolume: Double): Boolean =
    previousVolume.isFinite() && reclaimVolume.isFinite() && baselineVolume.isFinite() &&
        previousVolume >= 0.0 && baselineVolume > 0.0 &&
        previousVolume < baselineVolume * 1.15 &&
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
