package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSignalReplayTest {
    private fun longSignal(triggered: Boolean = true) = ReplaySignal(
        id = "s1", symbol = "BTCUSDT", side = "LONG", signalTime = 0L, score = 85,
        entry = 100.0, stop = 95.0, target1 = 110.0, target2 = 120.0,
        entryWasTriggered = triggered
    )

    @Test fun stopBeforeTargetIsConservativelyLossOneR() {
        val result = HistoricalSignalReplay.replay(longSignal(), listOf(ReplayCandle(1, 105.0, 94.0, 99.0)))
        assertEquals(ReplayOutcome.STOP_BEFORE_TP1, result.outcome)
        assertEquals(-1.0, result.grossR, 0.00001)
        assertTrue(result.netR < result.grossR)
    }

    @Test fun tp1ThenTp2RealizesWeightedTargets() {
        val result = HistoricalSignalReplay.replay(longSignal(), listOf(
            ReplayCandle(1, 111.0, 99.0, 109.0),
            ReplayCandle(2, 121.0, 108.0, 120.0)
        ))
        assertEquals(ReplayOutcome.TP2, result.outcome)
        assertEquals(1.5, result.grossR, 0.00001) // 50% at 2R, 50% at 4R
    }

    @Test fun tp1ThenOriginalStopOnRemainingPositionReturnsZeroGrossR() {
        val result = HistoricalSignalReplay.replay(longSignal(), listOf(
            ReplayCandle(1, 111.0, 99.0, 109.0),
            ReplayCandle(2, 106.0, 94.0, 100.0)
        ))
        assertEquals(ReplayOutcome.TP1_THEN_STOP, result.outcome)
        assertEquals(0.0, result.grossR, 0.00001)
    }

    @Test fun targetAndStopSameCandleUsesStopFirst() {
        val result = HistoricalSignalReplay.replay(longSignal(), listOf(ReplayCandle(1, 111.0, 94.0, 100.0)))
        assertEquals(ReplayOutcome.STOP_BEFORE_TP1, result.outcome)
        assertEquals(-1.0, result.grossR, 0.00001)
    }

    @Test fun untriggeredSignalIsSkippedWhenEntryNeverTouches() {
        val result = HistoricalSignalReplay.replay(longSignal(triggered = false), listOf(
            ReplayCandle(1, 99.0, 97.0, 98.0), ReplayCandle(2, 99.5, 98.0, 99.0)
        ))
        assertFalse(result.entryTriggered)
        assertEquals(ReplayOutcome.TIMEOUT_NO_TP, result.outcome)
        assertEquals(0.0, result.netR, 0.00001)
    }

    @Test fun untriggeredSignalEntersOnTouchAndUsesTouchBarConservatively() {
        val result = HistoricalSignalReplay.replay(longSignal(triggered = false), listOf(
            ReplayCandle(1, 111.0, 94.0, 100.0)
        ))
        assertTrue(result.entryTriggered)
        assertEquals(ReplayOutcome.STOP_BEFORE_TP1, result.outcome)
        assertEquals(1L, result.entryTime)
    }

    @Test fun shortSideTargetsAndStopAreEvaluatedCorrectly() {
        val short = ReplaySignal("s2", "ETHUSDT", "SHORT", 0, 72, 100.0, 105.0, 90.0, 80.0, true)
        val result = HistoricalSignalReplay.replay(short, listOf(ReplayCandle(1, 91.0, 79.0, 85.0)))
        assertEquals(ReplayOutcome.TP2, result.outcome)
        assertEquals(1.5, result.grossR, 0.00001)
    }

    @Test fun summaryReportsScoreBandsAndSideStats() {
        val a = HistoricalSignalReplay.replay(longSignal(), listOf(ReplayCandle(1, 111.0, 99.0, 109.0), ReplayCandle(2, 121.0, 108.0, 120.0)))
        val b = HistoricalSignalReplay.replay(longSignal().copy(id = "s2", score = 65), listOf(ReplayCandle(1, 105.0, 94.0, 99.0)))
        val summary = HistoricalSignalReplay.summarize(listOf(a, b))
        assertEquals(2, summary.enteredTrades)
        assertEquals(1, summary.wins)
        assertEquals(1, summary.losses)
        assertEquals(1, summary.long.trades)
        assertEquals(1, summary.scoreBands.first { it.band.minScore == 60 }.trades)
        assertEquals(1, summary.scoreBands.first { it.band.minScore == 80 }.trades)
    }

    @Test fun invalidGeometryReturnsInvalidInsteadOfThrowing() {
        val bad = longSignal().copy(stop = 101.0)
        assertEquals(ReplayOutcome.INVALID, HistoricalSignalReplay.replay(bad, listOf(ReplayCandle(1, 110.0, 99.0, 105.0))).outcome)
    }
}
