package com.risktracker.app

/** In-memory counts for risk gates encountered on returned scanner mode plans. */
internal data class ScanGateDiagnostics(
    val evaluatedPlans: Int = 0,
    val blockedPlans: Int = 0,
    val gateHits: Map<TradeIdeaRiskCode, Int> = emptyMap()
) {
    fun record(ideas: List<TradeIdea>): ScanGateDiagnostics {
        if (ideas.isEmpty()) return this
        val nextHits = gateHits.toMutableMap()
        ideas.forEach { idea ->
            idea.riskCodes.forEach { code -> nextHits[code] = (nextHits[code] ?: 0) + 1 }
        }
        return copy(
            evaluatedPlans = evaluatedPlans + ideas.size,
            blockedPlans = blockedPlans + ideas.count { idea -> idea.riskCodes.any { it.blocksEntry } },
            gateHits = nextHits
        )
    }

    fun topGateSummary(limit: Int = 3): String {
        if (limit <= 0) return "none"
        return gateHits.entries
            .sortedWith(compareByDescending<Map.Entry<TradeIdeaRiskCode, Int>> { it.value }.thenBy { it.key.name })
            .take(limit)
            .joinToString(" · ") { (code, count) -> "${code.name.lowercase().replace('_', ' ')}: $count" }
            .ifBlank { "no risk-code hits" }
    }
}
