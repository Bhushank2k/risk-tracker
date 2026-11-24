package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BinanceFillCsvImporterTest {
    private fun parse(csv: String) = BinanceFillCsvImporter.parse(
        ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)), "USDⓈ-M Futures"
    )

    private val noIdHeader = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset,Order ID\n"
    private val identicalRow = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50,0.01,USDT,order-1\n"

    @Test fun parsesAttachedUsdtFeeAndExactBinanceFuturesHeaders() {
        val csv = "\uFEFFUid,Time,Symbol,Side,Price,Quantity,Amount,Fee,Realized Profit,Buyer,Maker,Trade ID,Order ID\\n" +
            "90488738,2026-11-24 13:46:41,LISTAUSDT,SELL,0.11175,41,4.58175,0.00229087USDT,0.20177378,false,false,154376830,2033186077\\n"
        val report = parse(csv)
        assertEquals(1, report.fills.size)
        assertEquals(0.00229087, report.fills.single().fee, 1e-12)
        assertEquals("USDT", report.fills.single().feeAsset)
        assertEquals(0.20177378, report.fills.single().realizedPnl!!, 1e-12)
        assertTrue(report.fills.single().id.contains("154376830"))
    }

    @Test fun savedJournalParserSkipsOneDamagedRecordWithoutLosingValidHistory() {
        val raw = """[
            {"id":"valid-1","exchange":"Binance","market":"USDⓈ-M Futures","symbol":"BTCUSDT","side":"BUY","quantity":1,"price":100,"quoteAmount":100,"fee":0.1,"feeAsset":"USDT","realizedPnl":null,"time":1746091200000,"orderId":"order-1"},
            {"id":"broken","symbol":"BTCUSDT","quantity":"not-a-number"}
        ]"""
        val result = BinanceAutoJournalStore.parseSavedFills(raw)
        assertEquals(1, result.fills.size)
        assertEquals("valid-1", result.fills.single().id)
        assertEquals(1, result.skippedRecords)
        assertTrue(!result.unreadableStore)
    }

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
        val header = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset,Trade ID\n"
        val row = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50,0.01,USDT,trade-123\n"
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

    @Test fun rejectsNumericPrefixesAndMalformedOptionalAmounts() {
        assertThrows(IllegalStateException::class.java) {
            parse(noIdHeader + identicalRow.replace(",100,0.5,", ",100USDTjunk,0.5,"))
        }
        val header = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset\n"
        val badFee = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50,0.01oops,USDT\n"
        assertThrows(IllegalStateException::class.java) { parse(header + badFee) }
        val badQuote = "2025-05-01 10:00:00,BTCUSDT,BUY,100,0.5,50xyz,0,USDT\n"
        assertThrows(IllegalStateException::class.java) { parse(header + badQuote) }
    }

    @Test fun rejectsOverflowInDerivedQuoteAmount() {
        val header = "Date(UTC),Symbol,Side,Price,Quantity,Amount,Fee,Fee Asset\n"
        val row = "2025-05-01 10:00:00,BTCUSDT,BUY,1e308,1e308,,0,USDT\n"
        assertThrows(IllegalArgumentException::class.java) { parse(header + row) }
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
