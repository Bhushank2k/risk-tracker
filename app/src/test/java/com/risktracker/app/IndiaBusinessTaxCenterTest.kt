package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class IndiaBusinessTaxCenterTest {
    private fun epochUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun financialYearUsesIndiaLocalMidnight() {
        // 18:29 UTC on 31 March is still 31 March in India.
        assertEquals(2025, indiaFinancialYearStart(epochUtc(2026, 3, 31, 18, 29)))
        // 18:30 UTC is 00:00 IST on 1 April, the new financial year.
        assertEquals(2026, indiaFinancialYearStart(epochUtc(2026, 3, 31, 18, 30)))
    }

    @Test
    fun financialYearHandlesJanuaryAndAprilDates() {
        assertEquals(2025, indiaFinancialYearStart(epochUtc(2026, 1, 1, 0, 0)))
        assertEquals(2026, indiaFinancialYearStart(epochUtc(2026, 4, 1, 0, 0)))
    }
}
