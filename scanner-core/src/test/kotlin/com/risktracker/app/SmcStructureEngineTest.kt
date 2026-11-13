package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class SmcStructureEngineTest {
    private fun candle(high: Double, low: Double, close: Double, index: Int): V2Candle =
        V2Candle(high, low, close, 100.0, index.toLong(), close)

    @Test fun swingIsNotExposedUntilRightConfirmationBarsClose() {
        val rows = listOf(
            candle(10.0, 8.0, 9.0, 0),
            candle(11.0, 8.5, 10.0, 1),
            candle(15.0, 9.0, 12.0, 2),
            candle(12.0, 8.0, 9.0, 3)
        )
        assertTrue(confirmedSmcSwings(rows).none { it.index == 2 })
        val confirmed = rows + candle(11.0, 8.2, 9.0, 4)
        assertTrue(confirmedSmcSwings(confirmed).any { it.index == 2 && it.isHigh && it.confirmedAtIndex == 4 })
    }

    @Test fun malformedCandlesFailClosed() {
        val rows = listOf(candle(10.0, 8.0, 9.0, 0), V2Candle(7.0, 8.0, 9.0, 1.0, 1, 8.5))
        assertTrue(confirmedSmcSwings(rows).isEmpty())
        assertEquals(SmcDirection.NEUTRAL, analyzeSmcStructure(rows).bias)
    }

    @Test fun sweepRequiresReclaimAndUsesOnlyPriorRange() {
        val base = (0 until 22).map { i ->
            val center = 100.0 + (i % 3) * 0.1
            candle(center + 1.0, center - 1.0, center, i)
        }
        val noReclaim = base + candle(100.8, 95.0, 96.0, 22)
        assertNull(detectLatestSmcLiquiditySweep(noReclaim, atr = 1.0, lookback = 20))
        val reclaim = base + candle(100.8, 96.0, 100.5, 23)
        val sweep = detectLatestSmcLiquiditySweep(reclaim, atr = 1.0, lookback = 20)
        assertNotNull(sweep)
        assertEquals(SmcLiquidityType.SELL_SIDE, sweep!!.type)
        assertEquals(23, sweep.candleIndex)
    }

    @Test fun invalidParametersReturnNoEvidence() {
        val rows = (0 until 30).map { i -> candle(101.0, 99.0, 100.0, i) }
        assertTrue(confirmedSmcSwings(rows, leftBars = 0).isEmpty())
        assertTrue(detectSmcLiquidityLevels(rows, atr = 0.0).isEmpty())
        assertNull(detectLatestSmcLiquiditySweep(rows, atr = Double.NaN))
    }
}
