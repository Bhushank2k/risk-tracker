package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class TradeIdeaAnalyticsTest {
    @Test fun positionSizeRespectsHalfPercentRisk() {
        val result = TradeSizing.calculate(10_000.0, 100.0, 99.0)!!
        assertEquals(50.0, result.riskAmount, 0.000001)
        assertEquals(0.5, result.actualRiskPercent, 0.000001)
        assertEquals(5_000.0, result.notional, 0.000001)
        assertEquals(1_666.6666667, result.marginAtMaxLeverage, 0.0001)
    }

    @Test fun positionSizeCapsNotionalAtThreeTimesEquity() {
        val result = TradeSizing.calculate(1_000.0, 100.0, 99.99)!!
        assertEquals(3_000.0, result.notional, 0.000001)
        assertEquals(3.0, result.notional / 1_000.0, 0.000001)
        assertTrue(result.actualRiskPercent <= 0.5)
    }

    @Test fun invalidSizingInputsReturnNull() {
        assertNull(TradeSizing.calculate(0.0, 100.0, 99.0))
        assertNull(TradeSizing.calculate(1000.0, 100.0, 100.0))
        assertNull(TradeSizing.calculate(1000.0, -1.0, 99.0))
    }
}
