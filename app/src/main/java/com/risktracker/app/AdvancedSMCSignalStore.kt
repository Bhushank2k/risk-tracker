package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists Advanced SMC scan status and results for the unified SMC screen
 * and background scanner. Kept separate from the retired standalone screen.
 */
internal object AdvancedSMCSignalStore {
    private const val PREFS = "advanced_smc_signals_v1"

    fun update(context: Context, scanned: Int, total: Int, running: Boolean, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("scanned", scanned).putInt("total", total).putBoolean("running", running)
            .putString("message", message).putLong("statusAt", System.currentTimeMillis()).apply()
    }

    fun status(context: Context): JSONObject {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject().put("scanned", p.getInt("scanned", 0)).put("total", p.getInt("total", 0))
            .put("running", p.getBoolean("running", false))
            .put("message", p.getString("message", "Background scan is scheduled") ?: "")
    }

    fun save(context: Context, signals: List<AdvancedSMCSignal>) {
        val rows = JSONArray()
        signals.forEach { s ->
            rows.put(JSONObject().put("id", s.id).put("symbol", s.symbol).put("side", s.side)
                .put("signalType", s.signalType).put("horizon", s.horizon).put("score", s.score)
                .put("status", s.status).put("entryLow", s.entryLow).put("entryHigh", s.entryHigh)
                .put("stop", s.stop).put("target1", s.target1).put("target2", s.target2)
                .put("rewardRisk", s.rewardRisk).put("explanation", s.explanation)
                .put("confirmation", s.confirmation).put("detectedAt", s.detectedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("signals", rows.toString()).putLong("resultsAt", System.currentTimeMillis()).apply()
    }

    fun load(context: Context): List<AdvancedSMCSignal> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("signals", "[]") ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val x = rows.getJSONObject(i)
                AdvancedSMCSignal(
                    id = x.getString("id"), symbol = x.getString("symbol"), side = x.getString("side"),
                    signalType = x.getString("signalType"), horizon = x.getString("horizon"),
                    score = x.getInt("score"), status = x.getString("status"),
                    entryLow = x.getDouble("entryLow"), entryHigh = x.getDouble("entryHigh"),
                    stop = x.getDouble("stop"), target1 = x.getDouble("target1"), target2 = x.getDouble("target2"),
                    rewardRisk = x.getDouble("rewardRisk"), explanation = x.getString("explanation"),
                    confirmation = x.getString("confirmation"), detectedAt = x.getLong("detectedAt")
                )
            }.getOrNull()
        }
    }

    fun resultsUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("resultsAt", 0L)
}
