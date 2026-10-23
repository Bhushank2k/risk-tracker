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
            "2019-07-01 12:30:00,BTCUSDT,SELL,0.5,9000,4500,1,USDT,\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val summary = CryptoTaxHistory.summaries(rows, 80.0).single()
        assertEquals(2019, summary.year)
        assertEquals(2, summary.trades)
        assertEquals(9600.0, summary.realizedPnlInr, 0.0001)
        assertEquals(240.0, summary.feesInr, 0.0001)
        assertEquals(1, summary.missingPnlRecords)
        assertTrue(summary.proceedsInr > 0)
    }

    @Test fun groupsTradesByIndianFinancialYearAcrossAprilBoundary() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-03-31 23:59:00,BTCUSDT,SELL,0.1,10000,1000,1,USDT,50\n" +
            "2025-04-01 00:01:00,BTCUSDT,SELL,0.1,11000,1100,1,USDT,60\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        assertEquals(2024, rows[0].year) // FY 2024-25
        assertEquals(2025, rows[1].year) // FY 2025-26
        assertEquals(listOf(2024, 2025), CryptoTaxHistory.summaries(rows, 1.0).map { it.year })
    }

    @Test fun dataQualityFlagsMissingPnlAndIncompleteTradeFields() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-05-01 12:00:00,BTCUSDT,SELL,0,0,0,2,,\n" +
            "2025-05-02 12:00:00,ETHUSDT,UNKNOWN,1,100,100,0,USDT,\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val quality = CryptoTaxHistory.dataQuality(rows)
        assertEquals(1, quality.missingRealizedPnl)
        assertEquals(1, quality.unknownSide)
        assertEquals(1, quality.invalidQuantityOrPrice)
        assertEquals(1, quality.missingFeeAsset)
        assertTrue(quality.score < 100)
        assertTrue(quality.warnings.isNotEmpty())
    }

    @Test fun fifoReviewMatchesBuysAndFlagsSellQuantityWithoutImportedCostBasis() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n" +
            "2025-05-02 10:00:00,BTCUSDT,SELL,1.5,120,180,0,USDT,\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val review = CryptoTaxHistory.costBasisReview(rows, 80.0)
        assertEquals(8000.0, review.estimatedMatchedCostInr, 0.0001)
        assertEquals(0.5, review.unmatchedSellQuantity, 0.0001)
        assertEquals(1, review.sellRowsWithoutPriorBuys)
    }

    @Test fun printableTaxReportIncludesAssumptionsMissingPnlAndTransactionDetails() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL,TradeId\\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120,tx-1\\n" +
            "2019-07-01 12:30:00,ETHUSDT,SELL,1,200,200,1,USDT,,tx-2\\n"
        val trades = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val report = CryptoTaxHistory.printableReportLines(trades, 80.0)
        assertTrue(report.any { it.contains("RISK TRACKER") })
        assertTrue(report.any { it.contains("not a tax-filing determination") })
        assertTrue(report.any { it.contains("FY 2019-20") })
        assertTrue(report.any { it.contains("Sell rows missing realized P&L: 1") })
        assertTrue(report.any { it.contains("realized P&L MISSING") })
        assertTrue(report.any { it.contains("transaction coverage") })
    }

    @Test fun reconciliationFlagsPossibleDuplicatesWithoutDeletingRows() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n"
        val row = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv").single()
        val review = CryptoTaxHistory.costBasisReview(listOf(row, row.copy(id = "other-source")), 80.0)
        assertEquals(1, review.possibleDuplicateRows)
    }
}
