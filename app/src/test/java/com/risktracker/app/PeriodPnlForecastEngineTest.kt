package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class PeriodPnlForecastEngineTest {
    private fun row(day: Int, pnl: Double) = ReconciledDailyPnl(
        "2026-01-%02d".format(day), null, pnl, null, pnl, DailyPnlSource.BINANCE, null
    )

    @Test fun projectsCurrentPnlAndResampledRemainingSessions() {
        val history = (1..20).map { row(it, 10.0) }
        val result = PeriodPnlForecastEngine.forecast(
            history, "2026-02-01", currentPeriodPnl = 25.0,
            remainingSessions = 3, targetPnl = 50.0, seed = 7
        )
        assertEquals(20, result.sampleCount)
        assertEquals(55.0, result.expectedEndPnl!!, 1e-8)
        assertEquals(100.0, result.targetReachPercent!!, 1e-8)
        assertEquals(25.0 / 3.0, result.requiredAverageDailyPnl!!, 1e-8)
    }

    @Test fun insufficientHistoryDoesNotProduceProjection() {
        val result = PeriodPnlForecastEngine.forecast(
            listOf(row(1, 4.0)), "2026-02-01", 12.0, 5, targetPnl = 40.0
        )
        assertNull(result.expectedEndPnl)
        assertNull(result.targetReachPercent)
        assertEquals(ForecastReliability.INSUFFICIENT, result.reliability)
    }

    @Test fun noRemainingSessionsUsesCurrentPnlWithoutDivisionByZero() {
        val result = PeriodPnlForecastEngine.forecast(
            (1..10).map { row(it, 2.0) }, "2026-02-01", 17.0, 0, targetPnl = 15.0
        )
        assertEquals(17.0, result.expectedEndPnl!!, 1e-8)
        assertEquals(100.0, result.targetReachPercent!!, 1e-8)
        assertNull(result.requiredAverageDailyPnl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnreasonableRemainingSessionCount() {
        PeriodPnlForecastEngine.forecast(emptyList(), "2026-02-01", 0.0, 61)
    }
}
