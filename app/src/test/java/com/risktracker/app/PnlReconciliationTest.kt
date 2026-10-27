package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PnlReconciliationTest {
    @Test fun normalizesSupportedDateFormats() {
        assertEquals("2026-06-06", canonicalPnlDate("6/6/2026"))
        assertEquals("2026-06-06", canonicalPnlDate("2026-06-06"))
        assertNull(canonicalPnlDate("not-a-date"))
    }

    @Test fun manualWinsButOverlapRemainsVisibleAndDoesNotDoubleCount() {
        val manual = listOf(PnlEntry("6/6/2026", 100.0, 5.0))
        val binance = listOf(BinanceDailyResult("2026-06-06", 2.0, 4.0))
        val rows = reconcileDailyPnl(manual, binance, emptyList())
        assertEquals(1, rows.size)
        assertEquals(DailyPnlSource.MANUAL, rows.single().source)
        assertEquals(5.0, rows.single().selectedPnl, 0.0001)
        assertEquals(1.0, rows.single().discrepancy!!, 0.0001)
    }

    @Test fun usesBinanceAndJournalOnlyForDaysWithoutHigherPrioritySources() {
        val imported = listOf(BinanceDailyResult("2026-06-07", 1.0, 2.0))
        val trades = listOf(
            TradeEntry("a", "2026-06-08", "BTCUSDT", "Long", 100.0, 102.0, 1.0),
            TradeEntry("b", "2026-06-08", "BTCUSDT", "Short", 100.0, 99.0, 1.0)
        )
        val rows = reconcileDailyPnl(emptyList(), imported, trades)
        assertEquals(2, rows.size)
        assertEquals(DailyPnlSource.BINANCE, rows[0].source)
        assertEquals(3.0, rows[0].selectedPnl, 0.0001)
        assertEquals(DailyPnlSource.JOURNAL, rows[1].source)
        assertEquals(3.0, rows[1].selectedPnl, 0.0001)
    }
}
