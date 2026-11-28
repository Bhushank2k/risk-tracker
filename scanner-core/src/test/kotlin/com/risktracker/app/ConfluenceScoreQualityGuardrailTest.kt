package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfluenceScoreQualityGuardrailTest {
    @Test fun fullyAvailableAlignedContextDoesNotCapScore() {
        val result = applyConfluenceScoreQualityCaps(100, true, true, false, 1.2)
        assertEquals(100, result.score)
        assertTrue(result.appliedCaps.isEmpty())
    }

    @Test fun missingBtcContextCapsConfidenceAndExplainsWhy() {
        val result = applyConfluenceScoreQualityCaps(98, false, true, false, 1.2)
        assertEquals(82, result.score)
        assertTrue(result.appliedCaps.single().contains("BTC market context unavailable"))
    }

    @Test fun missingAdxAndElevatedExpansionApplyTightestCap() {
        val result = applyConfluenceScoreQualityCaps(95, true, false, false, 1.6)
        assertEquals(78, result.score)
        assertEquals(2, result.appliedCaps.size)
    }

    @Test fun btcHeadwindCapsScoreWithoutHardBlockingByItself() {
        val result = applyConfluenceScoreQualityCaps(99, true, true, true, 1.1)
        assertEquals(75, result.score)
        assertTrue(result.appliedCaps.any { it.contains("headwind") })
    }

    @Test fun extremeVolatilityExpansionCapsBelowReadyThreshold() {
        val result = applyConfluenceScoreQualityCaps(100, true, true, false, 1.81)
        assertEquals(60, result.score)
        assertTrue(result.appliedCaps.any { it.contains("beyond 1.8×") })
    }

    @Test fun unavailableAtrComparisonCapsAtWatchLevel() {
        val result = applyConfluenceScoreQualityCaps(90, true, true, false, Double.NaN)
        assertEquals(70, result.score)
        assertTrue(result.appliedCaps.single().contains("ATR comparison unavailable"))
    }

    @Test fun capDoesNotInflateLowerRawScore() {
        val result = applyConfluenceScoreQualityCaps(65, false, false, true, 2.0)
        assertEquals(60, result.score)
        assertFalse(result.appliedCaps.isEmpty())
    }
}
