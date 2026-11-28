package com.risktracker.app

import android.content.Context

/** A reproducible replay batch built only from persisted scanner snapshots and stored candles. */
internal data class StoredSignalReplayReport(
    val interval: String,
    val capturedSnapshots: Int,
    val replayedSignals: Int,
    val missingHistorySignals: Int,
    val trades: List<ReplayTradeResult>,
    val summary: ReplaySummary
)

internal object StoredSignalReplayRunner {
    fun replay(
        context: Context,
        interval: String,
        config: ReplayConfig = ReplayConfig(),
        snapshotLimit: Int = ConfluenceV4PlanSnapshotStore.DEFAULT_LIMIT
    ): StoredSignalReplayReport {
        require(interval in HistoricalMarketCandle.SUPPORTED_INTERVALS) { "Unsupported replay interval." }
        val snapshots = ConfluenceV4PlanSnapshotStore.load(context, snapshotLimit)
        val candlesBySymbol = snapshots.asSequence().map { it.symbol }.distinct().associateWith { symbol ->
            HistoricalMarketCandleStore.load(context, symbol, interval)
        }
        var missingHistory = 0
        val results = snapshots.map { snapshot ->
            val candles = candlesBySymbol[snapshot.symbol].orEmpty()
                .asSequence()
                .filter { it.openTime > snapshot.capturedAt }
                .map { ReplayCandle(it.openTime, it.high, it.low, it.close) }
                .toList()
            if (candles.isEmpty()) missingHistory++
            HistoricalSignalReplay.replay(
                signal = ReplaySignal(
                    id = snapshot.snapshotId,
                    symbol = snapshot.symbol,
                    side = snapshot.side,
                    signalTime = snapshot.capturedAt,
                    score = snapshot.score,
                    entry = snapshot.entry,
                    stop = snapshot.stop,
                    target1 = snapshot.target1,
                    target2 = snapshot.target2,
                    entryWasTriggered = snapshot.entryTriggered
                ),
                candles = candles,
                config = config
            )
        }
        return StoredSignalReplayReport(
            interval = interval,
            capturedSnapshots = snapshots.size,
            replayedSignals = results.count { it.outcome != ReplayOutcome.INVALID },
            missingHistorySignals = missingHistory,
            trades = results,
            summary = HistoricalSignalReplay.summarize(results)
        )
    }
}
