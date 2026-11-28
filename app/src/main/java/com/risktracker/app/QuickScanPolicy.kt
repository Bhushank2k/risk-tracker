package com.risktracker.app

import kotlin.math.abs
import kotlin.math.ln1p

/** Bounded work sizes for the all-market Quick Scan pipeline. */
internal const val QUICK_SCAN_DEEP_SCAN_CONCURRENCY = 8
internal const val QUICK_SCAN_CANDLE_FETCH_CONCURRENCY = 8
internal const val QUICK_SCAN_CANDLE_REQUEST_INTERVAL_MS = 150L
internal const val QUICK_SCAN_DEPTH_BATCH_SIZE = 150

/**
 * Prioritizes evaluation order using bulk 24h ticker data only.
 * This is never an eligibility gate: every market passing the volume floor is scanned.
 */
internal fun quickScanMarketPriority(
    volatilityPct: Double,
    changePct: Double,
    quoteVolume: Double
): Double {
    if (!volatilityPct.isFinite() || !changePct.isFinite() ||
        !quoteVolume.isFinite() || quoteVolume < 0.0
    ) return Double.NEGATIVE_INFINITY

    return volatilityPct.coerceAtLeast(0.0) +
        abs(changePct) * 0.5 +
        ln1p(quoteVolume / 5_000_000.0) * 0.15
}

/** Optional top-market cap retained for profiles that explicitly request it. */
internal const val QUICK_SETUP_TOP_MARKET_LIMIT = 50

/** The default and unknown profile modes scan the complete active USDT-M perpetual universe. */
internal fun quickSetupMarketLimit(markets: String): Int? = when (markets) {
    "top" -> QUICK_SETUP_TOP_MARKET_LIMIT
    "all" -> null
    else -> null
}
