package com.risktracker.app

/**
 * Applies momentum confirmation appropriate to the setup family.
 *
 * Trend continuation requires MACD on the side of zero and moving with the trade.
 * A range reversal instead requires MACD to turn toward the trade; requiring it
 * to already be above/below zero would systematically reject early reversals.
 */
internal fun confluenceMomentumConfirmed(
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
 * A transparent 100-point confluence score. Hard risk checks remain independent
 * blockers; the score never overrides funding, liquidity, stop, volatility,
 * extension, relative-volume, momentum, or reward/risk failures.
 */
internal fun confluenceScore(
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

/** Reject an execution price that has already run too far from the setup level. */
internal fun confluenceEntryWithinExtension(entry: Double, setupLevel: Double, atr: Double): Boolean =
    entry.isFinite() && setupLevel.isFinite() && atr.isFinite() && atr > 0.0 &&
        kotlin.math.abs(entry - setupLevel) <= atr * 0.9
