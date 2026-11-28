package com.risktracker.app

/** Replay-only cohort metrics. Every sample is a closed/entered replay outcome. */
internal data class StoredSignalPerformanceRow(
    val dimension: String,
    val samples: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val expectancyNetR: Double,
    val profitFactor: Double?,
    val maxDrawdownR: Double
) {
    val hasMinimumSample: Boolean get() = samples >= MINIMUM_ESTIMATE_SAMPLES

    companion object {
        const val MINIMUM_ESTIMATE_SAMPLES = 30
    }
}

internal data class StoredSignalPerformanceAnalytics(
    val bySetup: List<StoredSignalPerformanceRow>,
    val byHorizon: List<StoredSignalPerformanceRow>,
    val byScoreBand: List<StoredSignalPerformanceRow>,
    val evaluatedTrades: Int
)

internal object StoredSignalPerformanceAnalyzer {
    private data class Sample(
        val setup: String,
        val horizon: String,
        val score: Int,
        val time: Long,
        val netR: Double
    )

    fun analyze(
        snapshots: List<ConfluenceV4PlanSnapshot>,
        results: List<ReplayTradeResult>
    ): StoredSignalPerformanceAnalytics {
        val samples = snapshots.zip(results).mapNotNull { (snapshot, result) ->
            if (result.entryTime == null || result.outcome == ReplayOutcome.INVALID ||
                !result.netR.isFinite() || result.netR == 0.0) return@mapNotNull null
            Sample(
                setup = snapshot.setup.ifBlank { "Unspecified" },
                horizon = snapshot.horizon.ifBlank { "Unspecified" },
                score = snapshot.score,
                time = result.exitTime ?: result.entryTime,
                netR = result.netR
            )
        }
        fun rows(key: (Sample) -> String): List<StoredSignalPerformanceRow> =
            samples.groupBy(key).map { (dimension, cohort) ->
                summarize(dimension, cohort.sortedBy { it.time })
            }.sortedWith(compareByDescending<StoredSignalPerformanceRow> { it.samples }.thenBy { it.dimension })

        return StoredSignalPerformanceAnalytics(
            bySetup = rows { it.setup },
            byHorizon = rows { it.horizon },
            byScoreBand = rows { scoreBand(it.score) },
            evaluatedTrades = samples.size
        )
    }

    private fun scoreBand(score: Int): String {
        val lower = (score.coerceIn(0, 100) / 10) * 10
        val upper = if (lower == 100) 100 else lower + 9
        return if (lower == 100) "100" else "$lower–$upper"
    }

    private fun summarize(dimension: String, samples: List<Sample>): StoredSignalPerformanceRow {
        val values = samples.map { it.netR }
        val wins = values.count { it > 0.0 }
        val losses = values.count { it < 0.0 }
        val grossWins = values.filter { it > 0.0 }.sum()
        val grossLosses = -values.filter { it < 0.0 }.sum()
        var equity = 0.0
        var peak = 0.0
        var drawdown = 0.0
        values.forEach { value ->
            equity += value
            peak = maxOf(peak, equity)
            drawdown = maxOf(drawdown, peak - equity)
        }
        return StoredSignalPerformanceRow(
            dimension = dimension,
            samples = values.size,
            wins = wins,
            losses = losses,
            winRatePct = if (wins + losses == 0) 0.0 else wins.toDouble() / (wins + losses) * 100.0,
            expectancyNetR = values.average(),
            profitFactor = when {
                grossLosses > 0.0 -> grossWins / grossLosses
                grossWins > 0.0 -> Double.POSITIVE_INFINITY
                else -> null
            },
            maxDrawdownR = drawdown
        )
    }
}
