package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceV4PlanSnapshotTest {
    private fun idea(side: String = "LONG") = TradeIdea(
        symbol = "BTCUSDT", side = side, score = 84, volume = 1_000_000.0, change = 1.2,
        last = 102.0, entry = 100.0,
        stop = if (side == "LONG") 95.0 else 105.0,
        target1 = if (side == "LONG") 112.0 else 88.0,
        target2 = if (side == "LONG") 118.0 else 82.0,
        entryTriggered = true, rsi = 55.0, volumeRatio = 1.7,
        bias4h = "Bullish", bias1h = "Bullish", setup = "Trend pullback",
        reasons = listOf("closed candle confirmation"), warning = "BALANCED",
        horizon = "Intraday", bias5m = "Bullish", spreadBps = 2.0,
        bookImbalance = 0.2, fundingRate = 0.0001, oiChangePct = 1.5,
        backtest = TradeIdeaBacktestStats(), bidDepthUsd = 20_000.0, askDepthUsd = 18_000.0
    )

    @Test fun capturesPlanAndAvailablePointInTimeInputsWithoutInventingUnavailableFields() {
        val snapshot = ConfluenceV4PlanSnapshot.from(idea(), 1_700_000_000_000L)
        assertTrue(snapshot.isValid())
        assertEquals(1.7, snapshot.relativeVolume!!, 0.000001)
        assertEquals(0.0001, snapshot.fundingRate!!, 0.000001)
        assertEquals(2.0, snapshot.spreadBps!!, 0.000001)
        assertEquals("READY", snapshot.status)
    }

    @Test fun recordsBlockedPlanAndPreservesUnavailableContextAsAbsent() {
        val snapshot = ConfluenceV4PlanSnapshot.from(idea().copy(
            entryTriggered = false, warning = "BLOCK: funding unavailable",
            fundingRate = null, spreadBps = null
        ), 1_700_000_000_000L)
        assertEquals("BLOCKED", snapshot.status)
        assertNull(snapshot.fundingRate)
        assertNull(snapshot.spreadBps)
    }

    @Test fun validatesLongAndShortGeometry() {
        assertTrue(ConfluenceV4PlanSnapshot.from(idea(), 1_700_000_000_000L).isValid())
        assertTrue(ConfluenceV4PlanSnapshot.from(idea("SHORT"), 1_700_000_000_000L).isValid())
        assertFalse(ConfluenceV4PlanSnapshot.from(idea().copy(stop = 101.0), 1_700_000_000_000L).isValid())
    }

    @Test fun repeatedScanAtSameTimestampHasStableIdentity() {
        val first = ConfluenceV4PlanSnapshot.from(idea(), 1_700_000_000_000L)
        val second = ConfluenceV4PlanSnapshot.from(idea(), 1_700_000_000_000L)
        assertEquals(first.snapshotId, second.snapshotId)
    }
}
