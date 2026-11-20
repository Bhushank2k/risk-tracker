package com.risktracker.app

import kotlin.math.abs

internal enum class TradeIdeaEligibilityState { READY, WAIT, AVOID }

internal data class TradeIdeaEligibilityCheck(
    val label: String,
    val passed: Boolean?,
    val hardGate: Boolean
)

internal data class TradeIdeaEligibility(
    val state: TradeIdeaEligibilityState,
    val checks: List<TradeIdeaEligibilityCheck>,
    val estimatedNetR: Double,
    val reason: String
) {
    /** A failed gate is a confirmed rejection; unavailable data is pending, not a failure. */
    val hardGateBlocked: Boolean get() = checks.any { it.hardGate && it.passed == false }
    val hasUnavailableHardGate: Boolean get() = checks.any { it.hardGate && it.passed == null }
}

/**
 * Single source of truth for Trade Ideas approval. Pattern score and RSI are
 * informational; only explicit mandatory gates can approve an entry.
 */
internal fun evaluateTradeIdeaEligibility(
    idea: TradeIdea,
    snapshotAgeMs: Long = 0L,
    requireEntryConfirmation: Boolean = true
): TradeIdeaEligibility {
    val isLong = idea.side == "LONG"
    val validSide = idea.side == "LONG" || idea.side == "SHORT"
    val riskDistance = abs(idea.entry - idea.stop)
    val geometry = validSide && idea.entry.isFinite() && idea.stop.isFinite() &&
        idea.target1.isFinite() && idea.target2.isFinite() && idea.entry > 0.0 &&
        riskDistance > 0.0 && (if (isLong) {
            idea.stop < idea.entry && idea.target1 > idea.entry && idea.target2 > idea.target1
        } else {
            idea.stop > idea.entry && idea.target1 < idea.entry && idea.target2 < idea.target1
        })

    val ageLimit = when {
        idea.horizon.contains("scalp", ignoreCase = true) -> 15 * 60_000L
        idea.horizon.contains("swing", ignoreCase = true) -> 4 * 60 * 60_000L
        else -> 60 * 60_000L
    }
    val fresh = snapshotAgeMs <= 0L || snapshotAgeMs <= ageLimit
    val stopNotHit = validSide && idea.last.isFinite() && idea.last > 0.0 &&
        (if (isLong) idea.last > idea.stop else idea.last < idea.stop)

    val volumeThreshold = confluenceExecutionVolumeThreshold(idea.setup)
    val volumeOk: Boolean? = if (idea.volumeRatio.isFinite()) idea.volumeRatio >= volumeThreshold else null
    val spreadOk: Boolean? = idea.spreadBps?.let {
        if (it.isFinite()) it in 0.0..MAX_TRADE_SPREAD_BPS else null
    }
    val bothDepthSides: Boolean? = if (idea.bidDepthUsd == null || idea.askDepthUsd == null) null else {
        idea.bidDepthUsd.isFinite() && idea.askDepthUsd.isFinite() &&
            idea.bidDepthUsd >= MIN_BOOK_SIDE_DEPTH_USD && idea.askDepthUsd >= MIN_BOOK_SIDE_DEPTH_USD
    }
    val directionalDepthOk: Boolean? = if (idea.spreadBps == null || idea.bookImbalance == null ||
        idea.bidDepthUsd == null || idea.askDepthUsd == null) null else {
        isTradeIdeaLiquidityAcceptable(idea.side, idea.spreadBps, idea.bookImbalance, idea.bidDepthUsd, idea.askDepthUsd)
    }
    val fundingOk: Boolean? = if (idea.fundingRate == null || !idea.fundingRate.isFinite()) null
        else isFundingDataAcceptable(idea.side, idea.fundingRate)

    val grossR = if (riskDistance > 0.0) abs(idea.target1 - idea.entry) / riskDistance else Double.NaN
    val setupMinutes = when {
        idea.horizon.startsWith("Scalp", true) -> 5
        idea.horizon.startsWith("Swing", true) -> 60
        else -> 15
    }
    val netR = if (riskDistance > 0.0) tradeIdeaNetReturnR(
        grossR, idea.entry, riskDistance, heldBars = 8, setupBarMinutes = setupMinutes
    ) else Double.NaN
    val netRewardOk: Boolean? = if (netR.isFinite()) netR >= 2.0 else null

    fun supports(bias: String): Boolean {
        val value = bias.lowercase()
        return if (isLong) listOf("bull", "up", "long", "positive").any { value.contains(it) }
        else listOf("bear", "down", "short", "negative").any { value.contains(it) }
    }
    fun opposes(bias: String): Boolean {
        val value = bias.lowercase()
        return if (isLong) listOf("bear", "down", "short", "negative").any { value.contains(it) }
        else listOf("bull", "up", "long", "positive").any { value.contains(it) }
    }
    val isScalp = idea.horizon.startsWith("Scalp", true)
    val isSwing = idea.horizon.startsWith("Swing", true)
    val trendLabel = if (isScalp) "15M" else if (isSwing) "4H" else "1H"
    val contextLabel = if (isScalp) "1H" else if (isSwing) "1D" else "4H"
    val executionLabel = if (isScalp) "1M" else if (isSwing) "15M" else "5M"
    // V2TradePlan.bias1h stores the horizon-specific trend; bias4h stores the higher context.
    val trendBias = idea.bias1h
    val contextBias = idea.bias4h
    val trendAligned = validSide && supports(trendBias)
    val contextAligned = validSide && supports(contextBias)
    val executionTrend = validSide && supports(idea.bias5m)
    val executionOpposes = validSide && opposes(idea.bias5m)

    val strategyStop = idea.strategyChecks.firstOrNull { it.first.startsWith("Stop-loss", true) }?.second
    val strategyTarget = idea.strategyChecks.firstOrNull { it.first.startsWith("Target 1", true) }?.second
    val confirmation = idea.strategyChecks.firstOrNull { it.first.contains("confirmation candle", true) }?.second
    val strategyStructure = idea.strategyChecks.firstOrNull {
        it.first.contains("swing structure aligned", true) || it.first.contains("trend structure", true)
    }?.second

    val rsiOk = idea.rsi.isFinite() && (if (isLong) idea.rsi in 42.0..68.0 else idea.rsi in 32.0..58.0)
    val setupVolumeReview = idea.horizon.startsWith("Intraday", true) &&
        idea.volumeRatio.isFinite() && idea.volumeRatio >= 1.2
    val oiAvailable = idea.oiChangePct?.isFinite() == true
    val oiChangeAcceptable = idea.oiChangePct?.let { it.isFinite() && it > -1.0 }
    val btcReason = idea.reasons.firstOrNull { it.startsWith("BTC regime", ignoreCase = true) }
    val btcContextAvailable = btcReason != null && !btcReason.contains("unavailable", ignoreCase = true)
    val rsiText = idea.rsi.takeIf { it.isFinite() }?.let { "%.1f".format(java.util.Locale.US, it) } ?: "unavailable"
    val volumeText = idea.volumeRatio.takeIf { it.isFinite() }?.let { "%.2f×".format(java.util.Locale.US, it) } ?: "unavailable"
    val spreadText = idea.spreadBps?.takeIf { it.isFinite() }?.let { "%.1f bps".format(java.util.Locale.US, it) } ?: "unavailable"
    val bidDepthText = idea.bidDepthUsd?.takeIf { it.isFinite() }?.let { "$" + "%,.0f".format(java.util.Locale.US, it) } ?: "unavailable"
    val askDepthText = idea.askDepthUsd?.takeIf { it.isFinite() }?.let { "$" + "%,.0f".format(java.util.Locale.US, it) } ?: "unavailable"
    val imbalanceText = idea.bookImbalance?.takeIf { it.isFinite() }?.let { "%+.2f".format(java.util.Locale.US, it) } ?: "unavailable"
    val fundingText = idea.fundingRate?.takeIf { it.isFinite() }?.let { "%+.4f%%".format(java.util.Locale.US, it * 100.0) } ?: "unavailable"
    val oiText = idea.oiChangePct?.takeIf { it.isFinite() }?.let { "%+.2f%%".format(java.util.Locale.US, it) } ?: "unavailable"
    val netRText = netR.takeIf { it.isFinite() }?.let { "%.2fR".format(java.util.Locale.US, it) } ?: "unavailable"
    val btcText = btcReason?.substringAfter("BTC regime", "")?.trim()?.ifBlank { "unavailable" } ?: "unavailable"
    val blockingRiskCode = idea.riskCodes.any { it.blocksEntry }

    val checks = listOf(
        TradeIdeaEligibilityCheck("$trendLabel trend aligned ($trendBias)", trendAligned, true),
        TradeIdeaEligibilityCheck("$contextLabel context aligned ($contextBias)", contextAligned, true),
        TradeIdeaEligibilityCheck("$executionLabel execution trend aligned (${idea.bias5m})", executionTrend, false),
        TradeIdeaEligibilityCheck("RSI in preferred entry band ($rsiText)", rsiOk, false),
        TradeIdeaEligibilityCheck("Relative volume at least ${"%.1f".format(java.util.Locale.US, volumeThreshold)}× ($volumeText, supporting)", volumeOk, false),
        TradeIdeaEligibilityCheck("Live spread at or below 10 bps ($spreadText)", spreadOk, true),
        TradeIdeaEligibilityCheck("Both 5-level order-book depth sides sufficient ($bidDepthText / $askDepthText)", bothDepthSides, true),
        TradeIdeaEligibilityCheck("Directional order-book liquidity and imbalance acceptable ($imbalanceText)", directionalDepthOk, true),
        TradeIdeaEligibilityCheck("Funding data available and acceptable ($fundingText)", fundingOk, true),
        TradeIdeaEligibilityCheck("Target 1 estimated net reward at least 2.0R ($netRText)", netRewardOk, true),
        TradeIdeaEligibilityCheck("Stop-loss geometry valid", geometry && strategyStop == true, true),
        TradeIdeaEligibilityCheck("Market snapshot fresh", fresh, true),
        TradeIdeaEligibilityCheck("Live price has not invalidated the stop", stopNotHit, true),
        TradeIdeaEligibilityCheck("Trend structure aligned", strategyStructure, true),
        TradeIdeaEligibilityCheck("Entry confirmation candle", confirmation, false),
        TradeIdeaEligibilityCheck("Entry trigger confirmed", idea.entryTriggered, false),
        TradeIdeaEligibilityCheck("Existing READY score threshold", idea.score >= TradeIdeasV2.MIN_SCORE, false),
        TradeIdeaEligibilityCheck("15M relative volume at least 1.2× (supporting)", if (idea.horizon.startsWith("Intraday", true)) setupVolumeReview else null, false),
        TradeIdeaEligibilityCheck("Target 1 strategy validation", strategyTarget, true),
        TradeIdeaEligibilityCheck("Open-interest context available (supporting: $oiText)", oiAvailable, false),
        TradeIdeaEligibilityCheck("Open-interest change above -1.0% (supporting: $oiText)", oiChangeAcceptable, false),
        TradeIdeaEligibilityCheck("BTC market regime context available (supporting: $btcText)", btcContextAvailable, false),
        TradeIdeaEligibilityCheck("Existing scanner hard-risk codes clear", !blockingRiskCode, true)
    )

    val hardFailed = checks.any { it.hardGate && it.passed == false } || blockingRiskCode
    val hardUnknown = checks.any { it.hardGate && it.passed == null }
    val scoreEligible = idea.score >= TradeIdeasV2.MIN_SCORE
    val pendingConfirmation = !executionTrend || !idea.entryTriggered || confirmation != true
    val entryTooFar = run {
        val tolerance = minOf(idea.entry * 0.001, riskDistance * 0.20)
        riskDistance <= 0.0 || !idea.last.isFinite() || abs(idea.last - idea.entry) > tolerance
    }
    val favorableExtension = entryTooFar && (if (isLong) idea.last > idea.entry else idea.last < idea.entry)

    val state: TradeIdeaEligibilityState
    val reason: String
    when {
        !validSide -> { state = TradeIdeaEligibilityState.AVOID; reason = "Invalid trade direction." }
        hardFailed -> { state = TradeIdeaEligibilityState.AVOID; reason = "A mandatory risk, liquidity, funding or structure gate failed." }
        !stopNotHit -> { state = TradeIdeaEligibilityState.AVOID; reason = "Live price has reached or crossed the stop." }
        !fresh -> { state = TradeIdeaEligibilityState.AVOID; reason = "Market snapshot has expired." }
        hardUnknown -> { state = TradeIdeaEligibilityState.WAIT; reason = "A mandatory market-data or strategy-validation value is unavailable; refresh data before considering entry." }
        executionOpposes -> { state = TradeIdeaEligibilityState.WAIT; reason = "Execution timeframe trend currently opposes the trade; wait for alignment and a confirmed trigger." }
        favorableExtension -> { state = TradeIdeaEligibilityState.AVOID; reason = "Price has moved beyond the permitted entry distance; do not chase." }
        requireEntryConfirmation && pendingConfirmation -> { state = TradeIdeaEligibilityState.WAIT; reason = "Mandatory entry confirmation is still pending." }
        !scoreEligible -> { state = TradeIdeaEligibilityState.WAIT; reason = "Setup score is below the existing READY qualification threshold." }
        requireEntryConfirmation && entryTooFar && !favorableExtension -> {
            state = TradeIdeaEligibilityState.WAIT
            reason = "Price has not reached the permitted entry zone yet."
        }
        !requireEntryConfirmation && pendingConfirmation -> { state = TradeIdeaEligibilityState.READY; reason = "Hard risk gates pass; waiting for the entry trigger." }
        else -> { state = TradeIdeaEligibilityState.READY; reason = "All mandatory gates and entry confirmation pass." }
    }
    return TradeIdeaEligibility(state, checks, netR, reason)
}
