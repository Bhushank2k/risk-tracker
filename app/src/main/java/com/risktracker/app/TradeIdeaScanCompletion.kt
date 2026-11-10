package com.risktracker.app

/**
 * An empty signal list is a completed market result only when every evaluated
 * market was actually checked. If any market failed to load, preserve the
 * previous snapshot and surface an incomplete-scan error instead.
 */
internal fun isCompletedEmptyTradeIdeaScan(
    evaluatedMarkets: Int,
    dataFailures: Int
): Boolean = evaluatedMarkets > 0 && dataFailures == 0
