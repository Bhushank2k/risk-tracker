package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BinanceCapitalReconciliationTest {
    private fun row(
        id: String,
        category: String,
        asset: String,
        amount: Double,
        status: String,
        time: Long = 1L
    ) = BinanceStatement(id, time, category, asset, amount, status, "", "")

    @Test
    fun reconcilesCompletedExternalFlowsPerAssetWithoutCombiningCurrencies() {
        val result = BinanceCapitalReconciliationEngine.reconcile(
            listOf(
                row("d1", "Deposits", "USDT", 100.0, "1", 10L),
                row("d2", "Deposits", "USDT", 25.0, "SUCCESS", 20L),
                row("w1", "Withdrawals", "USDT", 30.0, "6", 30L),
                row("d3", "Deposits", "BTC", 0.02, "COMPLETED", 40L),
                row("d4", "Deposits", "USDT", 999.0, "0", 50L),
                row("t1", "Universal transfer", "USDT", 50.0, "SUCCESS", 60L)
            )
        )

        assertEquals(listOf("BTC", "USDT"), result.assetFlows.map { it.asset })
        val btc = result.assetFlows.first { it.asset == "BTC" }
        assertEquals(0.02, btc.netExternalFlow, 0.00000001)
        val usdt = result.assetFlows.first { it.asset == "USDT" }
        assertEquals(125.0, usdt.deposits, 0.00000001)
        assertEquals(30.0, usdt.withdrawals, 0.00000001)
        assertEquals(95.0, usdt.netExternalFlow, 0.00000001)
        assertEquals(4, result.completedExternalFlowRecords)
        assertEquals(6, result.totalStoredRecords)
        assertEquals(10L, result.firstRecordTime)
        assertEquals(60L, result.lastRecordTime)
    }

    @Test
    fun doesNotClaimPartialApiHistoryIsComplete() {
        val result = BinanceCapitalReconciliationEngine.reconcile(
            listOf(row("d1", "Deposits", "USDT", 100.0, "SUCCESS"))
        )

        assertFalse(result.isHistoryComplete)
    }
}
