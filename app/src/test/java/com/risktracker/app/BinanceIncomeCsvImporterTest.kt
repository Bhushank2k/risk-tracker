package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BinanceIncomeCsvImporterTest {
    @Test
    fun aggregatesRealizedPnlCommissionsFundingAndInsuranceClearByUtcDay() {
        val csv = """symbol,incomeType,income,asset,info,time,tranId,tradeId
BTCUSDT,REALIZED_PNL,100.00,USDT,,2024-01-01 00:00:00,1,11
BTCUSDT,COMMISSION,-2.00,USDT,,2024-01-01 00:00:00,2,11
BTCUSDT,FUNDING_FEE,-1.00,USDT,,2024-01-01 08:00:00,3,
BTCUSDT,INSURANCE_CLEAR,-0.50,USDT,,2024-01-01 09:00:00,4,
BTCUSDT,TRANSFER,500.00,USDT,,2024-01-01 08:00:00,5,
"""
        val result = BinanceIncomeCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(96.5, result.getValue("2024-01-01"), 0.0000001)
    }

    @Test
    fun reportsIncomeRowCountsAndObservedDateRange() {
        val csv = """symbol,incomeType,income,asset,time,tranId
BTCUSDT,REALIZED_PNL,10,USDT,2024-02-03 10:00:00,1
BTCUSDT,COMMISSION,-1,USDT,2024-02-03 10:01:00,2
BTCUSDT,TRANSFER,100,USDT,2024-06-01 08:00:00,3
BTCUSDT,FUNDING_FEE,-2,USDT,2024-11-20 08:00:00,4
BTCUSDT,INSURANCE_CLEAR,-0.5,USDT,2024-11-20 09:00:00,5
"""
        val report = BinanceIncomeCsvImporter.parseWithReport(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(4, report.includedRows)
        assertEquals(1, report.ignoredRows)
        assertEquals(1, report.rowsByType["REALIZED_PNL"])
        assertEquals(1, report.rowsByType["COMMISSION"])
        assertEquals(1, report.rowsByType["FUNDING_FEE"])
        assertEquals(1, report.rowsByType["INSURANCE_CLEAR"])
        assertEquals("2024-02-03", report.firstActivityDate)
        assertEquals("2024-11-20", report.lastActivityDate)
        assertEquals(6.5, report.daily.values.sum(), 0.0000001)
    }

    @Test
    fun skipsExactDuplicateRowsAndReportsCount() {
        val csv = """symbol,incomeType,income,asset,time,tranId
BTCUSDT,REALIZED_PNL,10,USDT,2024-02-03 10:00:00,1
BTCUSDT,REALIZED_PNL,10,USDT,2024-02-03 10:00:00,1
BTCUSDT,REALIZED_PNL,10,USDT,2024-02-03 10:01:00,2
"""
        val report = BinanceIncomeCsvImporter.parseWithReport(ByteArrayInputStream(csv.toByteArray()), 2024)
        assertEquals(2, report.includedRows)
        assertEquals(1, report.duplicateRows)
        assertEquals(20.0, report.daily.values.sum(), 0.0000001)
    }

    @Test
    fun rejectsMalformedAmountsAndMissingRequiredFieldsWithRowNumbers() {
        val badAmount = """symbol,incomeType,income,asset,time
BTCUSDT,REALIZED_PNL,NaN,USDT,2024-02-03 10:00:00
"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            BinanceIncomeCsvImporter.parse(ByteArrayInputStream(badAmount.toByteArray()), 2024)
        }
        org.junit.Assert.assertTrue(error.message!!.contains("row 2"))

        val missingType = """symbol,incomeType,income,asset,time
BTCUSDT,,10,USDT,2024-02-03 10:00:00
"""
        val missingError = assertThrows(IllegalArgumentException::class.java) {
            BinanceIncomeCsvImporter.parse(ByteArrayInputStream(missingType.toByteArray()), 2024)
        }
        org.junit.Assert.assertTrue(missingError.message!!.contains("Missing required income type"))
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
