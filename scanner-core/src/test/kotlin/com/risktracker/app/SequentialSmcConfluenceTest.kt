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


    @Test fun balancedEntrySequenceNeedsOnlyM15SetupThenM5Entry() {
        val events = listOf(
            SequentialSignalEvent(SequentialSignalStage.H4_BIAS, SmcDirection.BEARISH, 500L),
            SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, 3_000L),
            SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, 4_000L)
        )
        val result = evaluateEntrySequence(events, SmcDirection.BULLISH, 5_000L)
        assertTrue(result.ready)
        assertEquals(2, result.events.size)
    }

    @Test fun balancedEntrySequenceRejectsStaleOrOutOfOrderExecution() {
        val stale = listOf(
            SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, 1_000L),
            SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, 4_000L)
        )
        assertFalse(evaluateEntrySequence(stale, SmcDirection.BULLISH, 60 * 60_000L).ready)

        val reversed = listOf(
            SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, 4_000L),
            SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, 3_000L)
        )
        assertFalse(evaluateEntrySequence(reversed, SmcDirection.BULLISH, 5_000L).ready)
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


    @Test fun incrementalHistoryMatchesClosedPrefixReference() {
        fun candles(size: Int, interval: Long) = (0 until size).map { i ->
            val close = 100.0 + kotlin.math.sin(i * .17) * 4.0 +
                kotlin.math.cos(i * .07) * 2.0 + i * .015
            V2Candle(
                high = close + .5 + kotlin.math.abs(kotlin.math.sin(i * .3)),
                low = close - .5 - kotlin.math.abs(kotlin.math.cos(i * .23)),
                close = close,
                volume = 1_000.0 + (i % 9) * 200.0,
                closeTime = (i + 1L) * interval
            )
        }
        fun ema(values: List<Double>, period: Int): Double? {
            if (values.size < period) return null
            val alpha = 2.0 / (period + 1)
            var result = values.take(period).average()
            values.drop(period).forEach { result = it * alpha + result * (1.0 - alpha) }
            return result.takeIf { it.isFinite() }
        }
        fun atr(bars: List<V2Candle>): Double? {
            if (bars.size < 16) return null
            return bars.zipWithNext { previous, current ->
                maxOf(current.high - current.low,
                    kotlin.math.abs(current.high - previous.close),
                    kotlin.math.abs(current.low - previous.close))
            }.takeLast(14).average().takeIf { it.isFinite() && it > 0.0 }
        }
        val h4 = candles(90, 4 * 60 * 60_000L)
        val h1 = candles(110, 60 * 60_000L)
        val m15 = candles(120, 15 * 60_000L)
        val m5 = candles(150, 5 * 60_000L)
        val expected = mutableListOf<SequentialSignalEvent>()
        for (end in 50..h4.size) {
            val bars = h4.take(end)
            val fast = ema(bars.map { it.close }, 20) ?: continue
            val slow = ema(bars.map { it.close }, 50) ?: continue
            val bias = when {
                bars.last().close > slow && fast > slow -> SmcDirection.BULLISH
                bars.last().close < slow && fast < slow -> SmcDirection.BEARISH
                else -> SmcDirection.NEUTRAL
            }
            expected += SequentialSignalEvent(SequentialSignalStage.H4_BIAS, bias, bars.last().closeTime)
        }
        for (end in 9..h1.size) {
            val bars = h1.take(end)
            val bias = when (classifyTradeIdeaStructure(bars)) {
                TradeIdeaStructure.BULLISH -> SmcDirection.BULLISH
                TradeIdeaStructure.BEARISH -> SmcDirection.BEARISH
                else -> SmcDirection.NEUTRAL
            }
            expected += SequentialSignalEvent(SequentialSignalStage.H1_STRUCTURE, bias, bars.last().closeTime)
        }
        for (end in 45..m15.size) {
            val bars = m15.take(end)
            val ema20 = ema(bars.map { it.close }, 20) ?: continue
            val current = bars.last()
            val previous = bars[bars.lastIndex - 1]
            val currentAtr = atr(bars) ?: continue
            val averageVolume = bars.dropLast(1).takeLast(20).map { it.volume }.average()
            if (averageVolume <= 0.0) continue
            val participation = confluencePullbackVolumeConfirmed(previous.volume, current.volume, averageVolume)
            val bullish = current.low <= ema20 + currentAtr * .2 && current.low >= ema20 - currentAtr * .7 &&
                current.close > ema20 && current.close > previous.close && participation
            val bearish = current.high >= ema20 - currentAtr * .2 && current.high <= ema20 + currentAtr * .7 &&
                current.close < ema20 && current.close < previous.close && participation
            if (bullish) expected += SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BULLISH, current.closeTime)
            if (bearish) expected += SequentialSignalEvent(SequentialSignalStage.M15_SETUP, SmcDirection.BEARISH, current.closeTime)
        }
        for (end in 30..m5.size) {
            val bars = m5.take(end)
            val closes = bars.map { it.close }
            val fast = ema(closes, 9) ?: continue
            val slow = ema(closes, 21) ?: continue
            if (bars.last().close > fast && fast > slow)
                expected += SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BULLISH, bars.last().closeTime)
            if (bars.last().close < fast && fast < slow)
                expected += SequentialSignalEvent(SequentialSignalStage.M5_ENTRY, SmcDirection.BEARISH, bars.last().closeTime)
        }
        assertEquals(
            expected.sortedBy { it.closeTime },
            buildSequentialSignalHistory(h4, h1, m15, m5, "LONG")
        )
    }

}
