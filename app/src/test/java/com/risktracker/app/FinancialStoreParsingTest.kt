package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialStoreParsingTest {
    @Test(expected = org.json.JSONException::class)
    fun malformedBinanceFillStoreIsRejectedInsteadOfParsedAsEmpty() {
        parseBinanceJournalFills("not-json")
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedDailyPnlStoreIsRejectedInsteadOfParsedAsEmpty() {
        parseBinanceDailyResults("{broken")
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedStatementStoreIsRejectedInsteadOfParsedAsEmpty() {
        parseBinanceStatements("not-json")
    }

    @Test
    fun validDailyPnlRowsStillParse() {
        val rows = parseBinanceDailyResults("""[{"date":"2026-11-05","spotPnl":1.5,"futuresPnl":-2.0}]""")
        assertEquals(1, rows.size)
        assertEquals("2026-11-05", rows.single().date)
        assertEquals(1.5, rows.single().spotPnl, 0.000001)
        assertEquals(-2.0, rows.single().futuresPnl, 0.000001)
    }
}
