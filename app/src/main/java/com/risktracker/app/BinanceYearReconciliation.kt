package com.risktracker.app

enum class BinanceReconciliationStatus { MATCHED_TOTALS, DISCREPANCY }

data class BinanceReconciliationResult(
    val importedTotal: Double,
    val sourceTotal: Double,
    val delta: Double,
    val status: BinanceReconciliationStatus
)

/** Compares two user-supplied totals only; a match does not establish export completeness. */
object BinanceYearReconciliation {
    fun compare(importedTotal: Double, sourceTotal: Double, tolerance: Double = 0.01): BinanceReconciliationResult {
        require(importedTotal.isFinite() && sourceTotal.isFinite()) { "Totals must be finite numbers." }
        require(tolerance.isFinite() && tolerance >= 0.0) { "Tolerance must be a finite non-negative number." }
        val delta = importedTotal - sourceTotal
        return BinanceReconciliationResult(
            importedTotal = importedTotal,
            sourceTotal = sourceTotal,
            delta = delta,
            status = if (kotlin.math.abs(delta) <= tolerance) BinanceReconciliationStatus.MATCHED_TOTALS
            else BinanceReconciliationStatus.DISCREPANCY
        )
    }
}
