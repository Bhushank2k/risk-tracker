package com.risktracker.app

import kotlin.math.abs

/**
 * Strict Quality Mode is a conservative, advisory-only overlay on the existing scanner.
 * It never submits orders and never relaxes a V2 hard gate.
 */
internal object TradeIdeasV3 {
    const val MIN_SCORE = 75
    const val MIN_QUOTE_VOLUME = 10_000_000.0
    const val MAX_SPREAD_BPS = 10.0
    const val MAX_TRACKED_POSITIONS = 2
    const val MAX_TOTAL_OPEN_RISK_PERCENT = 1.0
    const val MAX_DAILY_LOSS_PERCENT = 1.5
    const val MAX_CONSECUTIVE_LOSSES = 2

    data class Assessment(
        val score: Int,
        val blockers: List<String>,
        val checks: List<Pair<String, Boolean>>,
        val state: String
    )

    fun assess(
        idea: TradeIdea,
        snapshotAgeMs: Long,
        trackedPositionCount: Int,
        isAlreadyTracked: Boolean,
        dailyLossPercent: Double,
        consecutiveLosses: Int,
        openPaperPositions: Int
    ): Assessment {
        val longSide = idea.side == "LONG"
        val sideValid = idea.side == "LONG" || idea.side == "SHORT"
        val expectedBias = if (longSide) "bullish" else "bearish"
        fun aligned(value: String) = value.lowercase().contains(expectedBias)
        val trend4h = aligned(idea.bias4h)
        val trend1h = aligned(idea.bias1h)
        val executionTrend = aligned(idea.bias5m)
        val volumeOk = idea.volume.isFinite() && idea.volume >= MIN_QUOTE_VOLUME
        val relativeVolumeOk = idea.volumeRatio.isFinite() && idea.volumeRatio >= 1.2
        val rsiOk = idea.rsi.isFinite() && if (longSide) idea.rsi in 35.0..68.0 else idea.rsi in 32.0..65.0
        val spreadOk = idea.spreadBps?.let { it.isFinite() && it in 0.0..MAX_SPREAD_BPS } == true
        val depthOk = idea.bookImbalance?.let {
            it.isFinite() && it in -1.0..1.0 && (if (longSide) it >= -0.15 else it <= 0.15)
        } == true
        val fundingAvailable = idea.fundingRate?.isFinite() == true
        val fundingOk = idea.fundingRate?.let {
            it.isFinite() && (if (longSide) it <= 0.0008 else it >= -0.0008)
        } ?: true // Missing funding is neutral for the hard gate, not positive evidence.
        val fundingScore = if (!fundingAvailable) 2 else if (fundingOk) 5 else 0
        val oiOk = idea.oiChangePct?.let {
            it.isFinite() && it >= 0.3 && (if (longSide) idea.change >= 0.0 else idea.change <= 0.0)
        } == true
        val riskDistance = abs(idea.entry - idea.stop)
        val reward = abs(idea.target1 - idea.entry)
        val geometryOk = sideValid && idea.entry.isFinite() && idea.stop.isFinite() &&
            idea.target1.isFinite() && idea.target2.isFinite() && idea.entry > 0.0 &&
            riskDistance > 0.0 && reward > 0.0 &&
            (if (longSide) idea.stop < idea.entry && idea.target1 > idea.entry && idea.target2 > idea.target1
             else idea.stop > idea.entry && idea.target1 < idea.entry && idea.target2 < idea.target1)
        val rrOk = geometryOk && reward / riskDistance >= 1.5
        val patternOk = !idea.setup.contains("watch", ignoreCase = true) &&
            !idea.setup.contains("disagreement", ignoreCase = true)
        val horizonMaxAge = when {
            idea.horizon.contains("scalp", ignoreCase = true) -> 15 * 60_000L
            idea.horizon.contains("swing", ignoreCase = true) -> 4 * 60 * 60_000L
            else -> 60 * 60_000L
        }
        val fresh = snapshotAgeMs in 0..horizonMaxAge
        val priceValid = idea.last.isFinite() && idea.last > 0.0 &&
            (if (longSide) idea.last > idea.stop else idea.last < idea.stop)
        val notChasing = riskDistance > 0.0 &&
            abs(idea.last - idea.entry) <= minOf(idea.entry * 0.003, riskDistance * 0.50)
        val activePositions = maxOf(trackedPositionCount, openPaperPositions)
        val capacityOk = isAlreadyTracked || activePositions < MAX_TRACKED_POSITIONS
        val dailyRiskOk = isAlreadyTracked || (dailyLossPercent < MAX_DAILY_LOSS_PERCENT && consecutiveLosses < MAX_CONSECUTIVE_LOSSES)
        val totalOpenRiskOk = isAlreadyTracked || openPaperPositions * 0.5 < MAX_TOTAL_OPEN_RISK_PERCENT

        val checks = listOf(
            "4H trend agrees with direction" to trend4h,
            "1H trend agrees with direction" to trend1h,
            "Execution-timeframe trend agrees" to executionTrend,
            "24h quote volume ≥ $10M" to volumeOk,
            "Relative volume ≥ 1.2×" to relativeVolumeOk,
            "RSI in non-extended zone" to rsiOk,
            "Spread ≤ 10 bps" to spreadOk,
            "Directional order-book depth available" to depthOk,
            "Funding not extreme against position" to fundingOk,
            "Open interest supports participation" to oiOk,
            "Setup pattern confirmed" to patternOk,
            "Target 1 reward/risk ≥ 1.5R" to rrOk,
            "Signal snapshot still fresh" to fresh,
            "Price has not invalidated or chased the plan" to (priceValid && notChasing),
            "Portfolio capacity available (max 2 positions / 1% open risk)" to (capacityOk && totalOpenRiskOk),
            "Daily loss below 1.5% and fewer than 2 consecutive losses" to dailyRiskOk
        )

        val blockers = mutableListOf<String>()
        if (!sideValid) blockers += "No directional LONG/SHORT signal."
        if (idea.warning.startsWith("BLOCK:", ignoreCase = true)) blockers += idea.warning.removePrefix("BLOCK:").trim()
        if (!trend4h || !trend1h) blockers += "4H and 1H trend alignment is missing."
        if (!executionTrend) blockers += "5M execution trend does not confirm this direction."
        if (!volumeOk) blockers += "24h quote volume is below $10M."
        if (!relativeVolumeOk) blockers += "Relative volume is below 1.2×."
        if (!rsiOk) blockers += "RSI is outside the permitted non-extended range."
        if (!spreadOk) blockers += "Spread is unavailable or exceeds 10 bps."
        if (!depthOk) blockers += "Directional order-book depth is unavailable or insufficient."
        if (!fundingOk) blockers += "Funding is extreme and adverse to this direction."
        if (!patternOk) blockers += "No confirmed setup pattern."
        if (!geometryOk) blockers += "Stop/target geometry is invalid."
        if (!rrOk) blockers += "Target 1 reward/risk is below 1.5R."
        if (!fresh) blockers += "Signal expired; a fresh market scan is required."
        if (!priceValid) blockers += "Price has invalidated the stop."
        else if (!notChasing) blockers += "Price moved too far from planned entry; do not chase."
        if (!capacityOk || !totalOpenRiskOk) blockers += "Portfolio cap reached: maximum 2 active positions and 1% illustrative combined open risk."
        if (!dailyRiskOk) blockers += "Daily paper risk stop reached (1.5% realized loss or 2 consecutive losses); no new entries until the next day."
        val score = (
            (if (trend4h && trend1h) 20 else 0) +
            (if (executionTrend) 20 else 0) +
            (if (relativeVolumeOk) 8 else 0) + (if (rsiOk) 7 else 0) +
            (if (spreadOk) 7 else 0) + (if (depthOk) 8 else 0) +
            (if (patternOk) 10 else 0) +
            (if (rrOk) 10 else 0) +
            fundingScore + (if (oiOk) 5 else 0)
        ).coerceIn(0, 100)
        if (score < MIN_SCORE) blockers += "Strict Quality score is below 75/100."
        val distinctBlockers = blockers.distinct()
        val state = when {
            distinctBlockers.isNotEmpty() -> "BLOCKED"
            !idea.entryTriggered -> "WAIT"
            else -> "READY"
        }
        return Assessment(score, distinctBlockers, checks, state)
    }
}
