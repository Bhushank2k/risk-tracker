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
        assertEquals(3.0, r.riskReward!!, 1e-8)
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

    @Test fun missingTargetDoesNotInventReward() {
        val r = evaluateTradeRisk("Long", 1000.0, 1.0, 100.0, 98.0, null, 0.0, settings)
        assertTrue(r.valid)
        assertNull(r.riskReward)
    }
}
