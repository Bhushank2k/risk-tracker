package com.risktracker.app

import android.content.Context
import org.json.JSONObject

/** Stores the latest import audit per selected year on this device. */
class BinanceImportAuditStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_income_audit_v1", Context.MODE_PRIVATE)
    private val backupPrefs = context.getSharedPreferences("binance_income_audit_restore_v1", Context.MODE_PRIVATE)

    /** Snapshot the current audit, including whether no audit existed, before replacing yearly data. */
    fun backup(year: Int) {
        require(year in 2018..2026)
        val existing = prefs.getString("year_$year", null)
        val payload = JSONObject().put("hadReport", existing != null)
        if (existing != null) payload.put("report", JSONObject(existing))
        check(backupPrefs.edit().putString("year_$year", payload.toString()).commit()) { "Could not commit the previous import audit snapshot." }
    }

    /** Restore the audit that matched the saved pre-import Futures values. */
    fun restoreBackup(year: Int): Boolean {
        if (year !in 2018..2026) return false
        return try {
            val raw = backupPrefs.getString("year_$year", null) ?: return false
            val payload = JSONObject(raw)
            require(payload.has("hadReport")) { "Audit snapshot is missing its presence marker." }
            val hadReport = payload.getBoolean("hadReport")
            if (hadReport) {
                val previous = payload.getJSONObject("report").toString()
                check(prefs.edit().putString("year_$year", previous).commit())
            } else {
                check(prefs.edit().remove("year_$year").commit())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

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
            .put("duplicateRows", report.duplicateRows)
        check(prefs.edit().putString("year_$year", payload.toString()).commit()) { "Could not commit the import audit." }
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
                lastActivityDate = payload.getString("lastActivityDate"),
                duplicateRows = payload.optInt("duplicateRows", 0)
            )
        } catch (_: Exception) {
            null
        }
    }
}
