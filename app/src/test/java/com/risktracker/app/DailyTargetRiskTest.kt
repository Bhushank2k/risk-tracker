package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyTargetRiskTest {
    @Test fun computesTargetsFromEstimatedStartOfDayEquity() {
        val result = calculateDailyTargetRisk(1100.0, 100.0, 10.0, 5.0, 1_795_000_000_000L)
        assertEquals(1000.0, result.baselineEquity!!, 1e-8)
        assertEquals(100.0, result.targetAmount!!, 1e-8)
        assertEquals(50.0, result.maxLossAmount!!, 1e-8)
        assertEquals(1.0, result.targetProgress, 1e-8)
        assertEquals(DailyTargetRiskStatus.TARGET_REACHED, result.status)
    }

    @Test fun warnsAtSeventyFivePercentOfLossLimitAndStopsAtLimit() {
        val warning = calculateDailyTargetRisk(962.5, -37.5, 10.0, 5.0, 1_795_000_000_000L)
        assertEquals(DailyTargetRiskStatus.LOSS_WARNING, warning.status)
        val reached = calculateDailyTargetRisk(950.0, -50.0, 10.0, 5.0, 1_795_000_000_000L)
        assertEquals(DailyTargetRiskStatus.LOSS_LIMIT_REACHED, reached.status)
    }

    @Test fun missingPnlDoesNotPretendRiskIsVerified() {
        val result = calculateDailyTargetRisk(1000.0, null, 10.0, 5.0)
        assertEquals(DailyTargetRiskStatus.DATA_UNAVAILABLE, result.status)
        assertNull(result.dailyPnl)
        assertEquals(100.0, result.targetAmount!!, 1e-8)
    }

    @Test fun resetsByIndiaStandardTimeCalendarDate() {
        val beforeMidnight = LocalDate.of(2026, 11, 24).atTime(18, 29).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val afterMidnight = LocalDate.of(2026, 11, 24).atTime(18, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 24), istTradingDate(beforeMidnight))
        assertEquals(LocalDate.of(2026, 11, 25), istTradingDate(afterMidnight))
    }

    @Test fun rejectsInvalidPercentagesAndEquity() {
        assertEquals(DailyTargetRiskStatus.INVALID, calculateDailyTargetRisk(1000.0, 0.0, 0.0, 5.0).status)
        assertEquals(DailyTargetRiskStatus.INVALID, calculateDailyTargetRisk(-10.0, 0.0, 10.0, 5.0).status)
    }
}
