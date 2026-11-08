package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceSignalQualityTest {
    @Test fun rangeLongAcceptsEarlyMacdTurnBeforeZeroCross() {
        assertTrue(confluenceMomentumConfirmed("LONG", true, 40.0, -0.35, -0.50))
    }
    @Test fun rangeShortAcceptsEarlyMacdTurnBeforeZeroCross() {
        assertTrue(confluenceMomentumConfirmed("SHORT", true, 60.0, 0.35, 0.50))
    }
    @Test fun rangeReversalRejectsMacdMovingAgainstTurn() {
        assertFalse(confluenceMomentumConfirmed("LONG", true, 40.0, -0.55, -0.50))
        assertFalse(confluenceMomentumConfirmed("SHORT", true, 60.0, 0.55, 0.50))
    }
    @Test fun trendContinuationStillRequiresDirectionalMacdAndRsi() {
        assertTrue(confluenceMomentumConfirmed("LONG", false, 55.0, 0.4, 0.3))
        assertTrue(confluenceMomentumConfirmed("SHORT", false, 45.0, -0.4, -0.3))
        assertFalse(confluenceMomentumConfirmed("LONG", false, 55.0, -0.1, -0.2))
        assertFalse(confluenceMomentumConfirmed("SHORT", false, 45.0, 0.1, 0.2))
    }
    @Test fun invalidMomentumInputsFailClosed() {
        assertFalse(confluenceMomentumConfirmed("LONG", true, Double.NaN, 0.1, 0.0))
        assertFalse(confluenceMomentumConfirmed("LONG", true, 40.0, Double.POSITIVE_INFINITY, 0.0))
        assertFalse(confluenceMomentumConfirmed("OTHER", false, 50.0, 0.1, 0.0))
    }
    @Test fun fullConfluenceScoreTotalsOneHundred() {
        assertEquals(100, confluenceScore(true, true, true, 1.5, true, true, false, true))
    }
    @Test fun scoreReflectsPartialVolumeAndBtcHeadwind() {
        assertEquals(79, confluenceScore(true, true, false, 1.3, true, true, true, true))
    }
    @Test fun unavailableVolumeDoesNotEarnVolumePoints() {
        assertEquals(90, confluenceScore(true, true, true, Double.NaN, true, true, false, true))
    }
}
