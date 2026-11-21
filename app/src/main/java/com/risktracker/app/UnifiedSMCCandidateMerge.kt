package com.risktracker.app

internal data class UnifiedSMCCandidate(
    val id: String,
    val symbol: String,
    val side: String,
    val horizon: String,
    val score: Int,
    val entryLow: Double,
    val entryHigh: Double,
    val stop: Double,
    val target: Double,
    val rewardRisk: Double,
    val source: String,
    val pattern: String,
    val status: String,
    val explanation: String,
    val detectedAt: Long
)

internal data class UnifiedSMCDisplaySignal(
    val id: String,
    val symbol: String,
    val side: String,
    val horizon: String,
    val score: Int,
    val entryLow: Double,
    val entryHigh: Double,
    val stop: Double,
    val target: Double,
    val rewardRisk: Double,
    val sources: Set<String>,
    val patterns: Set<String>,
    val status: String,
    val explanation: String,
    val levelSource: String,
    val detectedAt: Long
)

/**
 * Merge only one-to-one, same-direction setups whose entry zones materially overlap.
 * Matching only symbol/side/horizon is not enough: those keys can contain distinct plans.
 */
internal fun mergeUnifiedSMCCandidates(
    core: List<UnifiedSMCCandidate>,
    advanced: List<UnifiedSMCCandidate>
): List<UnifiedSMCDisplaySignal> {
    val availableCore = core.toMutableList()
    val merged = mutableListOf<UnifiedSMCDisplaySignal>()

    core.forEach { candidate ->
        merged += UnifiedSMCDisplaySignal(
            id = "core:${candidate.id}", symbol = candidate.symbol, side = candidate.side,
            horizon = candidate.horizon, score = candidate.score, entryLow = candidate.entryLow,
            entryHigh = candidate.entryHigh, stop = candidate.stop, target = candidate.target,
            rewardRisk = candidate.rewardRisk, sources = setOf("Core SMC"),
            patterns = setOf(candidate.pattern), status = candidate.status,
            explanation = candidate.explanation, levelSource = "Core SMC levels",
            detectedAt = candidate.detectedAt
        )
    }

    advanced.sortedByDescending { it.score }.forEach { candidate ->
        val match = availableCore
            .filter {
                it.symbol == candidate.symbol && it.side == candidate.side &&
                    it.horizon == candidate.horizon && entryZonesMatch(it, candidate)
            }
            .maxByOrNull { entryZoneOverlap(it, candidate) }

        if (match == null) {
            merged += UnifiedSMCDisplaySignal(
                id = "advanced:${candidate.id}", symbol = candidate.symbol, side = candidate.side,
                horizon = candidate.horizon, score = candidate.score, entryLow = candidate.entryLow,
                entryHigh = candidate.entryHigh, stop = candidate.stop, target = candidate.target,
                rewardRisk = candidate.rewardRisk, sources = setOf("Advanced SMC"),
                patterns = setOf(candidate.pattern), status = candidate.status,
                explanation = candidate.explanation, levelSource = "Advanced SMC levels",
                detectedAt = candidate.detectedAt
            )
        } else {
            availableCore.remove(match)
            val index = merged.indexOfFirst { it.id == "core:${match.id}" }
            if (index >= 0) {
                val previous = merged[index]
                merged[index] = previous.copy(
                    score = maxOf(previous.score, candidate.score),
                    sources = previous.sources + "Advanced SMC",
                    patterns = previous.patterns + candidate.pattern,
                    status = "${previous.status} · Advanced: ${candidate.status}",
                    explanation = previous.explanation + "\n" + candidate.explanation +
                        "\nConfluence: Advanced SMC confirms the pattern; Core entry, stop and target levels are retained.",
                    levelSource = "Core SMC levels (Advanced is confirmation only)",
                    detectedAt = maxOf(previous.detectedAt, candidate.detectedAt)
                )
            }
        }
    }
    return merged.sortedWith(compareByDescending<UnifiedSMCDisplaySignal> { it.score }.thenBy { it.symbol }.thenBy { it.id })
}

private fun entryZonesMatch(a: UnifiedSMCCandidate, b: UnifiedSMCCandidate): Boolean =
    entryZoneOverlap(a, b) >= 0.5

/** Intersection divided by the narrower zone width; identical point levels match exactly. */
private fun entryZoneOverlap(a: UnifiedSMCCandidate, b: UnifiedSMCCandidate): Double {
    val overlap = (minOf(a.entryHigh, b.entryHigh) - maxOf(a.entryLow, b.entryLow)).coerceAtLeast(0.0)
    val narrowerWidth = minOf(a.entryHigh - a.entryLow, b.entryHigh - b.entryLow)
    if (narrowerWidth <= 0.0) return if (a.entryLow == b.entryLow && a.entryHigh == b.entryHigh) 1.0 else 0.0
    return overlap / narrowerWidth
}
