package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTaxHistoryTest {
    @Test fun parsesBinanceStyleCsvWithQuotedCellsAndRealizedPnl() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120\n" +
            "2020-02-03 00:00:00,ETHUSDT,BUY,2,200,400,0.4,USDT,\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        assertEquals(2, rows.size)
        assertEquals("BTCUSDT", rows[0].symbol)
        assertEquals("SELL", rows[0].side)
        assertEquals(120.0, rows[0].realizedPnl!!, 0.0001)
        assertEquals(2019, rows[0].year)
        assertEquals(null, rows[1].realizedPnl)
    }

    @Test fun repeatedImportsAreDeduplicatedWithoutDroppingExistingRecords() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val (merged, added) = CryptoTaxHistory.deduplicate(rows, rows)
        assertEquals(1, merged.size)
        assertEquals(0, added)
    }

    @Test fun yearlySummaryFlagsMissingRealizedPnlAndConvertsAmountsToInr() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120\n" +
            "2019-07-01 12:30:00,BTCUSDT,BUY,0.5,9000,4500,1,USDT,\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val summary = CryptoTaxHistory.summaries(rows, 80.0).single()
        assertEquals(2019, summary.year)
        assertEquals(2, summary.trades)
        assertEquals(9600.0, summary.realizedPnlInr, 0.0001)
        assertEquals(240.0, summary.feesInr, 0.0001)
        assertEquals(1, summary.missingPnlRecords)
        assertTrue(summary.proceedsInr > 0)
    }
}
