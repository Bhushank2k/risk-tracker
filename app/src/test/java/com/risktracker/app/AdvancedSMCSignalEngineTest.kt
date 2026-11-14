package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSMCSignalEngineTest {
    @Test
    fun insufficientHistoryProducesNoSignals() {
        val candles = List(20) { i ->
            AdvancedSMCCandle(100.0, 102.0, 98.0, 101.0, 10.0, 1_800_000L * i)
        }
        assertTrue(AdvancedSMCSignalEngine.evaluate("ETHUSDT", 101.0, candles, candles, candles, "Intraday").isEmpty())
    }

    @Test
    fun smtSignalIncludesAValidGrossTwoRPlan() {
        val start = 2L * 86_400_000L + 10L * 3_600_000L
        val asset = MutableList(50) { i ->
            AdvancedSMCCandle(110.0, 120.0, 100.0, 110.0, 10.0, start + i * 3_600_000L)
        }
        for (i in 44..48) asset[i] = AdvancedSMCCandle(110.0, 120.0, 101.0, 110.0, 10.0, start + i * 3_600_000L)
        asset[48] = AdvancedSMCCandle(99.0, 103.0, 98.0, 102.0, 12.0, start + 48 * 3_600_000L)
        val benchmark = MutableList(50) { i ->
            AdvancedSMCCandle(110.0, 120.0, 100.0, 110.0, 10.0, start + i * 3_600_000L)
        }
        for (i in 44..48) benchmark[i] = AdvancedSMCCandle(110.0, 120.0, 101.0, 111.0, 10.0, start + i * 3_600_000L)
        val execution = List(25) { i ->
            AdvancedSMCCandle(109.0, 111.0, 108.0, 110.0, 5.0, start + i * 300_000L)
        }

        val result = AdvancedSMCSignalEngine.evaluate("ETHUSDT", 102.0, asset, execution, benchmark, "Intraday")
        val smt = result.firstOrNull { it.signalType == "BULLISH SMT" }

        assertTrue("Expected a bullish SMT idea", smt != null)
        assertEquals("LONG", smt!!.side)
        assertTrue("Gross reward/risk must be at least 2R", smt.rewardRisk >= 2.0)
        assertTrue(smt.target1 > (smt.entryLow + smt.entryHigh) / 2.0)
        assertTrue(smt.stop < (smt.entryLow + smt.entryHigh) / 2.0)
    }
}
