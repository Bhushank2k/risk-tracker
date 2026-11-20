package com.risktracker.app

/** Stable identity for a specific plan; direction, setup and levels prevent Compose key collisions. */
internal fun tradeIdeaTrackingKeyParts(
    symbol: String, side: String, horizon: String, setup: String, entry: Double, stop: Double
): String = symbol + "_" + horizon.replace(Regex("[^A-Za-z0-9]"), "") + "_" +
    setup.replace(Regex("[^A-Za-z0-9]"), "") + "_" + side + "_" +
    java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(entry)) + "_" +
    java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(stop))

/** Describes scan snapshot age without changing signal eligibility. */
internal fun tradeIdeaSnapshotAgeLabel(ageMs: Long, maxAgeMs: Long): String {
    if (ageMs == Long.MAX_VALUE || ageMs < 0L) return "Snapshot age unavailable"
    val minutes = ageMs / 60_000L
    val age = when {
        minutes < 1L -> "less than 1m"
        minutes < 60L -> "${minutes}m old"
        else -> "${minutes / 60L}h ${minutes % 60L}m old"
    }
    return if (ageMs > maxAgeMs) "Stale snapshot · $age" else "Snapshot · $age"
}