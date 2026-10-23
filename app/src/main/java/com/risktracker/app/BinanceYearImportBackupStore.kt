package com.risktracker.app

import android.content.Context
import org.json.JSONObject

/** Keeps the most recent pre-import Futures values per year for recovery on this device. */
class BinanceYearImportBackupStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_income_import_backup_v1", Context.MODE_PRIVATE)

    fun save(year: Int, daily: Map<String, Double>) {
        require(year in 2018..2026)
        val values = JSONObject()
        daily.forEach { (date, amount) -> values.put(date, amount) }
        prefs.edit().putString("year_$year", values.toString()).apply()
    }

    fun hasBackup(year: Int): Boolean = year in 2018..2026 && prefs.contains("year_$year")

    fun load(year: Int): Map<String, Double>? {
        if (year !in 2018..2026) return null
        return try {
            val payload = prefs.getString("year_$year", null)?.let { JSONObject(it) } ?: return null
            buildMap {
                val keys = payload.keys()
                while (keys.hasNext()) {
                    val date = keys.next()
                    put(date, payload.getDouble(date))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
