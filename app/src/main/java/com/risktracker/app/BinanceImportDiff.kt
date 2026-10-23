package com.risktracker.app

enum class BinanceImportChangeType { NEW_DATE, CHANGED_AMOUNT, REMOVED_DATE }

data class BinanceDailyImportChange(
    val date: String,
    val type: BinanceImportChangeType,
    val previousAmount: Double?,
    val proposedAmount: Double?
)

data class BinanceImportComparison(
    val previousTotal: Double,
    val proposedTotal: Double,
    val delta: Double,
    val changes: List<BinanceDailyImportChange>
) {
    val newDates: Int get() = changes.count { it.type == BinanceImportChangeType.NEW_DATE }
    val changedDates: Int get() = changes.count { it.type == BinanceImportChangeType.CHANGED_AMOUNT }
    val removedDates: Int get() = changes.count { it.type == BinanceImportChangeType.REMOVED_DATE }
}

/** Compares current saved daily Futures values with a proposed year import. */
object BinanceImportDiff {
    fun compare(
        previous: Map<String, Double>,
        proposed: Map<String, Double>,
        tolerance: Double = 0.005
    ): BinanceImportComparison {
        require(previous.values.all { it.isFinite() } && proposed.values.all { it.isFinite() }) {
            "Import comparison requires finite daily totals."
        }
        require(tolerance.isFinite() && tolerance >= 0.0) {
            "Tolerance must be a finite non-negative number."
        }
        val changes = (previous.keys + proposed.keys).distinct().sorted().mapNotNull { date ->
            val old = previous[date]
            val next = proposed[date]
            when {
                old == null && next != null -> BinanceDailyImportChange(date, BinanceImportChangeType.NEW_DATE, null, next)
                old != null && next == null -> BinanceDailyImportChange(date, BinanceImportChangeType.REMOVED_DATE, old, null)
                old != null && next != null && kotlin.math.abs(next - old) > tolerance ->
                    BinanceDailyImportChange(date, BinanceImportChangeType.CHANGED_AMOUNT, old, next)
                else -> null
            }
        }
        val previousTotal = previous.values.sum()
        val proposedTotal = proposed.values.sum()
        return BinanceImportComparison(
            previousTotal = previousTotal,
            proposedTotal = proposedTotal,
            delta = proposedTotal - previousTotal,
            changes = changes
        )
    }
}
