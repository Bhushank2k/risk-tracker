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
}
