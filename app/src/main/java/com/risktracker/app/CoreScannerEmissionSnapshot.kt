package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable local record of a Core SMC setup. This source currently exposes one
 * target and no point-in-time indicator context, so those limitations stay explicit.
 */
internal data class CoreScannerEmissionSnapshot(
    val snapshotId: String,
    val signalId: String,
    val symbol: String,
    val side: String,
    val timeframe: String,
    val score: Int,
    val status: String,
    val entryLow: Double,
    val entryHigh: Double,
    val stop: Double,
    val target: Double,
    val rewardRisk: Double,
    val structure: String,
    val sweep: String,
    val displacement: String,
    val zoneDescription: String,
    val location: String,
    val capturedAt: Long,
    val btcBias: String? = null,
    val adx: Double? = null,
    val atrPct: Double? = null,
    val relativeVolume: Double? = null,
    val fundingRate: Double? = null
) {
    fun isValid(): Boolean {
        if (snapshotId.isBlank() || signalId.isBlank() || symbol.isBlank() ||
            timeframe.isBlank() || side !in setOf("LONG", "SHORT") ||
            score !in 0..100 || capturedAt <= 0L) return false
        if (listOf(entryLow, entryHigh, stop, target, rewardRisk).any { !it.isFinite() }) return false
        if (entryLow <= 0.0 || entryHigh < entryLow || stop <= 0.0 || target <= 0.0 || rewardRisk <= 0.0) return false
        val entry = (entryLow + entryHigh) / 2.0
        return if (side == "LONG") stop < entry && target > entry
        else stop > entry && target < entry
    }
}

internal object CoreScannerEmissionSnapshotStore {
    private const val PREFS = "core_scanner_emission_history_v1"
    private const val KEY_ROWS = "snapshots"
    const val DEFAULT_LIMIT = 5_000

    @Synchronized
    fun append(context: Context, snapshots: List<CoreScannerEmissionSnapshot>) {
        if (snapshots.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = decode(prefs.getString(KEY_ROWS, "[]"))
        val byId = LinkedHashMap<String, CoreScannerEmissionSnapshot>()
        rows.forEach { byId[it.snapshotId] = it }
        snapshots.filter { it.isValid() }.forEach { byId[it.snapshotId] = it }
        val retained = byId.values.sortedBy { it.capturedAt }.takeLast(DEFAULT_LIMIT)
        prefs.edit().putString(KEY_ROWS, encode(retained).toString()).apply()
    }

    fun load(context: Context, limit: Int = DEFAULT_LIMIT): List<CoreScannerEmissionSnapshot> =
        decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROWS, "[]")).takeLast(limit.coerceIn(0, DEFAULT_LIMIT))

    private fun encode(rows: List<CoreScannerEmissionSnapshot>) = JSONArray().also { array ->
        rows.forEach { s ->
            array.put(JSONObject()
                .put("snapshotId", s.snapshotId).put("signalId", s.signalId)
                .put("symbol", s.symbol).put("side", s.side).put("timeframe", s.timeframe)
                .put("score", s.score).put("status", s.status).put("entryLow", s.entryLow)
                .put("entryHigh", s.entryHigh).put("stop", s.stop).put("target", s.target)
                .put("rewardRisk", s.rewardRisk).put("structure", s.structure).put("sweep", s.sweep)
                .put("displacement", s.displacement).put("zoneDescription", s.zoneDescription)
                .put("location", s.location).put("capturedAt", s.capturedAt)
                .putNullable("btcBias", s.btcBias).putNullable("adx", s.adx)
                .putNullable("atrPct", s.atrPct).putNullable("relativeVolume", s.relativeVolume)
                .putNullable("fundingRate", s.fundingRate))
        }
    }

    private fun decode(raw: String?): List<CoreScannerEmissionSnapshot> {
        val rows = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val x = rows.getJSONObject(i)
                CoreScannerEmissionSnapshot(
                    snapshotId = x.getString("snapshotId"), signalId = x.getString("signalId"),
                    symbol = x.getString("symbol"), side = x.getString("side"),
                    timeframe = x.getString("timeframe"), score = x.getInt("score"),
                    status = x.getString("status"), entryLow = x.getDouble("entryLow"),
                    entryHigh = x.getDouble("entryHigh"), stop = x.getDouble("stop"),
                    target = x.getDouble("target"), rewardRisk = x.getDouble("rewardRisk"),
                    structure = x.optString("structure"), sweep = x.optString("sweep"),
                    displacement = x.optString("displacement"),
                    zoneDescription = x.optString("zoneDescription"), location = x.optString("location"),
                    capturedAt = x.getLong("capturedAt"), btcBias = x.optNullableString("btcBias"),
                    adx = x.optNullableDouble("adx"), atrPct = x.optNullableDouble("atrPct"),
                    relativeVolume = x.optNullableDouble("relativeVolume"),
                    fundingRate = x.optNullableDouble("fundingRate")
                ).takeIf { it.isValid() }
            }.getOrNull()
        }
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject =
        put(key, value ?: JSONObject.NULL)
    private fun JSONObject.putNullable(key: String, value: Double?): JSONObject =
        put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
