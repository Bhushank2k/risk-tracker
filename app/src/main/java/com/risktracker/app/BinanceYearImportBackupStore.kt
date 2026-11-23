package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal fun validateBinanceYearBackupRows(year: Int, rows: List<BinanceDailyResult>) {
    require(year in 2018..2026)
    require(rows.all {
        isValidBinanceCalendarDate(it.date) &&
            it.date.startsWith("$year-") &&
            it.spotPnl.isFinite() &&
            it.futuresPnl.isFinite() &&
            it.futuresIncomeByType.values.all(Double::isFinite)
    }) { "Backup rows must be valid records from the selected year." }
    require(rows.map { it.date }.distinct().size == rows.size) {
        "Backup contains duplicate dates; refusing to discard recovery records."
    }
}

/** Keeps the most recent complete pre-import Futures rows per year for recovery on this device. */
class BinanceYearImportBackupStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_income_import_backup_v1", Context.MODE_PRIVATE)

    /** Synchronously commits the complete year snapshot before any live values are replaced. */
    fun save(year: Int, rows: List<BinanceDailyResult>): Boolean {
        validateBinanceYearBackupRows(year, rows)
        val payload = JSONArray()
        rows.sortedBy { it.date }.forEach { row ->
            val breakdown = JSONObject()
            row.futuresIncomeByType.forEach { (type, amount) -> breakdown.put(type, amount) }
            payload.put(JSONObject()
                .put("date", row.date)
                .put("spotPnl", row.spotPnl)
                .put("futuresPnl", row.futuresPnl)
                .put("futuresIncomeByType", breakdown))
        }
        return prefs.edit().putString("year_$year", payload.toString()).commit()
    }

    fun hasBackup(year: Int): Boolean = year in 2018..2026 && prefs.contains("year_$year")

    fun load(year: Int): List<BinanceDailyResult>? {
        if (year !in 2018..2026) return null
        return try {
            val raw = prefs.getString("year_$year", null) ?: return null
            if (raw.trimStart().startsWith("{")) {
                // Backward compatibility: older app versions stored only { date: futuresPnl }.
                // Income-type detail was not captured in that format and cannot be reconstructed.
                val legacy = JSONObject(raw)
                buildList {
                    val keys = legacy.keys()
                    while (keys.hasNext()) {
                        val date = keys.next()
                        val amount = legacy.getDouble(date)
                        require(isValidBinanceCalendarDate(date) && date.startsWith("$year-") && amount.isFinite())
                        add(BinanceDailyResult(date = date, spotPnl = 0.0, futuresPnl = amount))
                    }
                }.sortedBy { it.date }
            } else {
                val payload = JSONArray(raw)
                (0 until payload.length()).map { index ->
                    val row = payload.getJSONObject(index)
                    val breakdownJson = if (row.has("futuresIncomeByType")) {
                        row.getJSONObject("futuresIncomeByType")
                    } else {
                        error("Recovery snapshot is missing its income-type breakdown.")
                    }
                    val breakdown = buildMap {
                        val keys = breakdownJson.keys()
                        while (keys.hasNext()) {
                            val type = keys.next()
                            val amount = breakdownJson.getDouble(type)
                            require(amount.isFinite())
                            put(type, amount)
                        }
                    }
                    BinanceDailyResult(
                        date = row.getString("date"),
                        spotPnl = row.getDouble("spotPnl"),
                        futuresPnl = row.getDouble("futuresPnl"),
                        futuresIncomeByType = breakdown
                    ).also {
                        require(it.date.take(4).toIntOrNull() == year && it.spotPnl.isFinite() && it.futuresPnl.isFinite())
                    }
                }.also { validateBinanceYearBackupRows(year, it) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
