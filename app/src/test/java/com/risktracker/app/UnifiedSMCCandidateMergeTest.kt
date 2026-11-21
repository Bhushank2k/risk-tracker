package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSMCCandidateMergeTest {
    private fun candidate(
        id: String, source: String, low: Double, high: Double,
        symbol: String = "ETHUSDT", score: Int = 70, pattern: String = source
    ) = UnifiedSMCCandidate(
        id, symbol, "LONG", "Intraday", score, low, high,
        stop = low - 2.0, target = high + 4.0, rewardRisk = 2.0,
        source = source, pattern = pattern, status = "WAIT FOR CONFIRMATION",
        explanation = "$source explanation", detectedAt = 1_800_000L
    )

    @Test fun advancedOnlyPatternsNeverClaimCoreLevelsOrCollapse() {
        val rows = mergeUnifiedSMCCandidates(
            emptyList(),
            listOf(candidate("a", "Advanced SMC", 100.0, 102.0), candidate("b", "Advanced SMC", 100.5, 102.5))
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.levelSource == "Advanced SMC levels" })
        assertTrue(rows.all { it.sources == setOf("Advanced SMC") })
    }

    @Test fun distinctCorePlansForSameMarketRemainSeparate() {
        val rows = mergeUnifiedSMCCandidates(
            listOf(candidate("core-a", "Core SMC", 100.0, 102.0), candidate("core-b", "Core SMC", 110.0, 112.0)),
            emptyList()
        )
        assertEquals(2, rows.size)
        assertEquals(setOf(100.0, 110.0), rows.map { it.entryLow }.toSet())
    }

    @Test fun overlappingAdvancedSetupConfirmsCoreWithoutReplacingItsLevels() {
        val core = candidate("core-a", "Core SMC", 100.0, 102.0, score = 75)
        val advanced = candidate("adv-a", "Advanced SMC", 100.5, 101.5, score = 82, pattern = "BULLISH IFVG")
        val rows = mergeUnifiedSMCCandidates(listOf(core), listOf(advanced))
        assertEquals(1, rows.size)
        assertEquals(setOf("Core SMC", "Advanced SMC"), rows.single().sources)
        assertEquals(100.0, rows.single().entryLow, 0.0)
        assertEquals(102.0, rows.single().entryHigh, 0.0)
        assertEquals("Core SMC levels (Advanced is confirmation only)", rows.single().levelSource)
        assertEquals(82, rows.single().score)
    }

    @Test fun nonOverlappingAdvancedPlanStaysIndependent() {
        val rows = mergeUnifiedSMCCandidates(
            listOf(candidate("core-a", "Core SMC", 100.0, 102.0)),
            listOf(candidate("adv-a", "Advanced SMC", 104.0, 106.0))
        )
        assertEquals(2, rows.size)
        assertFalse(rows.any { it.sources.size > 1 })
    }
}
