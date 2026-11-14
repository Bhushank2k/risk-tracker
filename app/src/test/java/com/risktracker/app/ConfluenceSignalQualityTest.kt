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
    @Test fun extensionIsMeasuredFromActualExecutionPrice() {
        assertTrue(confluenceEntryWithinExtension(100.8, 100.0, 1.0))
        assertFalse(confluenceEntryWithinExtension(101.1, 100.0, 1.0))
        assertFalse(confluenceEntryWithinExtension(Double.NaN, 100.0, 1.0))
        assertFalse(confluenceEntryWithinExtension(100.0, 100.0, 0.0))
    }

    @Test fun unavailableVolumeDoesNotEarnVolumePoints() {
        assertEquals(90, confluenceScore(true, true, true, Double.NaN, true, true, false, true))
    }
    @Test fun pullbackRequiresQuietVolumeThenRenewedParticipation() {
        assertTrue(confluencePullbackVolumeConfirmed(70.0, 120.0, 100.0))
        assertFalse(confluencePullbackVolumeConfirmed(110.0, 120.0, 100.0))
        assertTrue(confluencePullbackVolumeConfirmed(70.0, 90.0, 100.0))
        assertFalse(confluencePullbackVolumeConfirmed(Double.NaN, 120.0, 100.0))
    }

    @Test fun volatilityExpansionRejectsSuddenSpikesAndInvalidInputs() {
        assertTrue(confluenceVolatilityExpansionAcceptable(1.5, 1.0))
        assertFalse(confluenceVolatilityExpansionAcceptable(1.81, 1.0))
        assertFalse(confluenceVolatilityExpansionAcceptable(Double.NaN, 1.0))
        assertFalse(confluenceVolatilityExpansionAcceptable(1.0, 0.0))
    }

    @Test fun candleMustBeFullyClosedBeforeSignalEvaluation() {
        assertTrue(isTradeIdeaCandleClosed(99L, 100L))
        assertFalse(isTradeIdeaCandleClosed(100L, 100L))
        assertFalse(isTradeIdeaCandleClosed(101L, 100L))
        assertFalse(isTradeIdeaCandleClosed(0L, 100L))
    }

    @Test fun executionVolumeThresholdMatchesSetupFamily() {
        assertEquals(1.2, confluenceExecutionVolumeThreshold("Breakout + retest"), 0.0)
        assertEquals(1.2, confluenceExecutionVolumeThreshold("breakout + retest"), 0.0)
        assertEquals(0.9, confluenceExecutionVolumeThreshold("Trend pullback"), 0.0)
        assertEquals(0.9, confluenceExecutionVolumeThreshold("Range reversal"), 0.0)
    }

}
