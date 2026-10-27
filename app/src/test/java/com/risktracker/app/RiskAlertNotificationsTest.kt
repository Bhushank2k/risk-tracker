package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskAlertNotificationsTest {
    @Test
    fun newlyTriggeredAlertsOnlyIncludesNewlyActiveKeys() {
        assertEquals(
            setOf("drawdown"),
            newlyTriggeredAlertKeys(setOf("daily-loss", "stale-sync"), setOf("daily-loss", "drawdown"))
        )
    }

    @Test
    fun resolvedAlertCanTriggerAgainLater() {
        val firstActive = setOf("daily-loss")
        val resolved = emptySet<String>()
        val triggeredAgain = newlyTriggeredAlertKeys(resolved, firstActive)
        assertEquals(setOf("daily-loss"), triggeredAgain)
    }

    @Test
    fun noNewNotificationForUnchangedActiveAlerts() {
        val active = setOf("daily-loss", "loss-streak")
        assertEquals(emptySet<String>(), newlyTriggeredAlertKeys(active, active))
    }
}
