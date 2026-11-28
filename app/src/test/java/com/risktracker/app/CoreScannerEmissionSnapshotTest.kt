package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreScannerEmissionSnapshotTest {
    private fun snapshot(side: String = "LONG") = CoreScannerEmissionSnapshot(
        snapshotId = "core-BTCUSDT-1", signalId = "BTCUSDT-1", symbol = "BTCUSDT",
        side = side, timeframe = "15m", score = 82, status = "WAIT",
        entryLow = 100.0, entryHigh = 102.0,
        stop = if (side == "LONG") 95.0 else 107.0,
        target = if (side == "LONG") 112.0 else 90.0,
        rewardRisk = 2.0, structure = "BOS", sweep = "sell-side",
        displacement = "bullish", zoneDescription = "order block", location = "discount",
        capturedAt = 1_700_000_000_000L
    )

    @Test fun validLongEmissionIsAcceptedAndPreservesSingleTargetShape() {
        val value = snapshot()
        assertTrue(value.isValid())
        assertEquals(112.0, value.target, 0.000001)
        assertEquals(null, value.adx)
        assertEquals(null, value.btcBias)
    }

    @Test fun validShortEmissionIsAccepted() {
        assertTrue(snapshot("SHORT").isValid())
    }

    @Test fun invalidDirectionalGeometryIsRejected() {
        assertFalse(snapshot().copy(stop = 103.0).isValid())
        assertFalse(snapshot("SHORT").copy(target = 110.0).isValid())
    }

    @Test fun missingCaptureIdentityAndInvalidScoreAreRejected() {
        assertFalse(snapshot().copy(snapshotId = "").isValid())
        assertFalse(snapshot().copy(score = 101).isValid())
    }
}
