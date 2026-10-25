package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPlanTest {
    private val eps = 1e-6

    @Test
    fun calculatesRequiredDailyProfitEstimateAndProgress() {
        val plan = calculateTargetPlan(100.0, 200.0, 20, 5.0)
        assertTrue(plan.validBalances)
        assertEquals(100.0, plan.profitNeeded!!, eps)
        assertEquals(5.0, plan.requiredDailyProfit!!, eps)
        assertEquals(20, plan.estimatedDays)
        assertEquals(0.5f, plan.progress, eps.toFloat())
    }

    @Test
    fun acceptsZeroStartingBalanceButRequiresHigherFiniteTarget() {
        val zeroStart = calculateTargetPlan(0.0, 50.0, 10, 5.0)
        assertTrue(zeroStart.validBalances)
        assertEquals(50.0, zeroStart.profitNeeded!!, eps)
        assertFalse(calculateTargetPlan(-1.0, 50.0, 10, 5.0).validBalances)
        assertFalse(calculateTargetPlan(50.0, 50.0, 10, 5.0).validBalances)
        assertFalse(calculateTargetPlan(60.0, 50.0, 10, 5.0).validBalances)
    }

    @Test
    fun rejectsNonFiniteBalancesAndDailyPaces() {
        assertFalse(calculateTargetPlan(Double.NaN, 200.0, 20, 5.0).validBalances)
        assertFalse(calculateTargetPlan(100.0, Double.POSITIVE_INFINITY, 20, 5.0).validBalances)
        assertNull(calculateTargetPlan(100.0, 200.0, 20, Double.NaN).estimatedDays)
        assertNull(calculateTargetPlan(100.0, 200.0, 20, Double.POSITIVE_INFINITY).estimatedDays)
    }

    @Test
    fun missingOrInvalidDaysAndDailyPaceOmitOnlyTheirDependentMetrics() {
        val noDays = calculateTargetPlan(100.0, 200.0, 0, 5.0)
        assertTrue(noDays.validBalances)
        assertNull(noDays.requiredDailyProfit)
        assertEquals(20, noDays.estimatedDays)

        assertNull(calculateTargetPlan(100.0, 200.0, 20, 0.0).estimatedDays)
        assertNull(calculateTargetPlan(100.0, 200.0, 20, -1.0).estimatedDays)
    }

    @Test
    fun extremelySmallPaceDoesNotOverflowEstimatedDays() {
        val plan = calculateTargetPlan(0.0, Double.MAX_VALUE, 30, Double.MIN_VALUE)
        assertTrue(plan.validBalances)
        assertNull(plan.estimatedDays)
    }
}
