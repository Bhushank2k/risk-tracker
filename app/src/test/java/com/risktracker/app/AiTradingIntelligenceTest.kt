package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AiTradingIntelligenceTest {
    @Test fun contextContainsOnlySanitizedJournalFields() {
        val trade = TradeEntry(
            id = "private-id-123", date = "2026-11-03", symbol = "BTCUSDT", side = "Long",
            entry = 100.0, exit = 110.0, quantity = 1.0, fees = 1.0,
            notes = "Followed setup", strategy = "Breakout", mistake = "",
            riskUsd = 5.0, screenshotUri = "content://private/screenshot"
        )
        val context = buildAiTradingContext(
            listOf(trade), emptyMap(), AiReportKind.JOURNAL,
            nowMillis = 1794000000000L
        )
        val serialized = context.toString()
        assertTrue(serialized.contains("BTCUSDT"))
        assertTrue(serialized.contains("estimated_net_pnl"))
        assertFalse(serialized.contains("private-id-123"))
        assertFalse(serialized.contains("content://private"))
        assertFalse(serialized.contains("screenshotUri"))
    }

    @Test fun reviewAdherenceIsIncludedWithoutReviewIdentity() {
        val trade = TradeEntry("t1", "2026-11-03", "ETHUSDT", "Short", 10.0, 9.0, 2.0)
        val review = TradeReview("t1", true, "Pullback", "Calm", "Waited for confirmation", "2026-11-03")
        val context = buildAiTradingContext(listOf(trade), mapOf("t1" to review),
            AiReportKind.DISCIPLINE, nowMillis = 1794000000000L)
        val row = context.getJSONArray("trades").getJSONObject(0)
        assertEquals(true, row.getBoolean("followed_plan"))
        assertEquals("Pullback", row.getString("setup_tag"))
        assertFalse(context.toString().contains("\"tradeId\""))
    }

    @Test fun weeklyContextUsesOnlyTradesFromCurrentWeek() {
        val monday = TradeEntry("m", "2026-11-02", "BTCUSDT", "Long", 10.0, 11.0, 1.0)
        val prior = TradeEntry("p", "2026-10-30", "ETHUSDT", "Long", 10.0, 11.0, 1.0)
        val context = buildAiTradingContext(listOf(prior, monday), emptyMap(),
            AiReportKind.WEEKLY, nowMillis = 1794000000000L)
        val rows = context.getJSONArray("trades")
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getString("symbol") == "BTCUSDT" })
    }
}
