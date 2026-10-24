package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class RiskScenarioLabTest {
    @Test fun calculatesFractionalLossStreaks() {
        val result = calculateLossScenarios(1000.0, 1.0, listOf(10, 5, 3))
        assertTrue(result.valid)
        assertEquals(3, result.scenarios.first().lossStreak)
        assertEquals(970.299, result.scenarios.first().endingEquity, 1e-6)
        assertEquals(9.561792, result.scenarios.last().drawdownPercent, 1e-5)
    }
    @Test fun rejectsInvalidInputs() {
        assertFalse(calculateLossScenarios(0.0, 1.0).valid)
        assertFalse(calculateLossScenarios(100.0, 101.0).valid)
        assertFalse(calculateLossScenarios(100.0, 1.0, listOf(-1)).valid)
    }
    @Test fun seededSimulationIsRepeatable() {
        val a = simulateHistoricalOutcomes(1000.0, 1.0, listOf(-1.0, 1.5, -0.5), 20, 100, 42L)
        val b = simulateHistoricalOutcomes(1000.0, 1.0, listOf(-1.0, 1.5, -0.5), 20, 100, 42L)
        assertEquals(a, b)
        assertEquals(100, a.size)
    }
}
