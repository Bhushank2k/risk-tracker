package com.risktracker.app

import kotlin.math.abs

 data class V2Candle(
    val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long, val open: Double = close
)
 data class V2Microstructure(
    val spreadBps: Double?,
    val bookImbalance: Double?,
    val fundingRate: Double? = null,
    val oiChangePct: Double? = null,
    val bidDepthUsd: Double? = null,
    val askDepthUsd: Double? = null
)

 enum class TradeIdeaRiskCode(val blocksEntry: Boolean) {
    LOW_LIQUIDITY(true),
    INVALID_STOP(true),
    INSUFFICIENT_REWARD_RISK(true),
    ADVERSE_FUNDING(true),
    FUNDING_DATA_UNAVAILABLE(false),
    VOLATILITY_OUT_OF_RANGE(true),
    EXTENDED_ENTRY(true),
    LOW_RELATIVE_VOLUME(true),
    MOMENTUM_NOT_CONFIRMED(false),
    LOW_CONFLUENCE_SCORE(true),
    STALE_MARKET_DATA(true),
    WAITING_FOR_ENTRY_CONFIRMATION(false),
    BELOW_READY_SCORE_THRESHOLD(false),
    COSTS_NOT_INCLUDED(false),
    STRUCTURE_NOT_CONFIRMED(true),
    LEGACY_BLOCKING_WARNING(true)
}

 fun isFundingDataAcceptable(side: String, fundingRate: Double?): Boolean {
    if (side !in setOf("LONG", "SHORT") || fundingRate == null || !fundingRate.isFinite()) return false
    return if (side == "LONG") fundingRate <= 0.001 else fundingRate >= -0.001
}

 fun isTradeIdeaRiskValid(
    side: String,
    entry: Double,
    stop: Double,
    target1: Double,
    target2: Double,
    riskCodes: Set<TradeIdeaRiskCode>
): Boolean {
    if (side !in setOf("LONG", "SHORT")) return false
    if (!entry.isFinite() || !stop.isFinite() || !target1.isFinite() || !target2.isFinite()) return false
    if (entry <= 0.0 || stop <= 0.0 || target1 <= 0.0 || target2 <= 0.0) return false
    val geometryValid = if (side == "LONG") {
        stop < entry && target1 > entry && target2 > target1
    } else {
        stop > entry && target1 < entry && target2 < target1
    }
    val riskDistance = abs(entry - stop)
    val rewardRisk = if (riskDistance > 0.0) abs(target1 - entry) / riskDistance else 0.0
    return geometryValid && rewardRisk >= 2.0 && riskCodes.none { it.blocksEntry }
}

