package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TradeIdeaPresentationTest {
    @Test fun unknownSnapshotAgeIsExplicit() {
        assertEquals("Snapshot age unavailable", tradeIdeaSnapshotAgeLabel(Long.MAX_VALUE, 60_000L))
    }

    @Test fun recentSnapshotShowsAgeWithoutCallingItStale() {
        assertEquals("Snapshot · 4m old", tradeIdeaSnapshotAgeLabel(4 * 60_000L, 60 * 60_000L))
    }

    @Test fun oldSnapshotIsClearlyMarkedStale() {
        assertEquals("Stale snapshot · 1h 5m old", tradeIdeaSnapshotAgeLabel(65 * 60_000L, 60 * 60_000L))
    }

    @Test fun cardIdentityIncludesDirectionAndSetupAndPlanLevels() {
        val long = tradeIdeaTrackingKeyParts("BTCUSDT", "LONG", "Intraday", "Pullback", 100.0, 95.0)
        val short = tradeIdeaTrackingKeyParts("BTCUSDT", "SHORT", "Intraday", "Pullback", 100.0, 105.0)
        val otherSetup = tradeIdeaTrackingKeyParts("BTCUSDT", "LONG", "Intraday", "Breakout", 100.0, 95.0)
        assertNotEquals(long, short)
        assertNotEquals(long, otherSetup)
    }
}