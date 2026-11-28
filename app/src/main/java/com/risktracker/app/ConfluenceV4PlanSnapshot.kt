package com.risktracker.app

/**
 * Immutable capture of a Confluence V4 plan after it has been normalized into a
 * TradeIdea. The current TradeIdea boundary does not expose source-candle close
 * timestamps or raw ADX/ATR/BTC benchmark values, so those fields are deliberately
 * absent rather than reconstructed from display strings or unrelated metrics.
 */
internal data class ConfluenceV4PlanSnapshot(
    val snapshotId: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val symbol: String,
    val side: String,
    val horizon: String,
    val setup: String,
    val capturedAt: Long,
    val score: Int,
    val status: String,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val entryTriggered: Boolean,
    val warning: String,
    val reasons: List<String>,
    val relativeVolume: Double?,
    val fundingRate: Double?,
    val spreadBps: Double?,
    val bookImbalance: Double?,
    val bidDepthUsd: Double?,
    val askDepthUsd: Double?,
    val openInterestChangePct: Double?
) {
    fun isValid(): Boolean {
        if (schemaVersion <= 0 || snapshotId.isBlank() || symbol.isBlank() ||
            side !in setOf("LONG", "SHORT") || horizon.isBlank() || setup.isBlank() ||
            capturedAt <= 0L || score !in 0..100) return false
        if (listOf(entry, stop, target1, target2).any { !it.isFinite() || it <= 0.0 }) return false
        if (side == "LONG" && !(stop < entry && target1 > entry && target2 > target1)) return false
        if (side == "SHORT" && !(stop > entry && target1 < entry && target2 < target1)) return false
        return listOfNotNull(relativeVolume, fundingRate, spreadBps, bookImbalance,
            bidDepthUsd, askDepthUsd, openInterestChangePct).all { it.isFinite() }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun from(idea: TradeIdea, capturedAt: Long): ConfluenceV4PlanSnapshot {
            val safeTime = capturedAt.coerceAtLeast(1L)
            val identity = listOf(idea.symbol, idea.side, idea.horizon, idea.setup, safeTime).joinToString("|")
            return ConfluenceV4PlanSnapshot(
                snapshotId = identity,
                symbol = idea.symbol,
                side = idea.side,
                horizon = idea.horizon,
                setup = idea.setup,
                capturedAt = safeTime,
                score = idea.score,
                status = when {
                    idea.warning.startsWith("BLOCK:", true) -> "BLOCKED"
                    idea.entryTriggered -> "READY"
                    else -> "WAIT"
                },
                entry = idea.entry,
                stop = idea.stop,
                target1 = idea.target1,
                target2 = idea.target2,
                entryTriggered = idea.entryTriggered,
                warning = idea.warning,
                reasons = idea.reasons.toList(),
                relativeVolume = idea.volumeRatio.takeIf { it.isFinite() },
                fundingRate = idea.fundingRate,
                spreadBps = idea.spreadBps,
                bookImbalance = idea.bookImbalance,
                bidDepthUsd = idea.bidDepthUsd,
                askDepthUsd = idea.askDepthUsd,
                openInterestChangePct = idea.oiChangePct
            )
        }
    }
}

/** Bounded local capture for Confluence V4 plans. Research-only; no execution side effects. */
internal object ConfluenceV4PlanSnapshotStore {
    private const val PREFS = "confluence_v4_plan_history_v1"
    private const val KEY_ROWS = "snapshots"
    const val DEFAULT_LIMIT = 5_000

    @Synchronized
    fun append(context: android.content.Context, snapshots: List<ConfluenceV4PlanSnapshot>) {
        if (snapshots.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val rows = decode(prefs.getString(KEY_ROWS, "[]"))
        val byId = LinkedHashMap<String, ConfluenceV4PlanSnapshot>()
        rows.forEach { byId[it.snapshotId] = it }
        snapshots.filter { it.isValid() }.forEach { byId[it.snapshotId] = it }
        val retained = byId.values.sortedBy { it.capturedAt }.takeLast(DEFAULT_LIMIT)
        prefs.edit().putString(KEY_ROWS, encode(retained).toString()).apply()
    }

    fun load(context: android.content.Context, limit: Int = DEFAULT_LIMIT): List<ConfluenceV4PlanSnapshot> =
        decode(context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_ROWS, "[]")).takeLast(limit.coerceIn(0, DEFAULT_LIMIT))

    private fun encode(rows: List<ConfluenceV4PlanSnapshot>) = org.json.JSONArray().also { array ->
        rows.forEach { s ->
            array.put(org.json.JSONObject()
                .put("snapshotId", s.snapshotId).put("schemaVersion", s.schemaVersion)
                .put("symbol", s.symbol).put("side", s.side).put("horizon", s.horizon)
                .put("setup", s.setup).put("capturedAt", s.capturedAt).put("score", s.score)
                .put("status", s.status).put("entry", s.entry).put("stop", s.stop)
                .put("target1", s.target1).put("target2", s.target2)
                .put("entryTriggered", s.entryTriggered).put("warning", s.warning)
                .put("reasons", org.json.JSONArray(s.reasons))
                .putNullable("relativeVolume", s.relativeVolume)
                .putNullable("fundingRate", s.fundingRate).putNullable("spreadBps", s.spreadBps)
                .putNullable("bookImbalance", s.bookImbalance).putNullable("bidDepthUsd", s.bidDepthUsd)
                .putNullable("askDepthUsd", s.askDepthUsd).putNullable("openInterestChangePct", s.openInterestChangePct))
        }
    }

    private fun decode(raw: String?): List<ConfluenceV4PlanSnapshot> {
        val rows = runCatching { org.json.JSONArray(raw ?: "[]") }.getOrElse { org.json.JSONArray() }
        return (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val x = rows.getJSONObject(index)
                val reasonRows = x.optJSONArray("reasons") ?: org.json.JSONArray()
                ConfluenceV4PlanSnapshot(
                    snapshotId = x.getString("snapshotId"), schemaVersion = x.optInt("schemaVersion", 1),
                    symbol = x.getString("symbol"), side = x.getString("side"), horizon = x.getString("horizon"),
                    setup = x.getString("setup"), capturedAt = x.getLong("capturedAt"), score = x.getInt("score"),
                    status = x.getString("status"), entry = x.getDouble("entry"), stop = x.getDouble("stop"),
                    target1 = x.getDouble("target1"), target2 = x.getDouble("target2"),
                    entryTriggered = x.getBoolean("entryTriggered"), warning = x.optString("warning"),
                    reasons = (0 until reasonRows.length()).map { reasonRows.optString(it) },
                    relativeVolume = x.optNullableDouble("relativeVolume"), fundingRate = x.optNullableDouble("fundingRate"),
                    spreadBps = x.optNullableDouble("spreadBps"), bookImbalance = x.optNullableDouble("bookImbalance"),
                    bidDepthUsd = x.optNullableDouble("bidDepthUsd"), askDepthUsd = x.optNullableDouble("askDepthUsd"),
                    openInterestChangePct = x.optNullableDouble("openInterestChangePct")
                ).takeIf { it.isValid() }
            }.getOrNull()
        }
    }

    private fun org.json.JSONObject.putNullable(key: String, value: Double?) =
        put(key, value ?: org.json.JSONObject.NULL)

    private fun org.json.JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
