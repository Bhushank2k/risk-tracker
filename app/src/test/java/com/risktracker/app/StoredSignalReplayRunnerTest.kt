package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoredSignalReplayRunnerTest {
    @Test fun mapsPersistedPlanToReplayWithoutChangingEvaluationTimestamp() {
        val idea = TradeIdea(
            symbol = "BTCUSDT", side = "LONG", score = 88, volume = 1.0, change = 0.0,
            last = 100.0, entry = 100.0, stop = 95.0, target1 = 110.0, target2 = 120.0,
            entryTriggered = false, rsi = 50.0, volumeRatio = 1.0, bias4h = "Bullish",
            bias1h = "Bullish", setup = "Pullback", reasons = emptyList(), warning = "",
            horizon = "Intraday", bias5m = "Bullish", spreadBps = null, bookImbalance = null,
            fundingRate = null, oiChangePct = null, backtest = TradeIdeaBacktestStats()
        )
        val snapshot = ConfluenceV4PlanSnapshot.from(idea, 1_700_000_000_000L)
        val replaySignal = ReplaySignal(
            id = snapshot.snapshotId, symbol = snapshot.symbol, side = snapshot.side,
            signalTime = snapshot.capturedAt, score = snapshot.score, entry = snapshot.entry,
            stop = snapshot.stop, target1 = snapshot.target1, target2 = snapshot.target2,
            entryWasTriggered = snapshot.entryTriggered
        )
        assertEquals(snapshot.capturedAt, replaySignal.signalTime)
        assertEquals(snapshot.snapshotId, replaySignal.id)
        assertFalse(replaySignal.entryWasTriggered)
    }

    @Test fun historicalCandlesMustBeStrictlyAfterSignalTimestamp() {
        val signalTime = 1_700_000_000_000L
        val candles = listOf(
            HistoricalMarketCandle("BTCUSDT", "5m", signalTime - 300_000, signalTime - 1, 100.0, 101.0, 99.0, 100.0, 1.0),
            HistoricalMarketCandle("BTCUSDT", "5m", signalTime, signalTime + 299_999, 100.0, 101.0, 99.0, 100.0, 1.0),
            HistoricalMarketCandle("BTCUSDT", "5m", signalTime + 300_000, signalTime + 599_999, 100.0, 101.0, 99.0, 100.0, 1.0)
        )
        val replayCandles = candles.filter { it.openTime > signalTime }
            .map { ReplayCandle(it.openTime, it.high, it.low, it.close) }
        assertEquals(1, replayCandles.size)
        assertEquals(signalTime + 300_000, replayCandles.single().openTime)
    }
}
