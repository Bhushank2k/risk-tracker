package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class ProfitProtectionEngineTest {
    @Test fun triggersLossLimitAndProfitTargetIndependently() {
        val loss = ProfitProtectionEngine.evaluate(-100.0, null, 0,
            ProfitProtectionSettings(dailyLossLimit = 100.0, profitTarget = 50.0))
        assertTrue(loss.any { it.kind == ProfitProtectionKind.DAILY_LOSS_LIMIT })
        val profit = ProfitProtectionEngine.evaluate(60.0, 60.0, 0,
            ProfitProtectionSettings(dailyLossLimit = 100.0, profitTarget = 50.0))
        assertTrue(profit.any { it.kind == ProfitProtectionKind.PROFIT_TARGET })
    }

    @Test fun givebackRequiresPositiveObservedPeakAndThreshold() {
        val alerts = ProfitProtectionEngine.evaluate(40.0, 100.0, 0,
            ProfitProtectionSettings(500.0, maximumGiveback = 50.0))
        assertTrue(alerts.any { it.kind == ProfitProtectionKind.GIVEBACK })
        val noPeak = ProfitProtectionEngine.evaluate(-10.0, -5.0, 0,
            ProfitProtectionSettings(500.0, maximumGiveback = 1.0))
        assertFalse(noPeak.any { it.kind == ProfitProtectionKind.GIVEBACK })
    }

    @Test fun triggersConfiguredLosingStreak() {
        val alerts = ProfitProtectionEngine.evaluate(5.0, 10.0, 3,
            ProfitProtectionSettings(100.0, maximumConsecutiveLosses = 3))
        assertTrue(alerts.any { it.kind == ProfitProtectionKind.LOSING_STREAK })
    }

    @Test fun unknownOrNonFinitePnlDoesNotTriggerDailyAlerts() {
        assertTrue(ProfitProtectionEngine.evaluate(null, 100.0, 10,
            ProfitProtectionSettings(10.0, 5.0, 5.0, 2)).isEmpty())
        assertTrue(ProfitProtectionEngine.evaluate(Double.NaN, 100.0, 10,
            ProfitProtectionSettings(10.0, 5.0, 5.0, 2)).isEmpty())
    }

    @Test fun invalidSettingsAreIgnored() {
        val alerts = ProfitProtectionEngine.evaluate(10.0, 10.0, 0,
            ProfitProtectionSettings(Double.NaN, Double.NaN, -1.0, 0))
        assertTrue(alerts.isEmpty())
    }
}
