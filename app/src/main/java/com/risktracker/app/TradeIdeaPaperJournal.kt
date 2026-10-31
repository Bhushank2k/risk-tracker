package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal data class PaperSetupStats(
    val setup: String,
    val closedTrades: Int,
    val wins: Int,
    val averageR: Double
) {
    val winRatePercent: Double get() = if (closedTrades == 0) 0.0 else wins.toDouble() / closedTrades * 100.0
}

internal object TradeIdeaPaperJournal {
    private const val KEY = "paper_trade_history_v1"

    private fun history(context: Context): JSONArray = runCatching {
        JSONArray(context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    }.getOrElse { JSONArray() }

    private fun save(context: Context, rows: JSONArray) {
        val trimmed = JSONArray()
        val start = (rows.length() - 300).coerceAtLeast(0)
        for (i in start until rows.length()) trimmed.put(rows.getJSONObject(i))
        context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, trimmed.toString()).apply()
    }

    fun recordEntry(context: Context, symbol: String, setup: String, side: String, entry: Double, stop: Double) {
        val rows = history(context)
        if ((0 until rows.length()).any { i ->
                val row = rows.optJSONObject(i)
                row?.optString("symbol") == symbol && !row.has("closedAt")
            }) return
        rows.put(JSONObject().put("symbol", symbol).put("setup", setup).put("side", side)
            .put("entry", entry).put("stop", stop).put("openedAt", System.currentTimeMillis()))
        save(context, rows)
    }

    fun recordPartial(context: Context, symbol: String, partialR: Double = 0.75) {
        val rows = history(context)
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("symbol") == symbol && !row.has("closedAt")) {
                rows.put(i, JSONObject(row.toString()).put("partialR", partialR))
                save(context, rows)
                return
            }
        }
    }

    fun recordExit(context: Context, symbol: String, exitPrice: Double, outcome: String) {
        val rows = history(context)
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("symbol") != symbol || row.has("closedAt")) continue
            val entry = row.optDouble("entry", 0.0)
            val stop = row.optDouble("stop", 0.0)
            val side = row.optString("side")
            val risk = abs(entry - stop)
            if (risk <= 0.0) return
            val rawR = if (side == "LONG") (exitPrice - entry) / risk else (entry - exitPrice) / risk
            val partialR = row.optDouble("partialR", 0.0)
            val rMultiple = when {
                outcome == "TARGET2" -> 2.0
                partialR != 0.0 -> partialR + rawR * 0.5
                else -> rawR
            }
            val closed = JSONObject(row.toString())
                .put("exitPrice", exitPrice)
                .put("outcome", outcome)
                .put("rMultiple", rMultiple)
                .put("closedAt", System.currentTimeMillis())
            rows.put(i, closed)
            save(context, rows)
            return
        }
    }

    fun stats(context: Context): List<PaperSetupStats> {
        val rows = history(context)
        val closed = (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.takeIf { it.has("closedAt") && it.optString("outcome") != "INVALIDATED" }
        }
        return closed.groupBy { it.optString("setup", "Trend continuation") }.map { (setup, trades) ->
            val r = trades.map { it.optDouble("rMultiple", 0.0) }
            PaperSetupStats(setup, trades.size, r.count { it > 0.0 }, r.average())
        }.sortedByDescending { it.closedTrades }
    }
}
