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
