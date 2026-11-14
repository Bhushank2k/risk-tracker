package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class SequentialSmcConfluenceTest {
    private fun chain(direction: SmcDirection = SmcDirection.BULLISH) = listOf(
        SequentialSignalEvent(SequentialSignalStage.H4_BIAS, direction, 1_000L),
        SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, direction, 2_000L),
        SequentialSignalEvent(SequentialSignalStage.M15_SETUP, direction, 3_000L),
        SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, direction, 4_000L)
    )
    @Test fun acceptsSignalsConfirmedOnDifferentCandles() {
        val result = evaluateSequentialConfluence(chain(), SmcDirection.BULLISH, 5_000L)
        assertTrue(result.ready); assertEquals(4, result.events.size)
    }
    @Test fun rejectsOutOfOrderSignals() {
        val events = chain().toMutableList().also { it[2] = it[2].copy(closeTime = 1_500L) }
        val result = evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L)
        assertFalse(result.ready); assertTrue(result.reason.contains("before the prior stage"))
    }
    @Test fun rejectsExpiredSignals() {
        assertFalse(evaluateSequentialConfluence(chain(), SmcDirection.BULLISH, 60 * 60_000L).ready)
    }
    @Test fun acceptsSignalsAtExactExpiryBoundary() {
        val limits = mapOf(
            SequentialSignalStage.H4_BIAS to 4_000L,
            SequentialSignalStage.H1_STRUCTURE to 3_000L,
            SequentialSignalStage.M15_SETUP to 2_000L,
            SequentialSignalStage.M5_ENTRY to 1_000L
        )
        val result = evaluateSequentialConfluence(
            chain(), SmcDirection.BULLISH, 5_000L, maxAgeMillis = limits
        )
        assertTrue(result.ready)
    }
    @Test fun rejectsSignalOneMillisecondBeyondExpiryBoundary() {
        val limits = mapOf(
            SequentialSignalStage.H4_BIAS to 4_000L,
            SequentialSignalStage.H1_STRUCTURE to 3_000L,
            SequentialSignalStage.M15_SETUP to 2_000L,
            SequentialSignalStage.M5_ENTRY to 999L
        )
        val result = evaluateSequentialConfluence(
            chain(), SmcDirection.BULLISH, 5_000L, maxAgeMillis = limits
        )
        assertFalse(result.ready)
        assertTrue(result.reason.contains("M5 ENTRY confirmation expired"))
    }
    @Test fun ignoresFutureSignalsAndReportsMissingStage() {
        val events = chain().dropLast(1) + chain().last().copy(closeTime = 9_000L)
        val result = evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L)
        assertFalse(result.ready); assertTrue(result.reason.contains("M5 ENTRY"))
    }
    @Test fun rejectsOpposingAndNeutralSignals() {
        val opposing = chain() + SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, SmcDirection.BEARISH, 4_500L)
        assertFalse(evaluateSequentialConfluence(opposing, SmcDirection.BULLISH, 5_000L).ready)
        assertFalse(evaluateSequentialConfluence(chain(), SmcDirection.NEUTRAL, 5_000L).ready)
    }
    @Test fun rangeSequenceAllowsNeutralHigherTimeframeContext() {
        val events = listOf(
            SequentialSignalEvent(SequentialSignalStage.H4_BIAS, SmcDirection.NEUTRAL, 1_000L),
            SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, SmcDirection.NEUTRAL, 2_000L),
            SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, 3_000L),
            SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, 4_000L)
        )
        assertTrue(evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L, allowNeutralContext = true).ready)
        assertFalse(evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L).ready)
    }
    @Test fun neutralContextAllowanceDoesNotApplyToExecutionStages() {
        val events = listOf(
            SequentialSignalEvent(SequentialSignalStage.H4_BIAS, SmcDirection.NEUTRAL, 1_000L),
            SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, SmcDirection.NEUTRAL, 2_000L),
            SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.NEUTRAL, 3_000L),
            SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, 4_000L)
        )
        val result = evaluateSequentialConfluence(
            events, SmcDirection.BULLISH, 5_000L, allowNeutralContext = true
        )
        assertFalse(result.ready)
        assertTrue(result.reason.contains("M15 SETUP conflicts"))
    }
    @Test fun newerNeutralContextReplacesStaleTrendBias() {
        val events = chain() + SequentialSignalEvent(SequentialSignalStage.H4_BIAS, SmcDirection.NEUTRAL, 4_500L)
        val result = evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L)
        assertFalse(result.ready)
        assertTrue(result.reason.contains("H4 BIAS conflicts"))
    }
    @Test fun newerOpposingM15SetupInvalidatesEarlierDirectionalSetup() {
        val events = chain() + SequentialSignalEvent(
            SequentialSignalStage.M15_SETUP, SmcDirection.BEARISH, 4_500L
        )
        val result = evaluateSequentialConfluence(events, SmcDirection.BULLISH, 5_000L)
        assertFalse(result.ready)
        assertTrue(result.reason.contains("M15 SETUP conflicts"))
    }

    @Test fun rejectsUnsortedOrDuplicateCandleTimestamps() {
        val valid = (0 until 60).map { i ->
            V2Candle(
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.0 + i,
                volume = 1_000.0,
                closeTime = (i + 1L) * 60_000L
            )
        }
        val unsorted = valid.toMutableList().also { it[20] = it[20].copy(closeTime = it[19].closeTime - 1L) }
        val duplicate = valid.toMutableList().also { it[20] = it[20].copy(closeTime = it[19].closeTime) }
        assertTrue(buildSequentialSignalHistory(unsorted, valid, valid, valid, "LONG").isEmpty())
        assertTrue(buildSequentialSignalHistory(valid, duplicate, valid, valid, "LONG").isEmpty())
    }

    @Test fun rejectsNegativeTimestampsAndNonFiniteOrNegativeVolume() {
        val valid = (0 until 60).map { i ->
            V2Candle(
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.0 + i,
                volume = 1_000.0,
                closeTime = (i + 1L) * 60_000L
            )
        }
        val negativeTimestamp = valid.toMutableList().also { it[20] = it[20].copy(closeTime = -1L) }
        val nonFiniteVolume = valid.toMutableList().also { it[20] = it[20].copy(volume = Double.NaN) }
        val negativeVolume = valid.toMutableList().also { it[20] = it[20].copy(volume = -1.0) }
        assertTrue(buildSequentialSignalHistory(negativeTimestamp, valid, valid, valid, "LONG").isEmpty())
        assertTrue(buildSequentialSignalHistory(valid, nonFiniteVolume, valid, valid, "LONG").isEmpty())
        assertTrue(buildSequentialSignalHistory(valid, negativeVolume, valid, valid, "LONG").isEmpty())
    }


    @Test fun rejectsOpenOrCloseOutsideCandleRange() {
        val valid = (0 until 60).map { i ->
            V2Candle(
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.0 + i,
                volume = 1_000.0,
                closeTime = (i + 1L) * 60_000L
            )
        }
        val invalidOpen = valid.toMutableList().also {
            it[20] = it[20].copy(open = it[20].high + 1.0)
        }
        val invalidClose = valid.toMutableList().also {
            it[20] = it[20].copy(close = it[20].low - 1.0)
        }
        assertTrue(buildSequentialSignalHistory(invalidOpen, valid, valid, valid, "LONG").isEmpty())
        assertTrue(buildSequentialSignalHistory(valid, invalidClose, valid, valid, "LONG").isEmpty())
    }

}
