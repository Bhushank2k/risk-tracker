package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max

internal enum class BestOpportunityStatus { CONFIRMED, WAITING, CONFLICT, REJECTED }

internal data class BestOpportunity(
    val id: String,
    val symbol: String,
    val side: String,
    val horizon: String,
    val priorityScore: Int,
    val status: BestOpportunityStatus,
    val sources: Set<String>,
    val idea: TradeIdea?,
    val smc: UnifiedSMCDisplaySignal?,
    val eligibility: TradeIdeaEligibility?,
    val explanation: String,
    val detectedAt: Long
)

/**
 * Cross-scanner shortlist. A Daily Trade Idea is only paired with an SMC candidate
 * when the direction and entry area agree. Missing execution data never becomes a
 * confirmation: SMC-only and Daily-only rows remain WAITING until both engines agree.
 */
internal fun buildBestOpportunities(
    tradeIdeas: List<TradeIdea>,
    smcSignals: List<UnifiedSMCDisplaySignal>,
    tradeIdeaSnapshotAgeMs: Long,
    now: Long,
    smcFreshnessMs: Long = 15L * 60L * 1000L,
    tradeIdeaSnapshotComplete: Boolean = true
): List<BestOpportunity> {
    data class Draft(val id: String, val idea: TradeIdea?, val smc: UnifiedSMCDisplaySignal?)

    val unmatchedIdeas = tradeIdeas.toMutableList()
    val drafts = mutableListOf<Draft>()

    smcSignals.sortedByDescending { it.score }.forEach { signal ->
        val match = unmatchedIdeas
            .filter { it.symbol.equals(signal.symbol, ignoreCase = true) && it.side == signal.side }
            .mapNotNull { idea ->
                val overlap = entryAgreement(idea, signal) ?: return@mapNotNull null
                idea to overlap
            }
            .maxByOrNull { it.second }
            ?.first
        if (match != null) unmatchedIdeas.remove(match)
        drafts += Draft(
            id = "smc:${signal.id}" + (match?.let { "|idea:${ideaIdentity(it)}" } ?: ""),
            idea = match,
            smc = signal
        )
    }
    unmatchedIdeas.forEach { idea -> drafts += Draft("idea:${ideaIdentity(idea)}", idea, null) }

    val preliminary = drafts.map { draft ->
        val idea = draft.idea
        val smc = draft.smc
        val ideaEligibility = idea?.let { evaluateTradeIdeaEligibility(it, snapshotAgeMs = tradeIdeaSnapshotAgeMs) }
        val smcAge = smc?.detectedAt?.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val smcFresh = smc != null && smcAge <= smcFreshnessMs
        val status = when {
            ideaEligibility?.state == TradeIdeaEligibilityState.AVOID -> BestOpportunityStatus.REJECTED
            !tradeIdeaSnapshotComplete -> BestOpportunityStatus.WAITING
            idea != null && smc != null && smcFresh &&
                ideaEligibility?.state == TradeIdeaEligibilityState.READY -> BestOpportunityStatus.CONFIRMED
            else -> BestOpportunityStatus.WAITING
        }
        val sourceScores = listOfNotNull(idea?.score, smc?.score)
        val priority = (sourceScores.maxOrNull() ?: 0) + if (idea != null && smc != null) 8 else 0
        val explanation = buildList {
            if (idea != null) add("Daily Trade Ideas: ${idea.setup} · ${ideaEligibility?.reason ?: "validation pending"}")
            if (smc != null) add("SMC: ${smc.patterns.joinToString()}. ${if (smcFresh) "Fresh SMC snapshot" else "SMC snapshot is stale or unavailable"}")
            if (idea == null) add("No matching Daily Trade Idea yet; live spread, depth, funding and execution gates are not confirmed.")
            if (smc == null) add("No matching SMC structure confirmation yet.")
        }.joinToString("\n")
        BestOpportunity(
            id = draft.id,
            symbol = idea?.symbol ?: smc!!.symbol,
            side = idea?.side ?: smc!!.side,
            horizon = idea?.horizon ?: smc!!.horizon,
            priorityScore = priority.coerceIn(0, 108),
            status = status,
            sources = buildSet {
                if (idea != null) add("Daily Trade Ideas")
                if (smc != null) addAll(smc.sources)
            },
            idea = idea,
            smc = smc,
            eligibility = ideaEligibility,
            explanation = explanation,
            detectedAt = max(idea?.let { now - tradeIdeaSnapshotAgeMs } ?: 0L, smc?.detectedAt ?: 0L)
        )
    }

    // Opposing directions for one symbol are retained for diagnostics, but neither is promoted.
    val conflictingSymbols = preliminary.groupBy { it.symbol.uppercase() }
        .filterValues { rows -> rows.map { it.side }.distinct().size > 1 }
        .keys

    return preliminary.map { row ->
        if (row.symbol.uppercase() in conflictingSymbols) row.copy(
            status = BestOpportunityStatus.CONFLICT,
            explanation = row.explanation + "\nOpposing LONG/SHORT candidates exist for this symbol across the scanners; wait for direction to resolve."
        ) else row
    }.sortedWith(
        compareBy<BestOpportunity> { statusOrder(it.status) }
            .thenByDescending { it.priorityScore }
            .thenBy { it.symbol }
            .thenBy { it.id }
    )
}

private fun statusOrder(status: BestOpportunityStatus): Int = when (status) {
    BestOpportunityStatus.CONFIRMED -> 0
    BestOpportunityStatus.WAITING -> 1
    BestOpportunityStatus.CONFLICT -> 2
    BestOpportunityStatus.REJECTED -> 3
}

private fun entryAgreement(idea: TradeIdea, smc: UnifiedSMCDisplaySignal): Double? {
    val entry = idea.entry
    if (!entry.isFinite() || entry <= 0.0 || !smc.entryLow.isFinite() || !smc.entryHigh.isFinite() ||
        smc.entryLow > smc.entryHigh) return null
    val tolerance = max(entry * 0.0015, abs(idea.entry - idea.stop) * 0.20)
    val distance = when {
        entry in smc.entryLow..smc.entryHigh -> 0.0
        entry < smc.entryLow -> smc.entryLow - entry
        else -> entry - smc.entryHigh
    }
    if (distance > tolerance) return null
    return 1.0 - (distance / tolerance.coerceAtLeast(1e-9))
}

private fun ideaIdentity(idea: TradeIdea): String =
    listOf(idea.symbol, idea.side, idea.horizon, idea.setup, idea.entry.toBits(), idea.stop.toBits()).joinToString(":")
