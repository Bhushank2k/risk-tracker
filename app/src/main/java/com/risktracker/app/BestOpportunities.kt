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
 * Daily Trade Ideas is the sole source of watchlist rows. SMC can confirm or
 * conflict with a Daily idea, but SMC-only signals never become standalone
 * opportunities. Missing or stale confirmation remains WAITING.
 */
internal fun buildBestOpportunities(
    tradeIdeas: List<TradeIdea>,
    smcSignals: List<UnifiedSMCDisplaySignal>,
    tradeIdeaSnapshotAgeMs: Long,
    now: Long,
    smcFreshnessMs: Long = 15L * 60L * 1000L,
    tradeIdeaSnapshotComplete: Boolean = true
): List<BestOpportunity> {
    val unmatchedSmc = smcSignals.sortedByDescending { it.score }.toMutableList()

    val preliminary = tradeIdeas.map { idea ->
        val match = unmatchedSmc
            .filter {
                it.symbol.equals(idea.symbol, ignoreCase = true) &&
                    it.side == idea.side &&
                    sameHorizon(it.horizon, idea.horizon)
            }
            .mapNotNull { signal ->
                val agreement = entryAgreement(idea, signal) ?: return@mapNotNull null
                signal to agreement
            }
            .maxByOrNull { it.second }
            ?.first
        if (match != null) unmatchedSmc.remove(match)

        val opposingSignal = smcSignals.any {
            it.symbol.equals(idea.symbol, ignoreCase = true) &&
                it.side != idea.side &&
                sameHorizon(it.horizon, idea.horizon)
        }
        val eligibility = evaluateTradeIdeaEligibility(idea, snapshotAgeMs = tradeIdeaSnapshotAgeMs)
        val smcAge = match?.detectedAt?.takeIf { it > 0L }
            ?.let { (now - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val smcFresh = match != null && smcAge <= smcFreshnessMs

        val dailySnapshotFresh = tradeIdeaSnapshotAgeMs <= tradeIdeaFreshnessLimit(idea)
        val status = when {
            !tradeIdeaSnapshotComplete || !dailySnapshotFresh -> BestOpportunityStatus.WAITING
            eligibility.state == TradeIdeaEligibilityState.AVOID -> BestOpportunityStatus.REJECTED
            opposingSignal -> BestOpportunityStatus.CONFLICT
            match != null && smcFresh && eligibility.state == TradeIdeaEligibilityState.READY ->
                BestOpportunityStatus.CONFIRMED
            else -> BestOpportunityStatus.WAITING
        }
        val priority = max(idea.score, match?.score ?: 0) + if (match != null) 8 else 0
        val explanation = buildList {
            add("Daily Trade Ideas: ${idea.setup} · ${eligibility.reason}")
            if (match != null) {
                add("SMC: ${match.patterns.joinToString()}. ${if (smcFresh) "Fresh SMC snapshot" else "SMC snapshot is stale or unavailable"}")
            } else {
                add("No matching SMC structure confirmation yet.")
            }
            if (opposingSignal) {
                add("SMC has an opposing ${if (idea.side.equals("LONG", true)) "SHORT" else "LONG"} signal for this symbol and horizon; wait for direction to resolve.")
            }
        }.joinToString("\n")

        BestOpportunity(
            id = "idea:${ideaIdentity(idea)}" + (match?.let { "|smc:${it.id}" } ?: ""),
            symbol = idea.symbol,
            side = idea.side,
            horizon = idea.horizon,
            priorityScore = priority.coerceIn(0, 108),
            status = status,
            sources = buildSet {
                add("Daily Trade Ideas")
                if (match != null) addAll(match.sources)
            },
            idea = idea,
            smc = match,
            eligibility = eligibility,
            explanation = explanation,
            detectedAt = max(now - tradeIdeaSnapshotAgeMs, match?.detectedAt ?: 0L)
        )
    }

    // Keep an SMC signal separate when the same symbol has a Daily idea
    // for another horizon; it must not be merged across time horizons.
    val otherHorizonSmc = unmatchedSmc
        .filter { signal ->
            tradeIdeas.any {
                it.symbol.equals(signal.symbol, ignoreCase = true) &&
                    !sameHorizon(it.horizon, signal.horizon)
            }
        }
        .map { signal ->
            BestOpportunity(
                id = "smc:${signal.id}",
                symbol = signal.symbol,
                side = signal.side,
                horizon = signal.horizon,
                priorityScore = signal.score.coerceIn(0, 108),
                status = BestOpportunityStatus.WAITING,
                sources = signal.sources,
                idea = null,
                smc = signal,
                eligibility = null,
                explanation = "SMC signal belongs to a different horizon than the available Daily Trade Idea; kept separate and awaiting its own Daily Trade Idea.",
                detectedAt = signal.detectedAt
            )
        }

    return (preliminary + otherHorizonSmc).sortedWith(
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

private fun tradeIdeaFreshnessLimit(idea: TradeIdea): Long = when {
    idea.horizon.contains("scalp", ignoreCase = true) -> 15L * 60L * 1000L
    idea.horizon.contains("swing", ignoreCase = true) -> 4L * 60L * 60L * 1000L
    else -> 60L * 60L * 1000L
}

private fun sameHorizon(a: String, b: String): Boolean {
    fun bucket(value: String): String = when {
        value.contains("scalp", ignoreCase = true) -> "SCALP"
        value.contains("intraday", ignoreCase = true) -> "INTRADAY"
        value.contains("swing", ignoreCase = true) -> "SWING"
        else -> value.trim().uppercase()
    }
    return bucket(a) == bucket(b)
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
