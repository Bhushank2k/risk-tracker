package com.risktracker.app

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Imports Binance USDⓈ-M Futures income-history CSV exports.
 *
 * Expected fields are Binance's income-history columns (symbol, incomeType, income,
 * asset, time, tranId, tradeId); header aliases are accepted for common export variants.
 * Only realized P&L, commission and funding income affect daily net P&L.
 */
data class BinanceIncomeImportReport(
    val daily: Map<String, Double>,
    val includedRows: Int,
    val ignoredRows: Int,
    val rowsByType: Map<String, Int>,
    val firstActivityDate: String,
    val lastActivityDate: String
)

object BinanceIncomeCsvImporter {
    private val utc = TimeZone.getTimeZone("UTC")

    fun parse(input: InputStream, selectedYear: Int): Map<String, Double> =
        parseWithReport(input, selectedYear).daily

    fun parseWithReport(input: InputStream, selectedYear: Int): BinanceIncomeImportReport {
        val lines = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readLines() }
            .filter { it.isNotBlank() }
        require(lines.size >= 2) { "The CSV must contain a header and at least one data row." }
        val headers = splitCsvLine(lines.first()).map { it.trim().removePrefix("\uFEFF").lowercase(Locale.US) }
        val timeIndex = headers.firstIndexOfAny("time", "timestamp", "date", "transaction time")
        val typeIndex = headers.firstIndexOfAny("incometype", "income type", "type")
        val amountIndex = headers.firstIndexOfAny("income", "amount", "change")
        require(timeIndex >= 0 && typeIndex >= 0 && amountIndex >= 0) {
            "CSV headers must include time/date, incomeType/type, and income/amount."
        }

        val daily = linkedMapOf<String, Double>()
        val rowsByType = linkedMapOf<String, Int>()
        var accepted = 0
        var ignored = 0
        val activityDates = mutableListOf<String>()
        lines.drop(1).forEachIndexed { rowIndex, line ->
            val columns = splitCsvLine(line)
            if (columns.all { it.isBlank() }) return@forEachIndexed
            require(columns.size > maxOf(timeIndex, typeIndex, amountIndex)) {
                "Malformed CSV row ${rowIndex + 2}: missing expected columns."
            }
            val timestamp = parseTimestamp(columns[timeIndex].trim())
                ?: error("Could not parse timestamp on CSV row ${rowIndex + 2}.")
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.format(Date(timestamp))
            require(day.take(4).toIntOrNull() == selectedYear) {
                "CSV contains a row outside $selectedYear ($day). Export only the selected year and retry."
            }
            val type = columns[typeIndex].trim().uppercase(Locale.US).replace(' ', '_')
            if (type !in setOf("REALIZED_PNL", "COMMISSION", "FUNDING_FEE")) {
                ignored++
                return@forEachIndexed
            }
            val amount = columns[amountIndex].trim().replace(",", "").toDoubleOrNull()
                ?: error("Invalid income amount on CSV row ${rowIndex + 2}.")
            daily[day] = (daily[day] ?: 0.0) + amount
            rowsByType[type] = (rowsByType[type] ?: 0) + 1
            activityDates += day
            accepted++
        }
        require(accepted > 0) {
            "No REALIZED_PNL, COMMISSION, or FUNDING_FEE rows were found. Choose a Binance USDⓈ-M Futures income-history export."
        }
        return BinanceIncomeImportReport(
            daily = daily,
            includedRows = accepted,
            ignoredRows = ignored,
            rowsByType = rowsByType.toMap(),
            firstActivityDate = activityDates.minOrNull() ?: error("No activity dates found."),
            lastActivityDate = activityDates.maxOrNull() ?: error("No activity dates found.")
        )
    }

    private fun parseTimestamp(value: String): Long? {
        value.toLongOrNull()?.let { raw ->
            // Epoch values may be seconds or milliseconds.
            return if (raw in 1L..99_999_999_999L) raw * 1000L else raw
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = utc
                isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return null
    }

    /** RFC 4180-style single-line parser supporting commas and escaped quotes inside fields. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(ch)
            }
            i++
        }
        require(!quoted) { "Malformed CSV: an unclosed quoted field was found." }
        fields += field.toString()
        return fields
    }

    private fun List<String>.firstIndexOfAny(vararg candidates: String): Int =
        indexOfFirst { header -> candidates.any { header == it } }
}
