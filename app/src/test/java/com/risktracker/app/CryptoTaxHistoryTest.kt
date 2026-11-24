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

    @Test fun parsesBinanceFeeWithAttachedAssetSuffixAndRejectsConflicts() {
        val csv = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Realized Profit,Trade ID\\n" +
            "2026-11-24 13:46:41,LISTAUSDT,SELL,0.11175,41,4.58175,0.00229087USDT,0.20177378,154376830\\n"
        val trade = CryptoTaxHistory.parseCsv(csv, "binance-futures-trade-history.csv").single()
        assertEquals(0.00229087, trade.fee, 1e-12)
        assertEquals("USDT", trade.feeAsset)

        val conflicting = csv.replace("0.00229087USDT", "0.00229087oops").replace(",Trade ID", ",Fee Asset,Trade ID")
            .replace(",0.20177378,154376830", ",0.20177378,USDT,154376830")
        val result = CryptoTaxHistory.parseCsvWithDiagnostics(conflicting, "binance-futures-trade-history.csv")
        assertTrue(result.trades.isEmpty())
        assertEquals(1, result.issues.size)
    }

    @Test fun importDiagnosticsIdentifyInvalidTimestampAndMissingSymbolRows() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset\n" +
            "not-a-date,BTCUSDT,BUY,1,100,100,0,USDT\n" +
            "2025-05-01 10:00:00,,SELL,1,120,120,0,USDT\n" +
            "2025-05-02 10:00:00,ETHUSDT,BUY,1,100,100,0,USDT"
        val result = CryptoTaxHistory.parseCsvWithDiagnostics(csv, "spot.csv")
        assertEquals(1, result.trades.size)
        assertEquals(2, result.issues.size)
        assertEquals(2, result.issues[0].rowNumber)
        assertTrue(result.issues[0].reason.contains("date/time"))
        assertEquals(3, result.issues[1].rowNumber)
        assertTrue(result.issues[1].reason.contains("symbol"))
    }

    @Test fun allInvalidRowsRemainVisibleAsDiagnostics() {
        val csv = "Date(UTC),Pair,Side,Executed,Price\n" +
            "bad-date,BTCUSDT,BUY,1,100"
        val result = CryptoTaxHistory.parseCsvWithDiagnostics(csv, "bad.csv")
        assertTrue(result.trades.isEmpty())
        assertEquals(1, result.issues.size)
    }

    @Test fun repeatedImportsAreDeduplicatedWithoutDroppingExistingRecords() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120\n"
        val rows = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val (merged, added) = CryptoTaxHistory.deduplicate(rows, rows)
        assertEquals(1, merged.size)
        assertEquals(0, added)
    }

    @Test fun identicalNoIdExecutionsWithinOneExportArePreservedAndReimportsAreIdempotent() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n" +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n"
        val parsed = CryptoTaxHistory.parseCsv(csv, "spot-export.csv")
        assertEquals(2, parsed.size)
        assertEquals(parsed[0].id, parsed[1].id)

        val (firstImport, firstAdded) = CryptoTaxHistory.deduplicate(emptyList(), parsed)
        assertEquals(2, firstImport.size)
        assertEquals(2, firstAdded)

        val (reimported, reimportAdded) = CryptoTaxHistory.deduplicate(firstImport, parsed)
        assertEquals(2, reimported.size)
        assertEquals(0, reimportAdded)
    }

    @Test fun laterExportCanAddAnotherIdenticalExecutionWithoutTradeId() {
        val oneRow = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n"
        val twoRows = oneRow +
            "2025-05-01 10:00:00,BTCUSDT,BUY,1,100,100,0,USDT,\n"
        val first = CryptoTaxHistory.parseCsv(oneRow, "part-a.csv")
        val later = CryptoTaxHistory.parseCsv(twoRows, "part-b.csv")
        val (merged, added) = CryptoTaxHistory.deduplicate(first, later)
        assertEquals(2, merged.size)
        assertEquals(1, added)
    }

    @Test fun stableExchangeTradeIdsDeduplicateAcrossRenamedAndReorderedExports() {
        val header = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,TradeId,Realized PnL\n"
        val firstCsv = header +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,tx-101,120\n" +
            "2019-06-01 12:31:00,ETHUSDT,BUY,2,200,400,0.4,USDT,tx-202,\n"
        val reorderedCsv = header +
            "2019-06-01 12:31:00,ETHUSDT,BUY,2,200,400,0.4,USDT,tx-202,\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,tx-101,120\n"

        val first = CryptoTaxHistory.parseCsv(firstCsv, "spot-export.csv")
        val second = CryptoTaxHistory.parseCsv(reorderedCsv, "renamed-spot-export.csv")
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        val (merged, added) = CryptoTaxHistory.deduplicate(first, second)
        assertEquals(2, merged.size)
        assertEquals(0, added)
    }

    @Test fun tradeIdUsesSameCanonicalIdentityAsBinanceApiFill() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,TradeId\\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,tx-101\\n"
        val trade = CryptoTaxHistory.parseCsv(csv, "futures-trades.csv").single()
        assertEquals("binance:futures:BTCUSDT:tx-101", trade.id)
    }

    @Test fun journalApiFillReconcilesWithHistoricalCsvTradeId() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,TradeId\\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,tx-101\\n"
        val imported = CryptoTaxHistory.parseCsv(csv, "futures-trades.csv")
        val apiFill = imported.single().copy(
            id = "binance:futures:BTCUSDT:tx-101",
            source = "Journal API sync",
            market = "Futures"
        )
        val merged = CryptoTaxHistory.mergeJournalHistory(imported, listOf(apiFill))
        assertEquals(1, merged.size)
        assertEquals("Journal API sync", merged.single().source)
    }

    @Test fun identicalSameSourceJournalFillsRemainSeparateExecutions() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset\\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT\\n"
        val imported = CryptoTaxHistory.parseCsv(csv, "futures-trades.csv")
        val journal = listOf(
            imported.single().copy(id = "binance:futures:BTCUSDT:fill-1", source = "Journal API sync", market = "Futures"),
            imported.single().copy(id = "binance:futures:BTCUSDT:fill-2", source = "Journal API sync", market = "Futures")
        )
        assertEquals(2, CryptoTaxHistory.mergeJournalHistory(emptyList(), journal).size)
    }

    @Test fun fallbackTradeFingerprintDoesNotDependOnFilenameOrRowNumber() {
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120\n"
        val first = CryptoTaxHistory.parseCsv(csv, "spot-export-a.csv").single()
        val second = CryptoTaxHistory.parseCsv(csv, "spot-export-b.csv").single()
        assertEquals(first.id, second.id)
        assertEquals(first.id, CryptoTaxHistory.deduplicate(listOf(first), listOf(second)).first.single().id)
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
        val csv = "Date(UTC),Pair,Side,Executed,Price,Amount,Fee,Fee Asset,Realized PnL,TradeId\n" +
            "2019-06-01 12:30:00,BTCUSDT,SELL,0.5,10000,5000,2,USDT,120,tx-1\n" +
            "2019-07-01 12:30:00,ETHUSDT,SELL,1,200,200,1,USDT,,tx-2\n"
        val trades = CryptoTaxHistory.parseCsv(csv, "spot-trades.csv")
        val report = CryptoTaxHistory.printableReportLines(trades, 80.0)
        assertTrue(report.any { it.contains("RISK TRACKER") })
        assertTrue(report.any { it.contains("not a tax-filing determination", ignoreCase = true) })
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
