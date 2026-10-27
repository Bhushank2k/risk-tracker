package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskAlertEngineTest {
    @Test fun dailyLossAlertRequiresKnownPnlAndThresholdCrossing() {
        assertEquals(0, evaluate(dailyPnl = null).size)
        assertEquals(0, evaluate(dailyPnl = -49.0).size)
        assertEquals("daily-loss", evaluate(dailyPnl = -50.0).single().key)
    }

    @Test fun drawdownAlertUsesConfiguredThreshold() {
        assertEquals(0, evaluate(drawdownPercent = 9.9).size)
        assertEquals("drawdown", evaluate(drawdownPercent = 10.0).single().key)
    }

    @Test fun staleSyncAlertRequiresConnectionAndEnabledRule() {
        assertEquals(0, evaluate(hasCredentials = false, lastSuccess = 0L, staleEnabled = true).size)
        assertEquals(0, evaluate(lastSuccess = 0L, staleEnabled = false).size)
        assertEquals("stale-sync", evaluate(lastSuccess = 0L, staleEnabled = true).single().key)
    }

    @Test fun consecutiveLossAlertUsesConfiguredThreshold() {
        assertEquals(0, evaluate(consecutiveLosses = 2, streakEnabled = true).size)
        assertEquals("loss-streak", evaluate(consecutiveLosses = 3, streakEnabled = true).single().key)
    }

    @Test fun futureSyncTimestampDoesNotBecomeNegativeAge() {
        val result = RiskAlertEngine.evaluate(null, 50.0, false, null, 10.0, false,
            true, 2_000L, 1_000L, 60_000L, true)
        assertEquals(0, result.size)
    }

    private fun evaluate(dailyPnl: Double? = null, drawdownPercent: Double? = null,
        hasCredentials: Boolean = true, lastSuccess: Long = 99_000L, staleEnabled: Boolean = false,
        consecutiveLosses: Int = 0, streakEnabled: Boolean = false) =
        RiskAlertEngine.evaluate(dailyPnl, 50.0, true, drawdownPercent, 10.0, true,
            hasCredentials, lastSuccess, 100_000L, 1_000L, staleEnabled,
            consecutiveLosses, 3, streakEnabled)
}
