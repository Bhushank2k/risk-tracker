package com.risktracker.app

import kotlin.math.ceil

data class TargetPlan(
    val validBalances: Boolean,
    val profitNeeded: Double?,
    val requiredDailyProfit: Double?,
    val estimatedDays: Int?,
    val progress: Float
)

/**
 * Calculates planning figures without assuming profits are guaranteed.
 * Invalid, non-finite inputs are omitted instead of being allowed to leak
 * Infinity/NaN into the UI.
 */
fun calculateTargetPlan(
    startingBalance: Double?,
    targetBalance: Double?,
    days: Int?,
    plannedDailyProfit: Double?
): TargetPlan {
    val validBalances = startingBalance != null &&
        targetBalance != null &&
        startingBalance.isFinite() &&
        targetBalance.isFinite() &&
        startingBalance >= 0.0 &&
        targetBalance > startingBalance

    val profitNeeded = if (validBalances) targetBalance!! - startingBalance!! else null
    val requiredDailyProfit = if (profitNeeded != null && days != null && days > 0) {
        profitNeeded / days
    } else {
        null
    }
    val estimatedDays = if (
        profitNeeded != null &&
        plannedDailyProfit != null &&
        plannedDailyProfit.isFinite() &&
        plannedDailyProfit > 0.0
    ) {
        val estimate = ceil(profitNeeded / plannedDailyProfit)
        if (estimate.isFinite() && estimate <= Int.MAX_VALUE.toDouble()) estimate.toInt() else null
    } else {
        null
    }
    val progress = if (validBalances && targetBalance!! > 0.0) {
        (startingBalance!! / targetBalance).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    return TargetPlan(validBalances, profitNeeded, requiredDailyProfit, estimatedDays, progress)
}
