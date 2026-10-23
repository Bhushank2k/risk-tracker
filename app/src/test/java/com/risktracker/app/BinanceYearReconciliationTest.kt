package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BinanceYearReconciliationTest {
    @Test
    fun totalsWithinToleranceMatch() {
        val result = BinanceYearReconciliation.compare(100.005, 100.0)
        assertEquals(BinanceReconciliationStatus.MATCHED_TOTALS, result.status)
        assertEquals(0.005, result.delta, 0.0000001)
    }

    @Test
    fun totalsOutsideToleranceReportDiscrepancy() {
        val result = BinanceYearReconciliation.compare(98.0, 100.0)
        assertEquals(BinanceReconciliationStatus.DISCREPANCY, result.status)
        assertEquals(-2.0, result.delta, 0.0000001)
    }

    @Test
    fun rejectsNonFiniteTotalsAndInvalidTolerance() {
        assertThrows(IllegalArgumentException::class.java) {
            BinanceYearReconciliation.compare(Double.NaN, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BinanceYearReconciliation.compare(1.0, 1.0, -0.01)
        }
    }
}
