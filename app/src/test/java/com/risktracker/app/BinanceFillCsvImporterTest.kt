package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BinanceFillCsvImporterTest {
    private fun parse(csv: String) = BinanceFillCsvImporter.parse(
        ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)), "USDⓈ-M Futures"
    )

    private val noIdHeader = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset,Order ID\\n"
    private val identicalRow = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50,0.01,USDT,order-1\\n"

    @Test fun identicalRowsWithoutTradeIdsRemainSeparateExecutions() {
        val report = parse(noIdHeader + identicalRow + identicalRow)
        assertEquals(2, report.fills.size)
        assertTrue(report.fills[0].id != report.fills[1].id)
        assertEquals(0, report.duplicateRows)
    }

    @Test fun repeatedNoIdImportRowsKeepStableOccurrenceIds() {
        val first = parse(noIdHeader + identicalRow + identicalRow).fills
        val second = parse(noIdHeader + identicalRow + identicalRow).fills
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
    }

    @Test fun exchangeTradeIdsDeduplicateProvenDuplicateRows() {
        val header = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset,Trade ID\\n"
        val row = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50,0.01,USDT,trade-123\\n"
        val report = parse(header + row + row)
        assertEquals(1, report.fills.size)
        assertEquals(1, report.duplicateRows)
    }

    @Test fun csvToApiUpgradeMatchesEachExecutionOnlyOnce() {
        fun fill(id: String, csv: Boolean) = BinanceJournalFill(
            id = id, exchange = "Binance", market = "USDⓈ-M Futures", symbol = "BTCUSDT",
            side = "BUY", quantity = 0.5, price = 100.0, quoteAmount = 50.0,
            fee = 0.0, feeAsset = "USDT", realizedPnl = null, time = 1_746_091_200_000,
            orderId = "order-1"
        )
        val csvRows = listOf(fill("binance:csv:futures:BTCUSDT:a:0", true), fill("binance:csv:futures:BTCUSDT:a:1", true))
        val apiRows = listOf(fill("binance:futures:BTCUSDT:1", false), fill("binance:futures:BTCUSDT:2", false))
        val (upgraded, inserted) = mergeBinanceJournalFills(csvRows, apiRows)
        assertEquals(2, upgraded.size)
        assertEquals(0, inserted)
        assertTrue(upgraded.none { it.id.contains(":csv:") })
    }

    @Test fun sameSourceIdenticalApiExecutionsAreNotCollapsed() {
        fun fill(id: String) = BinanceJournalFill(
            id = id, exchange = "Binance", market = "USDⓈ-M Futures", symbol = "BTCUSDT",
            side = "BUY", quantity = 0.5, price = 100.0, quoteAmount = 50.0,
            fee = 0.0, feeAsset = "USDT", realizedPnl = null, time = 1_746_091_200_000,
            orderId = "order-1"
        )
        val (merged, inserted) = mergeBinanceJournalFills(emptyList(), listOf(fill("api:1"), fill("api:2")))
        assertEquals(2, merged.size)
        assertEquals(2, inserted)
    }
}
