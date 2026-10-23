package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BinanceIncomeCsvImporterTest {
    @Test
    fun aggregatesRealizedPnlCommissionsAndFundingByUtcDay() {
        val csv = """symbol,incomeType,income,asset,info,time,tranId,tradeId
BTCUSDT,REALIZED_PNL,100.00,USDT,,2024-01-01 00:00:00,1,11
BTCUSDT,COMMISSION,-2.00,USDT,,2024-01-01 00:00:00,2,11
BTCUSDT,FUNDING_FEE,-1.00,USDT,,2024-01-01 08:00:00,3,
BTCUSDT,TRANSFER,500.00,USDT,,2024-01-01 08:00:00,4,
"""
        val result = BinanceIncomeCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(97.0, result.getValue("2024-01-01"), 0.0000001)
    }

    @Test
    fun reportsIncomeRowCountsAndObservedDateRange() {
        val csv = """symbol,incomeType,income,asset,time,tranId
BTCUSDT,REALIZED_PNL,10,USDT,2024-02-03 10:00:00,1
BTCUSDT,COMMISSION,-1,USDT,2024-02-03 10:01:00,2
BTCUSDT,TRANSFER,100,USDT,2024-06-01 08:00:00,3
BTCUSDT,FUNDING_FEE,-2,USDT,2024-11-20 08:00:00,4
"""
        val report = BinanceIncomeCsvImporter.parseWithReport(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(3, report.includedRows)
        assertEquals(1, report.ignoredRows)
        assertEquals(1, report.rowsByType["REALIZED_PNL"])
        assertEquals(1, report.rowsByType["COMMISSION"])
        assertEquals(1, report.rowsByType["FUNDING_FEE"])
        assertEquals("2024-02-03", report.firstActivityDate)
        assertEquals("2024-11-20", report.lastActivityDate)
        assertEquals(7.0, report.daily.values.sum(), 0.0000001)
    }

    @Test
    fun rejectsRowsOutsideSelectedYear() {
        val csv = """symbol,incomeType,income,asset,time,tranId
BTCUSDT,REALIZED_PNL,5,USDT,2023-12-31 23:59:59,1
BTCUSDT,FUNDING_FEE,-1,USDT,2024-01-01 00:00:00,2
"""
        assertThrows(IllegalArgumentException::class.java) {
            BinanceIncomeCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), 2024)
        }
    }

    @Test
    fun supportsQuotedFieldsContainingCommas() {
        val csv = """symbol,incomeType,income,asset,info,time,tranId
BTCUSDT,REALIZED_PNL,10,USDT,"close, partial",2024-02-03 10:00:00,1
"""
        val result = BinanceIncomeCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(10.0, result.getValue("2024-02-03"), 0.0000001)
    }
}
