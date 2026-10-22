package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceSnapshotTest {
    @Test fun calculatesNetWinRateAndBestWorstDays() {
        val result = buildPerformanceSnapshot(
            pnlValues = listOf(100.0, -40.0, 0.0, 20.0),
            closingBalances = listOf(1000.0, 1100.0, 1050.0, 1200.0),
            tradeCount = 7
        )
        assertEquals(80.0, result.netPnl, 0.0001)
        assertEquals(66.6667, result.winRatePercent, 0.001)
        assertEquals(2, result.winningDays)
        assertEquals(1, result.losingDays)
        assertEquals(100.0, result.bestDay!!, 0.0001)
        assertEquals(-40.0, result.worstDay!!, 0.0001)
        assertEquals(7, result.tradeCount)
    }

    @Test fun calculatesPeakToTroughDrawdownAndHandlesEmptyData() {
        val result = buildPerformanceSnapshot(
            pnlValues = emptyList(),
            closingBalances = listOf(100.0, 80.0, 90.0),
            tradeCount = -1
        )
        assertEquals(20.0, result.maxDrawdownPercent, 0.0001)
        assertEquals(0.0, result.netPnl, 0.0001)
        assertEquals(0.0, result.winRatePercent, 0.0001)
        assertEquals(0, result.tradeCount)
    }
}
