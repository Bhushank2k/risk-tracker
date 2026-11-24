package com.risktracker.app

import kotlin.math.sqrt

enum class CoachInsightSeverity { INFO, CAUTION }

data class TradingCoachInsight(
    val title: String,
    val detail: String,
    val severity: CoachInsightSeverity,
    val sampleCount: Int
)

data class TradingCoachReport(
    val tradeCount: Int,
    val taggedTradeCount: Int,
    val insights: List<TradingCoachInsight>,
    val limitations: List<String>
)

/**
 * Explainable coaching rules over manually journaled closed trades. Date-only journal records
 * cannot establish intraday order sequence, so this engine deliberately does not label behavior
 * as revenge trading or infer intent.
 */
object PersonalTradingCoachEngine {
    fun analyze(trades: List<TradeEntry>, minimumStrategySamples: Int = 5): TradingCoachReport {
        require(minimumStrategySamples >= 2)
        val valid = trades.filter { it.pnl.isFinite() }
        val tagged = valid.count { it.strategy.isNotBlank() }
        val insights = mutableListOf<TradingCoachInsight>()
        val limitations = mutableListOf<String>()

        if (valid.isEmpty()) {
            insights += TradingCoachInsight(
                "Start with a complete trade journal",
                "Add closed trades with strategy, mistake tags, and planned risk to enable useful coaching.",
                CoachInsightSeverity.INFO, 0
            )
        } else {
            val streak = currentTradeStreak(valid)
            if (streak.kind == "Loss" && streak.count >= 3) {
                insights += TradingCoachInsight(
                    "Review the current losing streak",
                    "The latest journal records contain " + streak.count +
                        " consecutive losing trades. Consider reviewing setup quality and your risk plan before the next trade.",
                    CoachInsightSeverity.CAUTION, streak.count
                )
            }

            val strategyGroups = valid.filter { it.strategy.isNotBlank() }.groupBy { it.strategy.trim() }
            strategyGroups.forEach { (strategy, rows) ->
                if (rows.size >= minimumStrategySamples) {
                    val net = rows.sumOf { it.pnl }
                    val average = net / rows.size
                    if (average < 0.0) {
                        insights += TradingCoachInsight(
                            "Review strategy: " + strategy,
                            "This strategy has " + rows.size + " journaled trades and average net P&L of " +
                                "%.2f".format(java.util.Locale.US, average) +
                                ". Review its entries, exits, fees, and market conditions before increasing size.",
                            CoachInsightSeverity.CAUTION, rows.size
                        )
                    }
                }
            }

            val mistakes = valid.map { it.mistake.trim() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            mistakes.maxByOrNull { it.value }?.let { (tag, count) ->
                insights += TradingCoachInsight(
                    "Most frequent mistake tag: " + tag,
                    "This tag appears on " + count + " closed trades. Review those entries for a repeated process issue.",
                    CoachInsightSeverity.INFO, count
                )
            }

            val risks = valid.map { it.riskUsd }.filter { it.isFinite() && it > 0.0 }
            if (risks.size >= minimumStrategySamples) {
                val mean = risks.average()
                if (mean > 0.0) {
                    val variance = risks.sumOf { (it - mean) * (it - mean) } / risks.size
                    val coefficientOfVariation = sqrt(variance) / mean
                    if (coefficientOfVariation > 0.75) {
                        insights += TradingCoachInsight(
                            "Planned risk varies substantially",
                            "Recorded planned-risk values have a coefficient of variation of " +
                                "%.2f".format(java.util.Locale.US, coefficientOfVariation) +
                                ". Check whether position sizing follows a consistent risk budget.",
                            CoachInsightSeverity.CAUTION, risks.size
                        )
                    }
                }
            }

            if (tagged < valid.size) {
                insights += TradingCoachInsight(
                    "Improve journal coverage",
                    (valid.size - tagged) + " of " + valid.size +
                        " valid trades have no strategy tag. Tagging trades makes strategy comparisons more useful.",
                    CoachInsightSeverity.INFO, valid.size
                )
            }
        }

        if (valid.size < 10) limitations += "Fewer than 10 valid closed trades are available; treat all patterns as early observations."
        if (valid.any { it.riskUsd <= 0.0 || !it.riskUsd.isFinite() }) {
            limitations += "Some trades have no valid planned-risk value; risk-consistency analysis is incomplete."
        }
        if (valid.any { it.mistake.isBlank() }) {
            limitations += "Mistake tags are incomplete; untagged behavior cannot be evaluated."
        }
        limitations += "Journal dates do not include execution timestamps, so intraday sequencing and revenge-trading claims are not supported."
        if (insights.isEmpty()) {
            insights += TradingCoachInsight(
                "No strong journal pattern detected",
                "Keep recording strategy, mistake tags, and planned risk. Absence of a warning does not prove a strategy is safe or profitable.",
                CoachInsightSeverity.INFO, valid.size
            )
        }
        return TradingCoachReport(valid.size, tagged, insights, limitations)
    }
}
