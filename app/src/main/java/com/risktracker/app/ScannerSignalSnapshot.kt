package com.risktracker.app

/**
 * Immutable, versioned record of what the scanner knew when it emitted a signal.
 * Missing indicator/context values remain null; they are never reconstructed later.
 */
data class ScannerSignalSnapshot(
    val snapshotId: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val signalId: String,
    val symbol: String,
    val side: String,
    val strategy: String,
    val strategyVersion: String,
    val timeframe: String,
    val signalTime: Long,
    val capturedAt: Long,
    val score: Int,
    val status: String,
    val entryLow: Double,
    val entryHigh: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val rewardRisk: Double,
    val explanation: String,
    val confirmation: String,
    val btcBias: String? = null,
    val adx: Double? = null,
    val atrPct: Double? = null,
    val relativeVolume: Double? = null,
    val fundingRate: Double? = null
) {
    val entry: Double get() = (entryLow + entryHigh) / 2.0

    fun toReplaySignal(): ReplaySignal = ReplaySignal(
        id = snapshotId,
        symbol = symbol,
        side = side,
        signalTime = signalTime,
        score = score,
        entry = entry,
        stop = stop,
        target1 = target1,
        target2 = target2,
        entryWasTriggered = false
    )

    fun isValid(): Boolean {
        if (schemaVersion <= 0 || snapshotId.isBlank() || signalId.isBlank() || symbol.isBlank()) return false
        if (side !in setOf("LONG", "SHORT") || score !in 0..100 || signalTime <= 0L || capturedAt < signalTime) return false
        if (strategy.isBlank() || strategyVersion.isBlank() || timeframe.isBlank()) return false
        if (listOf(entryLow, entryHigh, stop, target1, target2, rewardRisk).any { !it.isFinite() }) return false
        if (entryLow <= 0.0 || entryHigh < entryLow || stop <= 0.0 || target1 <= 0.0 || target2 <= 0.0) return false
        val e = entry
        return if (side == "LONG") stop < e && target1 > e && target2 > target1
        else stop > e && target1 < e && target2 < target1
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_STRATEGY_VERSION = "advanced-smc-v1"

        internal fun from(signal: AdvancedSMCSignal, capturedAt: Long): ScannerSignalSnapshot {
            val id = "${signal.id}_${signal.detectedAt}"
            return ScannerSignalSnapshot(
                snapshotId = id,
                signalId = signal.id,
                symbol = signal.symbol,
                side = signal.side,
                strategy = signal.signalType,
                strategyVersion = DEFAULT_STRATEGY_VERSION,
                timeframe = signal.horizon,
                signalTime = signal.detectedAt,
                capturedAt = capturedAt.coerceAtLeast(signal.detectedAt),
                score = signal.score,
                status = signal.status,
                entryLow = signal.entryLow,
                entryHigh = signal.entryHigh,
                stop = signal.stop,
                target1 = signal.target1,
                target2 = signal.target2,
                rewardRisk = signal.rewardRisk,
                explanation = signal.explanation,
                confirmation = signal.confirmation
            )
        }
    }
}
