package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerSignalSnapshotTest {
    private fun signal() = AdvancedSMCSignal(
        id = "BTCUSDT_BULLISH_IFVG_15m",
        symbol = "BTCUSDT",
        side = "LONG",
        signalType = "BULLISH IFVG",
        horizon = "15m",
        score = 88,
        status = "WAIT FOR RETEST",
        entryLow = 100.0,
        entryHigh = 102.0,
        stop = 95.0,
        target1 = 112.0,
        target2 = 120.0,
        rewardRisk = 2.0,
        explanation = "Gap reclaimed",
        confirmation = "Wait for retest",
        detectedAt = 1_000L
    )

    @Test fun snapshotPreservesEmissionValuesAndUsesEntryZoneMidpointForReplay() {
        val snapshot = ScannerSignalSnapshot.from(signal(), capturedAt = 1_100L)
        assertTrue(snapshot.isValid())
        assertEquals(101.0, snapshot.entry, 0.000001)
        assertEquals(1_000L, snapshot.signalTime)
        assertEquals(1_100L, snapshot.capturedAt)
        val replay = snapshot.toReplaySignal()
        assertEquals(snapshot.snapshotId, replay.id)
        assertEquals(101.0, replay.entry, 0.000001)
        assertFalse(replay.entryWasTriggered)
    }

    @Test fun repeatedEmissionForSameSourceCandleGetsStableSnapshotId() {
        val first = ScannerSignalSnapshot.from(signal(), capturedAt = 1_100L)
        val second = ScannerSignalSnapshot.from(signal(), capturedAt = 1_200L)
        assertEquals(first.snapshotId, second.snapshotId)
    }

    @Test fun invalidPriceGeometryIsRejected() {
        val snapshot = ScannerSignalSnapshot.from(signal().copy(stop = 103.0), capturedAt = 1_100L)
        assertFalse(snapshot.isValid())
    }

    @Test fun optionalUnavailableContextStaysNull() {
        val snapshot = ScannerSignalSnapshot.from(signal(), capturedAt = 1_100L)
        assertEquals(null, snapshot.adx)
        assertEquals(null, snapshot.btcBias)
        assertEquals(null, snapshot.fundingRate)
    }
}
