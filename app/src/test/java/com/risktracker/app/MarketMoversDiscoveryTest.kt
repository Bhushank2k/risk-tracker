package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketMoversDiscoveryTest {
    @Test fun moderateAbsoluteMovesAreNotMislabelledAsConfirmedEntries() {
        assertEquals(MarketMoverPhase.NOTABLE_MOVE, MarketMoversDiscovery.phaseFor(1.8))
        assertEquals(MarketMoverPhase.NOTABLE_MOVE, MarketMoversDiscovery.phaseFor(-2.9))
    }

    @Test fun strongMovesAreClassifiedInBothDirections() {
        assertEquals(MarketMoverPhase.STRONG_MOVE, MarketMoversDiscovery.phaseFor(3.0))
        assertEquals(MarketMoverPhase.STRONG_MOVE, MarketMoversDiscovery.phaseFor(-7.99))
    }

    @Test fun extendedMovesAreFlaggedRatherThanChased() {
        assertEquals(MarketMoverPhase.EXTENDED_MOVE, MarketMoversDiscovery.phaseFor(8.0))
        assertEquals(MarketMoverPhase.EXTENDED_MOVE, MarketMoversDiscovery.phaseFor(-12.0))
    }
}
