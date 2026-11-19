package com.risktracker.app

/**
 * Side-by-side replay result for the legacy signal model and the newer causal V2 model.
 *
 * Both evaluators receive the same candle history. Their execution assumptions still differ:
 * the legacy replay uses its historical breakout/stop model, while V2 requires a next-bar
 * open fill, lower-timeframe confirmation and estimated trading costs. Treat this as a
 * model comparison, not a perfectly controlled strategy-only experiment.
 */
internal data class TradeIdeaBacktestComparison(
    val legacy: TradeIdeaBacktestStats,
    val v2: TradeIdeaBacktestStats,
    val averageRDelta: Double?,
    val enoughSamplesForComparison: Boolean
) {
    val expectancyLeader: String?
        get() = when {
            !enoughSamplesForComparison -> null
            averageRDelta == null -> null
            averageRDelta > 0.0 -> "V2"
            averageRDelta < 0.0 -> "Legacy"
            else -> "Tie"
        }
}

internal object TradeIdeaBacktestComparator {
    /**
     * Replays both generations over exactly the supplied history.
     *
     * The legacy engine does not consume M5 candles; V2 does. All candles must be
     * chronologically ordered and represent the same symbol and historical window.
     */
    fun compare(
        h4: List<V2Candle>,
        h1: List<V2Candle>,
        m15: List<V2Candle>,
        m5: List<V2Candle>,
        maxHoldingBars: Int = 96,
        roundTripFeeBps: Double = 8.0,
        slippageBps: Double = 4.0,
        fundingBpsPer8h: Double = 1.0
    ): TradeIdeaBacktestComparison {
        val legacy = TradeIdeaBacktest.run(
            h4 = h4.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) },
            h1 = h1.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) },
            m15 = m15.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) }
        )
        val v2 = TradeIdeasV2.backtest(
            h4 = h4,
            h1 = h1,
            m15 = m15,
            m5 = m5,
            maxHoldingBars = maxHoldingBars,
            roundTripFeeBps = roundTripFeeBps,
            slippageBps = slippageBps,
            fundingBpsPer8h = fundingBpsPer8h
        )
        val enough = legacy.trades >= TradeIdeaBacktestStats.MINIMUM_ESTIMATE_SAMPLES &&
            v2.trades >= TradeIdeaBacktestStats.MINIMUM_ESTIMATE_SAMPLES
        val delta = if (enough) v2.averageR - legacy.averageR else null
        return TradeIdeaBacktestComparison(
            legacy = legacy,
            v2 = v2,
            averageRDelta = delta,
            enoughSamplesForComparison = enough
        )
    }
}
