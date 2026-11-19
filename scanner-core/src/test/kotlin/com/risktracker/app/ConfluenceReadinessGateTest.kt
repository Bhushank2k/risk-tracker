package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceReadinessGateTest {
    private fun readiness(structure: Boolean) = isConfluenceReady(
        liquid = true, geometry = true, rewardRisk = true, funding = true,
        volatility = true, notExtended = true, volume = true, momentum = false,
        entryConfirmed = true, score = 85, structureConfirmed = structure
    )

    @Test fun unconfirmedStructureCannotBecomeReady() {
        assertFalse(readiness(structure = false))
    }

    @Test fun confirmedStructureCanBecomeReadyWithoutMomentumHardGate() {
        assertTrue(readiness(structure = true))
    }
}
