package com.risktracker.app

import android.content.Context
import org.json.JSONObject

/** Stores the latest import audit per selected year on this device. */
class BinanceImportAuditStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_income_audit_v1", Context.MODE_PRIVATE)

    fun save(year: Int, report: BinanceIncomeImportReport) {
        require(year in 2018..2026)
        val daily = JSONObject()
        report.daily.forEach { (date, amount) -> daily.put(date, amount) }
        val rowsByType = JSONObject()
        report.rowsByType.forEach { (type, count) -> rowsByType.put(type, count) }
        val payload = JSONObject()
            .put("daily", daily)
            .put("includedRows", report.includedRows)
            .put("ignoredRows", report.ignoredRows)
            .put("rowsByType", rowsByType)
            .put("firstActivityDate", report.firstActivityDate)
            .put("lastActivityDate", report.lastActivityDate)
        prefs.edit().putString("year_$year", payload.toString()).apply()
    }

    fun load(year: Int): BinanceIncomeImportReport? {
        if (year !in 2018..2026) return null
        return try {
            val payload = prefs.getString("year_$year", null)?.let { JSONObject(it) } ?: return null
            val dailyJson = payload.getJSONObject("daily")
            val daily = buildMap {
                val keys = dailyJson.keys()
                while (keys.hasNext()) {
                    val date = keys.next()
                    put(date, dailyJson.getDouble(date))
                }
            }
            val typeJson = payload.getJSONObject("rowsByType")
            val rowsByType = buildMap {
                val keys = typeJson.keys()
                while (keys.hasNext()) {
                    val type = keys.next()
                    put(type, typeJson.getInt(type))
                }
            }
            BinanceIncomeImportReport(
                daily = daily,
                includedRows = payload.getInt("includedRows"),
                ignoredRows = payload.getInt("ignoredRows"),
                rowsByType = rowsByType,
                firstActivityDate = payload.getString("firstActivityDate"),
                lastActivityDate = payload.getString("lastActivityDate")
            )
        } catch (_: Exception) {
            // Corrupt or older stored audit metadata should not break the sync screen.
            null
        }
    }
}
