package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class TradeRiskGateTest {
    private val settings = TradeRiskSettings(20.0, 50.0, -10.0, 5, 2)

    @Test fun longRiskIncludesFees() {
        val r = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, 104.0, 0.5, settings)
        assertTrue(r.valid)
        assertEquals(4.75, r.quantity, 1e-8)
        assertEquals(10.0, r.plannedRisk, 1e-8)
        assertEquals(1.9, r.riskReward!!, 1e-8)
    }

    @Test fun shortRiskIsCalculatedCorrectly() {
        val r = evaluateTradeRisk("Short", 1000.0, 1.0, 100.0, 102.0, 96.0, 0.0, settings)
        assertTrue(r.valid)
        assertEquals(5.0, r.quantity, 1e-8)
        assertEquals(2.0, r.riskReward!!, 1e-8)
    }

    @Test fun invalidStopAndTargetDirectionsAreRejected() {
        assertFalse(evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 101.0, 105.0, 0.0, settings).valid)
        assertFalse(evaluateTradeRisk("Short", 1000.0, 1.0, 100.0, 102.0, 105.0, 0.0, settings).valid)
    }

    @Test fun limitBreachesAreReported() {
        val r = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, 104.0, 0.5,
            settings.copy(maxTradeRisk = 5.0, maxDailyLoss = 12.0))
        assertFalse(r.ready)
        assertTrue(r.checks.any { it.title == "Per-trade risk" && !it.passed })
        assertTrue(r.checks.any { it.title == "Daily loss budget" && !it.passed })
    }

    @Test fun invalidNumbersAndTradeCountAreRejected() {
        assertFalse(evaluateTradeRisk("Long", Double.NaN, 1.0, 100.0, 98.0, null, 0.0, settings).valid)
        val atLimit = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, 104.0, 0.0,
            settings.copy(maxTradesPerDay = 3, tradesTakenToday = 3))
        assertTrue(atLimit.errors.any { it.contains("trade-count") })
    }

    @Test fun feesMeetingOrExceedingRiskBudgetRejectsThePlan() {
        val atBudget = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, 104.0, 10.0, settings)
        assertFalse(atBudget.valid)
        assertEquals(0.0, atBudget.quantity, 1e-8)
        assertTrue(atBudget.errors.any { it.contains("meet or exceed") })

        val overBudget = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, 104.0, 12.0, settings)
        assertFalse(overBudget.valid)
        assertEquals(0.0, overBudget.quantity, 1e-8)
    }

    @Test fun missingTargetDoesNotInventReward() {
        val r = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, null, 0.0, settings)
        assertTrue(r.valid)
        assertNull(r.riskReward)
    }
}
