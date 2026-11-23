package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceImportDiffTest {
    @Test
    fun classifiesNewChangedAndRemovedDatesAndTotals() {
        val result = BinanceImportDiff.compare(
            previous = mapOf("2024-01-01" to 10.0, "2024-01-02" to 20.0, "2024-01-03" to 30.0),
            proposed = mapOf("2024-01-01" to 10.0, "2024-01-02" to 18.0, "2024-01-04" to 5.0)
        )
        assertEquals(1, result.newDates)
        assertEquals(1, result.changedDates)
        assertEquals(1, result.removedDates)
        assertEquals(60.0, result.previousTotal, 0.000001)
        assertEquals(33.0, result.proposedTotal, 0.000001)
        assertEquals(-27.0, result.delta, 0.000001)
    }

    @Test
    fun ignoresSmallDifferencesWithinTolerance() {
        val result = BinanceImportDiff.compare(
            previous = mapOf("2024-01-01" to 1.0),
            proposed = mapOf("2024-01-01" to 1.004)
        )
        assertEquals(0, result.changes.size)
    }
    @Test
    fun completeYearBackupRowsPassValidation() {
        validateBinanceYearBackupRows(2026, listOf(
            BinanceDailyResult("2026-01-01", 3.0, 4.0, mapOf("REALIZED_PNL" to 4.0)),
            BinanceDailyResult("2026-01-02", 1.0, -2.0)
        ))
    }

    @Test
    fun duplicateDatesAreRejectedInsteadOfSilentlyDiscarded() {
        val rows = listOf(
            BinanceDailyResult("2026-01-01", 1.0, 2.0),
            BinanceDailyResult("2026-01-01", 3.0, 4.0)
        )
        assertEquals(true, runCatching {
            validateBinanceYearBackupRows(2026, rows)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun malformedDatesAndWrongYearAreRejected() {
        val malformed = listOf(BinanceDailyResult("2026-1-01", 0.0, 1.0))
        val wrongYear = listOf(BinanceDailyResult("2025-01-01", 0.0, 1.0))
        assertEquals(true, runCatching {
            validateBinanceYearBackupRows(2026, malformed)
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(true, runCatching {
            validateBinanceYearBackupRows(2026, wrongYear)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun impossibleCalendarDatesAreRejectedAndLeapDaysAreHandled() {
        assertEquals(true, isValidBinanceCalendarDate("2024-02-29"))
        assertEquals(false, isValidBinanceCalendarDate("2025-02-29"))
        assertEquals(false, isValidBinanceCalendarDate("2026-99-99"))
        assertEquals(false, isValidBinanceCalendarDate("2026-04-31"))
        assertEquals(false, isValidBinanceCalendarDate("2026-1-01"))
    }

    @Test
    fun nonFiniteBackupAmountsAreRejected() {
        val rows = listOf(BinanceDailyResult("2026-01-01", 0.0, Double.POSITIVE_INFINITY))
        assertEquals(true, runCatching {
            validateBinanceYearBackupRows(2026, rows)
        }.exceptionOrNull() is IllegalArgumentException)
    }

}
