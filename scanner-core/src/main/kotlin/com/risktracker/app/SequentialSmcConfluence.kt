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
