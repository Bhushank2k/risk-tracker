package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeIdeaRiskPlanTest {
    private fun idea() = TradeIdea(
        symbol = "BTCUSDT", side = "LONG", score = 85, volume = 1_000_000.0, change = 0.0,
        last = 100.0, entry = 100.0, stop = 90.0, target1 = 120.0, target2 = 130.0,
        entryTriggered = true, rsi = 55.0, volumeRatio = 1.5, bias4h = "Bullish",
        bias1h = "Bullish", setup = "Trend Pullback", reasons = emptyList(), warning = "",
        horizon = "Intraday", bias5m = "Bullish", spreadBps = 2.0, bookImbalance = 0.2,
        fundingRate = 0.0, oiChangePct = null, backtest = TradeIdeaBacktestStats()
    )

    @Test fun positionNotionalIsCappedByEquityAndLeverage() {
        val plan = calculateEntryRiskPlan(idea(), EntryRiskSettings(accountEquity = 100.0, riskPercent = 2.0, maxLeverage = 3))
        assertNotNull(plan)
        assertEquals(300.0, plan!!.positionNotional, 1e-9)
        assertEquals(30.0, plan.riskAmount, 1e-9)
        assertEquals(2.0, plan.requestedRiskAmount, 1e-9)
        assertTrue(plan.leverageCapped)
        assertEquals(100.0, plan.estimatedInitialMargin, 1e-9)
        assertEquals(60.0, plan.target1Profit, 1e-9)
    }

    @Test fun uncappedPositionUsesRequestedRiskBudget() {
        val plan = calculateEntryRiskPlan(idea(), EntryRiskSettings(accountEquity = 10_000.0, riskPercent = 0.5, maxLeverage = 3))
        assertNotNull(plan)
        assertEquals(50.0, plan!!.riskAmount, 1e-9)
        assertEquals(50.0, plan.requestedRiskAmount, 1e-9)
        assertFalse(plan.leverageCapped)
    }
}
