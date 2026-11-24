package com.risktracker.app

enum class ProfitProtectionKind { PROFIT_TARGET, GIVEBACK, DAILY_LOSS_LIMIT, LOSING_STREAK }

data class ProfitProtectionSettings(
    val dailyLossLimit: Double,
    val profitTarget: Double? = null,
    val maximumGiveback: Double? = null,
    val maximumConsecutiveLosses: Int? = null
)

data class ProfitProtectionAlert(
    val kind: ProfitProtectionKind,
    val title: String,
    val detail: String
)

/** Advisory-only daily discipline rules. This engine never places, blocks, or closes exchange orders. */
object ProfitProtectionEngine {
    fun evaluate(
        dailyPnl: Double?,
        peakObservedDailyPnl: Double?,
        consecutiveLosses: Int,
        settings: ProfitProtectionSettings
    ): List<ProfitProtectionAlert> {
        val validPnl = dailyPnl?.takeIf { it.isFinite() } ?: return emptyList()
        val alerts = mutableListOf<ProfitProtectionAlert>()
        if (settings.dailyLossLimit.isFinite() && settings.dailyLossLimit > 0.0 &&
            validPnl <= -settings.dailyLossLimit) {
            alerts += ProfitProtectionAlert(
                ProfitProtectionKind.DAILY_LOSS_LIMIT,
                "Daily loss limit reached",
                "Recorded daily P&L is " + "%.2f".format(java.util.Locale.US, validPnl) +
                    "; configured loss limit is " + "%.2f".format(java.util.Locale.US, settings.dailyLossLimit) +
                    ". Consider stopping new trades and reviewing open exposure."
            )
        }
        val target = settings.profitTarget?.takeIf { it.isFinite() && it > 0.0 }
        if (target != null && validPnl >= target) {
            alerts += ProfitProtectionAlert(
                ProfitProtectionKind.PROFIT_TARGET,
                "Daily profit target reached",
                "Recorded P&L has reached " + "%.2f".format(java.util.Locale.US, validPnl) +
                    " against your " + "%.2f".format(java.util.Locale.US, target) +
                    " target. Consider protecting gains rather than increasing risk."
            )
        }
        val giveback = settings.maximumGiveback?.takeIf { it.isFinite() && it > 0.0 }
        val peak = peakObservedDailyPnl?.takeIf { it.isFinite() && it > 0.0 }
        if (giveback != null && peak != null && peak - validPnl >= giveback) {
            alerts += ProfitProtectionAlert(
                ProfitProtectionKind.GIVEBACK,
                "Daily profit giveback threshold reached",
                "P&L has fallen " + "%.2f".format(java.util.Locale.US, peak - validPnl) +
                    " from the observed in-session peak of " + "%.2f".format(java.util.Locale.US, peak) +
                    ". Consider reducing risk and reviewing current positions."
            )
        }
        val streakLimit = settings.maximumConsecutiveLosses?.takeIf { it > 0 }
        if (streakLimit != null && consecutiveLosses >= streakLimit) {
            alerts += ProfitProtectionAlert(
                ProfitProtectionKind.LOSING_STREAK,
                "Consecutive-loss threshold reached",
                "The journal shows " + consecutiveLosses + " consecutive losing trades; your threshold is " +
                    streakLimit + ". Consider a pause and a review of recent setups."
            )
        }
        return alerts
    }
}
