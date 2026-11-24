package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class DailyPnlForecastEngineTest {
    private fun row(date: String, pnl: Double) = ReconciledDailyPnl(
        date, null, pnl, null, pnl, DailyPnlSource.BINANCE, null
    )

    @Test fun excludesAsOfDateAndFutureRows() {
        val result = DailyPnlForecastEngine.forecast(
            listOf(row("2026-01-01", 10.0), row("2026-01-02", -5.0),
                row("2026-01-03", 999.0), row("2026-01-04", 500.0)),
            asOfDate = "2026-01-03"
        )
        assertEquals(2, result.sampleCount)
        assertEquals(2.5, result.expectedPnl!!, 1e-8)
    }

    @Test fun reportsEmpiricalOutcomeFrequenciesAndPercentiles() {
        val rows = (1..10).map { day ->
            row("2026-01-%02d".format(day), (day - 5).toDouble())
        }
        val result = DailyPnlForecastEngine.forecast(rows, "2026-02-01")
        assertEquals(10, result.sampleCount)
        assertEquals(1.5, result.expectedPnl!!, 1e-8)
        assertEquals(40.0, result.profitableDayPercent!!, 1e-8)
        assertEquals(50.0, result.losingDayPercent!!, 1e-8)
        assertEquals(ForecastReliability.LIMITED, result.reliability)
        assertTrue(result.lowerOutcome!! <= result.upperOutcome!!)
    }

    @Test fun missingHistoryDoesNotBecomeZeroProfitForecast() {
        val result = DailyPnlForecastEngine.forecast(emptyList(), "2026-02-01")
        assertFalse(result.hasEstimate)
        assertNull(result.expectedPnl)
        assertNull(result.profitableDayPercent)
        assertEquals(ForecastReliability.INSUFFICIENT, result.reliability)
    }

    @Test fun ignoresMalformedAndNonFiniteObservations() {
        val result = DailyPnlForecastEngine.forecast(
            listOf(row("bad-date", 5.0), row("2026-01-01", Double.NaN)),
            "2026-02-01"
        )
        assertEquals(0, result.sampleCount)
    }
}
