package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class ScannerCalculationCoreTest {
    @Test fun emaUsesSmaSeedAndStandardAlpha() {
        val result = ScannerCalculationCore.ema(listOf(1.0, 2.0, 3.0, 4.0), 3)
        assertEquals(3.0, result!!, 1e-9)
    }

    @Test fun simpleAndWilderRsiRemainExplicitlyDifferent() {
        val closes = listOf(10.0, 11.0, 10.0, 12.0, 11.0, 13.0, 12.0, 14.0,
            13.0, 15.0, 14.0, 16.0, 15.0, 17.0, 16.0, 18.0)
        val simple = ScannerCalculationCore.rsiSimple(closes, 14)
        val wilder = ScannerCalculationCore.rsiWilder(closes, 14)
        assertNotNull(simple)
        assertNotNull(wilder)
        assertNotEquals(simple!!, wilder!!)
    }

    @Test fun adxAndDirectionalIndicatorsIdentifyStrongUptrend() {
        val closes = (0 until 40).map { 100.0 + it }
        val highs = closes.map { it + 1.0 }
        val lows = closes.map { it - 1.0 }
        val strength = ScannerCalculationCore.directionalStrength(highs, lows, closes)
        assertNotNull(strength)
        assertTrue(strength!!.adx > 25.0)
        assertTrue(strength.plusDi > strength.minusDi)
    }

    @Test fun adxAndDirectionalIndicatorsIdentifyStrongDowntrend() {
        val closes = (0 until 40).map { 140.0 - it }
        val highs = closes.map { it + 1.0 }
        val lows = closes.map { it - 1.0 }
        val strength = ScannerCalculationCore.directionalStrength(highs, lows, closes)
        assertNotNull(strength)
        assertTrue(strength!!.adx > 25.0)
        assertTrue(strength.minusDi > strength.plusDi)
    }

    @Test fun adxRejectsInsufficientOrInvalidCandleData() {
        assertNull(ScannerCalculationCore.directionalStrength(
            listOf(2.0, 3.0), listOf(1.0, 2.0), listOf(1.5, 2.5)
        ))
        assertNull(ScannerCalculationCore.directionalStrength(
            listOf(2.0, Double.NaN), listOf(1.0, 2.0), listOf(1.5, 2.5)
        ))
    }

    @Test fun atrUsesSimpleMeanOfLastTrueRanges() {
        val highs = (0..14).map { 12.0 }
        val lows = (0..14).map { 10.0 }
        val closes = (0..14).map { 11.0 }
        assertEquals(2.0, ScannerCalculationCore.atr(highs, lows, closes, 14)!!, 1e-9)
    }

    @Test fun priorVolumeAverageCanExcludeCurrentCandle() {
        assertEquals(2.0, ScannerCalculationCore.averageVolume(
            listOf(1.0, 2.0, 3.0, 100.0), 3, excludeLast = true
        )!!, 1e-9)
    }

    @Test fun pivotsAreReturnedOnlyAfterRightBars() {
        val highs = listOf(1.0, 2.0, 5.0, 3.0)
        val lows = listOf(0.0, 1.0, 2.0, 1.0)
        assertTrue(ScannerCalculationCore.confirmedPivots(highs, lows).isEmpty())
        val confirmed = ScannerCalculationCore.confirmedPivots(
            highs + 2.0, lows + 1.5
        )
        assertTrue(confirmed.any { it.index == 2 && it.isHigh && it.confirmedAtIndex == 4 })
    }
}
