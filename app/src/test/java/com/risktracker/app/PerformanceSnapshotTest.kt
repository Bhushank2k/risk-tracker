package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

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

    @Test fun filtersDailyRecordsByInclusiveLocalCalendarWindow() {
        val now = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-10-23")!!.time + 12 * 60 * 60 * 1000L
        val entries = listOf(
            PnlEntry("23/10/2026", 100.0, 10.0),
            PnlEntry("17/10/2026", 110.0, 5.0),
            PnlEntry("16/10/2026", 115.0, -4.0),
            PnlEntry("2026-10-24", 111.0, 1.0),
            PnlEntry("not-a-date", 112.0, 2.0)
        )

        val lastSevenDays = filterPnlEntriesForPeriod(entries, 7, now)
        assertEquals(listOf("23/10/2026", "17/10/2026"), lastSevenDays.map { it.date })
        assertEquals(listOf("23/10/2026"), filterPnlEntriesForPeriod(entries, 1, now).map { it.date })
        assertEquals(entries, filterPnlEntriesForPeriod(entries, null, now))
    }

    @Test fun invalidPeriodDoesNotIncludeRecords() {
        val now = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-10-23")!!.time
        val entries = listOf(PnlEntry("23/10/2026", 100.0, 10.0))
        assertEquals(emptyList<PnlEntry>(), filterPnlEntriesForPeriod(entries, 0, now))
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
