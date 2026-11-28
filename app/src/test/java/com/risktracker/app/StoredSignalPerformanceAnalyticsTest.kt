package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSignalPerformanceAnalyticsTest {
    @Test
    fun cohortMetricsUseChronologicalNetRAndComputeDrawdown() {
        val row = StoredSignalPerformanceAnalyzer.summarizeObservations(
            "Pullback",
            listOf(
                StoredSignalPerformanceObservation(3L, 2.0),
                StoredSignalPerformanceObservation(1L, 1.0),
                StoredSignalPerformanceObservation(2L, -1.0)
            )
        )

        assertEquals(3, row.samples)
        assertEquals(2, row.wins)
        assertEquals(1, row.losses)
        assertEquals(200.0 / 3.0, row.winRatePct, 1e-9)
        assertEquals(2.0 / 3.0, row.expectancyNetR, 1e-9)
        assertEquals(3.0, row.profitFactor!!, 1e-9)
        assertEquals(1.0, row.maxDrawdownR, 1e-9)
        assertFalse(row.hasMinimumSample)
    }

    @Test
    fun insufficientSamplesRemainExplicitlyUnqualified() {
        val rows = (1L..29L).map { StoredSignalPerformanceObservation(it, 1.0) }
        val row = StoredSignalPerformanceAnalyzer.summarizeObservations("Breakout", rows)

        assertEquals(29, row.samples)
        assertFalse(row.hasMinimumSample)
        assertTrue(row.profitFactor!!.isInfinite())
    }

    @Test
    fun exactlyThirtySamplesMeetMinimumEstimateThreshold() {
        val rows = (1L..30L).map { StoredSignalPerformanceObservation(it, 0.5) }
        val row = StoredSignalPerformanceAnalyzer.summarizeObservations("Reversal", rows)

        assertEquals(30, row.samples)
        assertTrue(row.hasMinimumSample)
        assertEquals(0.5, row.expectancyNetR, 0.0)
    }

    @Test
    fun nonFiniteObservationsAreExcluded() {
        val row = StoredSignalPerformanceAnalyzer.summarizeObservations(
            "Mixed",
            listOf(
                StoredSignalPerformanceObservation(1L, 1.0),
                StoredSignalPerformanceObservation(2L, Double.NaN),
                StoredSignalPerformanceObservation(3L, Double.POSITIVE_INFINITY)
            )
        )

        assertEquals(1, row.samples)
        assertEquals(1, row.wins)
        assertEquals(1.0, row.expectancyNetR, 0.0)
    }
}
