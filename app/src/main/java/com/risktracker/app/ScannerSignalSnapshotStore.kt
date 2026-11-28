package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Append-only local history for scanner emissions. Snapshot IDs are deterministic
 * (signal ID + source candle timestamp), so repeated scans of the same setup do not
 * inflate the history. Storage is bounded to protect app preferences from unbounded growth.
 */
internal object ScannerSignalSnapshotStore {
    private const val PREFS = "scanner_signal_history_v1"
    private const val KEY_ROWS = "snapshots"
    private const val KEY_UPDATED_AT = "updatedAt"
    const val DEFAULT_LIMIT = 5_000
    private const val MAX_LIMIT = 10_000

    @Synchronized
    fun append(context: Context, snapshots: List<ScannerSignalSnapshot>) {
        if (snapshots.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = decode(prefs.getString(KEY_ROWS, "[]"))
        val byId = LinkedHashMap<String, ScannerSignalSnapshot>()
        existing.forEach { byId[it.snapshotId] = it }
        snapshots.filter { it.isValid() }.forEach { byId[it.snapshotId] = it }
        val retained = byId.values.sortedWith(compareBy<ScannerSignalSnapshot> { it.signalTime }
            .thenBy { it.snapshotId }).takeLast(DEFAULT_LIMIT)
        prefs.edit()
            .putString(KEY_ROWS, encode(retained).toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context, limit: Int = DEFAULT_LIMIT): List<ScannerSignalSnapshot> {
        val safeLimit = limit.coerceIn(0, MAX_LIMIT)
        if (safeLimit == 0) return emptyList()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return decode(prefs.getString(KEY_ROWS, "[]")).takeLast(safeLimit)
    }

    fun count(context: Context): Int = load(context, MAX_LIMIT).size

    fun updatedAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED_AT, 0L)

    private fun encode(rows: List<ScannerSignalSnapshot>): JSONArray = JSONArray().also { array ->
        rows.forEach { s ->
            array.put(JSONObject()
                .put("snapshotId", s.snapshotId)
                .put("schemaVersion", s.schemaVersion)
                .put("signalId", s.signalId)
                .put("symbol", s.symbol)
                .put("side", s.side)
                .put("strategy", s.strategy)
                .put("strategyVersion", s.strategyVersion)
                .put("timeframe", s.timeframe)
                .put("signalTime", s.signalTime)
                .put("capturedAt", s.capturedAt)
                .put("score", s.score)
                .put("status", s.status)
                .put("entryLow", s.entryLow)
                .put("entryHigh", s.entryHigh)
                .put("stop", s.stop)
                .put("target1", s.target1)
                .put("target2", s.target2)
                .put("rewardRisk", s.rewardRisk)
                .put("explanation", s.explanation)
                .put("confirmation", s.confirmation)
                .putNullable("btcBias", s.btcBias)
                .putNullable("adx", s.adx)
                .putNullable("atrPct", s.atrPct)
                .putNullable("relativeVolume", s.relativeVolume)
                .putNullable("fundingRate", s.fundingRate))
        }
    }

    private fun decode(raw: String?): List<ScannerSignalSnapshot> {
        val rows = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val x = rows.getJSONObject(index)
                ScannerSignalSnapshot(
                    snapshotId = x.getString("snapshotId"),
                    schemaVersion = x.optInt("schemaVersion", 1),
                    signalId = x.getString("signalId"),
                    symbol = x.getString("symbol"),
                    side = x.getString("side"),
                    strategy = x.getString("strategy"),
                    strategyVersion = x.getString("strategyVersion"),
                    timeframe = x.getString("timeframe"),
                    signalTime = x.getLong("signalTime"),
                    capturedAt = x.getLong("capturedAt"),
                    score = x.getInt("score"),
                    status = x.getString("status"),
                    entryLow = x.getDouble("entryLow"),
                    entryHigh = x.getDouble("entryHigh"),
                    stop = x.getDouble("stop"),
                    target1 = x.getDouble("target1"),
                    target2 = x.getDouble("target2"),
                    rewardRisk = x.getDouble("rewardRisk"),
                    explanation = x.optString("explanation", ""),
                    confirmation = x.optString("confirmation", ""),
                    btcBias = x.optNullableString("btcBias"),
                    adx = x.optNullableDouble("adx"),
                    atrPct = x.optNullableDouble("atrPct"),
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
