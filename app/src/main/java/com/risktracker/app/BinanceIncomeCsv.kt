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
 * Daily net trading P&L uses the same income types as the live API collector:
 * realized P&L, commission, funding fees and insurance-clear adjustments.
 * Transfers and other non-trading cash movements remain excluded.
 */
data class BinanceIncomeImportReport(
    val daily: Map<String, Double>,
    val includedRows: Int,
    val ignoredRows: Int,
    val rowsByType: Map<String, Int>,
    val firstActivityDate: String,
    val lastActivityDate: String,
    val duplicateRows: Int = 0
)

object BinanceIncomeCsvImporter {
    private val utc = TimeZone.getTimeZone("UTC")
    // Keep this set aligned with BinanceApi.futuresDailyPnl().
    private val includedTypes = setOf("REALIZED_PNL", "COMMISSION", "FUNDING_FEE", "INSURANCE_CLEAR")

    fun parse(input: InputStream, selectedYear: Int): Map<String, Double> =
        parseWithReport(input, selectedYear).daily

    fun parseWithReport(input: InputStream, selectedYear: Int): BinanceIncomeImportReport {
        require(selectedYear in 2018..2026) { "Selected year must be between 2018 and 2026." }
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
        val seenRows = mutableSetOf<String>()
        var accepted = 0
        var ignored = 0
        var duplicates = 0
        val activityDates = mutableListOf<String>()
        lines.drop(1).forEachIndexed { rowIndex, line ->
            val rowNumber = rowIndex + 2
            val columns = try {
                splitCsvLine(line)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Malformed CSV row $rowNumber: ${e.message}", e)
            }
            if (columns.all { it.isBlank() }) return@forEachIndexed
            require(columns.size == headers.size) {
                "Malformed CSV row $rowNumber: expected ${headers.size} columns but found ${columns.size}."
            }
            val timeValue = columns[timeIndex].trim()
            val typeValue = columns[typeIndex].trim()
            val amountValue = columns[amountIndex].trim()
            require(timeValue.isNotEmpty()) { "Missing required time/date on CSV row $rowNumber." }
            require(typeValue.isNotEmpty()) { "Missing required income type on CSV row $rowNumber." }
            require(amountValue.isNotEmpty()) { "Missing required income amount on CSV row $rowNumber." }

            val timestamp = parseTimestamp(timeValue)
                ?: throw IllegalArgumentException("Could not parse timestamp on CSV row $rowNumber.")
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.format(Date(timestamp))
            require(day.take(4).toIntOrNull() == selectedYear) {
                "CSV contains a row outside $selectedYear ($day). Export only the selected year and retry."
            }
            val amount = amountValue.replace(",", "").toDoubleOrNull()
            require(amount != null && amount.isFinite()) {
                "Invalid or non-finite income amount on CSV row $rowNumber."
            }

            // Exact duplicate records are skipped. Distinct rows with the same amount/date remain separate.
            val fingerprint = columns.map { it.trim() }.joinToString("\u001F")
            if (!seenRows.add(fingerprint)) {
                duplicates++
                return@forEachIndexed
            }

            val type = typeValue.uppercase(Locale.US).replace(' ', '_')
            if (type !in includedTypes) {
                ignored++
                return@forEachIndexed
            }
            daily[day] = (daily[day] ?: 0.0) + amount
            rowsByType[type] = (rowsByType[type] ?: 0) + 1
            activityDates += day
            accepted++
        }
        require(accepted > 0) {
            "No REALIZED_PNL, COMMISSION, FUNDING_FEE or INSURANCE_CLEAR rows were found. Choose a Binance USDⓈ-M Futures income-history export."
        }
        return BinanceIncomeImportReport(
            daily = daily,
            includedRows = accepted,
            ignoredRows = ignored,
            rowsByType = rowsByType.toMap(),
            firstActivityDate = activityDates.minOrNull() ?: error("No activity dates found."),
            lastActivityDate = activityDates.maxOrNull() ?: error("No activity dates found."),
            duplicateRows = duplicates
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
        require(!quoted) { "an unclosed quoted field was found." }
        fields += field.toString()
        return fields
    }

    private fun List<String>.firstIndexOfAny(vararg candidates: String): Int =
        indexOfFirst { header -> candidates.any { header == it } }
}
