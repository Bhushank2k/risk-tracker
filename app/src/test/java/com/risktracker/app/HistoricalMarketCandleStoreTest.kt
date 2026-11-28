package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalMarketCandleStoreTest {
    private fun candle(openTime: Long, close: Double = 101.0) = HistoricalMarketCandle(
        symbol = "BTCUSDT", interval = "15m", openTime = openTime, closeTime = openTime + 899_999L,
        open = 100.0, high = maxOf(102.0, close), low = 99.0, close = close, volume = 12.0
    )

    @Test fun validatesCompletedOhlcvGeometry() {
        assertTrue(candle(1_700_000_000_000L).isValid())
        assertFalse(candle(1_700_000_000_000L).copy(high = 98.0).isValid())
        assertFalse(candle(1_700_000_000_000L).copy(volume = -1.0).isValid())
        assertFalse(candle(1_700_000_000_000L).copy(interval = "2m").isValid())
    }

    @Test fun mergeDeduplicatesByOpenTimeAndIncomingCorrectsExistingRow() {
        val start = 1_700_000_000_000L
        val merged = HistoricalMarketCandle.merge(
            listOf(candle(start), candle(start + 900_000L)),
            listOf(candle(start, close = 101.5), candle(start + 1_800_000L))
        )
        assertEquals(3, merged.size)
        assertEquals(101.5, merged.first().close, 0.000001)
        assertEquals(listOf(start, start + 900_000L, start + 1_800_000L), merged.map { it.openTime })
    }

    @Test fun mergeRetainsOnlyNewestConfiguredWindow() {
        val rows = (1L..(HistoricalMarketCandle.MAX_CANDLES_PER_SERIES + 20L)).map { candle(it * 900_000L) }
        val merged = HistoricalMarketCandle.merge(emptyList(), rows)
        assertEquals(HistoricalMarketCandle.MAX_CANDLES_PER_SERIES, merged.size)
        assertEquals(21L * 900_000L, merged.first().openTime)
    }
}
