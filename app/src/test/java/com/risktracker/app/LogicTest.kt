package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val eps = 1e-6

    // Defaults: balance 50, risk 2%, entry 100, SL 98, target 105, 10x, MMR 0.5%
    private val base = CalcInput()

    @Test
    fun longSizingMatchesHandCalculation() {
        val r = calcSide(base, long = true)
        assertTrue(r.valid)
        assertEquals(1.0, r.riskAmount, eps)          // 2% of 50
        assertEquals(2.0, r.slDistancePercent, eps)
        assertEquals(50.0, r.positionSize, eps)       // risk / 2%
        assertEquals(0.5, r.tokens, eps)
        assertEquals(5.0, r.margin, eps)              // size / 10x
        assertEquals(90.0, r.liqSimple, eps)
        assertEquals(90.5, r.liqExact, eps)           // with 0.5% maintenance margin
        assertEquals(2.5, r.profit, eps)              // 5% target on size 50
        assertEquals(50.0, r.roiPercent, eps)
        assertEquals(2.5, r.rr, eps)
    }

    @Test
    fun shortIsInvalidWhenStopIsBelowEntry() {
        val r = calcSide(base, long = false)
        assertFalse(r.valid)
        assertNotNull(r.message)
    }

    @Test
    fun validShortHasMirroredLiquidation() {
        val r = calcSide(base.copy(stopLoss = 102.0, target = 95.0), long = false)
        assertTrue(r.valid)
        assertEquals(110.0, r.liqSimple, eps)
        assertEquals(109.5, r.liqExact, eps)
    }

    @Test
    fun stopBeyondLiquidationIsRejected() {
        // 50x leverage: long liquidation is near 98.5, so a stop at 97 can never trigger first.
        val r = calcSide(base.copy(stopLoss = 97.0, leverage = 50.0), long = true)
        assertFalse(r.valid)
    }

    @Test
    fun zeroInputsDoNotCrashOrProduceNaN() {
        val r = calcSide(CalcInput(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), long = true)
        assertFalse(r.valid)
        assertFalse(r.positionSize.isNaN())
        assertFalse(r.roiPercent.isNaN())
        assertFalse(r.slDistancePercent.isNaN())
        assertFalse(r.tpPercent.isNaN())
        assertFalse(r.profit.isNaN())
    }

    @Test
    fun leverageMatrixLiquidationBoundaries() {
        val row10 = leverageMatrix(100.0).first { it.lev == 10 }
        assertEquals(10.0, row10.movePercent, eps)
        assertEquals(90.0, row10.longLiq, eps)
        assertEquals(110.0, row10.shortLiq, eps)
    }

    @Test
    fun statsCountWinsLossesAndBalance() {
        val s = computeStats(
            listOf(
                PnlEntry("1/1/2026", 100.0, 10.0),
                PnlEntry("2/1/2026", 110.0, -5.0),
                PnlEntry("3/1/2026", 105.0, 0.0)
            )
        )
        assertEquals(10.0, s.totalWin, eps)
        assertEquals(-5.0, s.totalLoss, eps)
        assertEquals(5.0, s.net, eps)
        assertEquals(1, s.winDays)
        assertEquals(1, s.lossDays)
        assertEquals(50.0, s.winRate, eps)            // flat days are not counted as traded
        assertEquals(105.0, s.currentBalance, eps)
    }

    @Test
    fun emptyHistoryIsSafe() {
        val s = computeStats(emptyList())
        assertEquals(0.0, s.currentBalance, eps)
        assertEquals(0.0, s.winRate, eps)
    }
    @Test
    fun longAndShortTradePnlSubtractsFees() {
        val long = TradeEntry("1", "2026-01-01", "BTCUSDT", "Long", 100.0, 110.0, 2.0, 1.0)
        val short = TradeEntry("2", "2026-01-02", "ETHUSDT", "Short", 100.0, 90.0, 3.0, 2.0)
        assertEquals(19.0, long.pnl, eps)
        assertEquals(28.0, short.pnl, eps)
    }

    @Test
    fun tradeStatsComputeWinRateProfitFactorAndDrawdown() {
        val trades = listOf(
            TradeEntry("1", "2026-01-01", "BTC", "Long", 100.0, 110.0, 1.0),
            TradeEntry("2", "2026-01-02", "BTC", "Long", 100.0, 95.0, 1.0),
            TradeEntry("3", "2026-01-03", "BTC", "Short", 100.0, 100.0, 1.0)
        )
        val s = computeTradeStats(trades)
        assertEquals(3, s.count)
        assertEquals(1, s.wins)
        assertEquals(1, s.losses)
        assertEquals(5.0, s.netPnl, eps)
        assertEquals(50.0, s.winRate, eps)
        assertEquals(2.0, s.profitFactor, eps)
        assertEquals(5.0, s.maxDrawdown, eps)
    }

    @Test
    fun emptyTradeJournalHasSafeStats() {
        val s = computeTradeStats(emptyList())
        assertEquals(0, s.count)
        assertEquals(0.0, s.winRate, eps)
        assertEquals(0.0, s.netPnl, eps)
        assertEquals(0.0, s.maxDrawdown, eps)
    }

}
