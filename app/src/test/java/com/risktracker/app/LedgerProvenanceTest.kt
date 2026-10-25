package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class LedgerProvenanceTest {
    @Test fun keepsJournalEstimatesSeparateFromExchangeRecords() {
        val records = listOf(
            LedgerRecord("journal-1", "2026-10-01", 10.0, "usdt", LedgerSource.JOURNAL_ESTIMATE, "trade-1", estimated = true),
            LedgerRecord("binance-1", "2026-10-01", 9.5, "USDT", LedgerSource.BINANCE_CSV, "income-1")
        )
        val report = auditLedgerRecords(records)
        assertEquals(2, report.totalsBySource.size)
        assertEquals(10.0, report.totalsBySource.first { it.source == LedgerSource.JOURNAL_ESTIMATE }.total, 1e-8)
        assertEquals(9.5, report.totalsBySource.first { it.source == LedgerSource.BINANCE_CSV }.total, 1e-8)
    }

    @Test fun identifiesRepeatedSourceIdsAndInvalidRecords() {
        val records = listOf(
            LedgerRecord("a", "2026-10-01", 2.0, "USDT", LedgerSource.BINANCE_CSV, "row-1"),
            LedgerRecord("b", "2026-10-01", 3.0, "USDT", LedgerSource.BINANCE_CSV, "row-1"),
            LedgerRecord("", "", Double.NaN, "", LedgerSource.MANUAL)
        )
        val report = auditLedgerRecords(records)
        assertEquals(1, report.duplicateSourceRecords.size)
        assertEquals(listOf("a", "b"), report.duplicateSourceRecords.single().recordIds)
        assertTrue(report.invalidRecordIds.isNotEmpty())
    }
}
