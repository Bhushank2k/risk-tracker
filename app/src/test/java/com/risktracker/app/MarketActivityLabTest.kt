package com.risktracker.app

import org.junit.Assert.*
import org.junit.Test

class MarketActivityLabTest {
    private fun candles(n:Int=260, breakout:Boolean=false):List<MarketCandle>{
        val out=mutableListOf<MarketCandle>()
        repeat(n){i->
            val base=100.0+(i%12)*0.025
            val close=if(breakout&&i==n-1)base+4 else base+0.01
            val open=if(breakout&&i==n-1)base else close-0.005
            val high=maxOf(open,close)+0.02
            val low=minOf(open,close)-0.02
            val volume=if(breakout&&i==n-1)1000.0 else 100.0
            out+=MarketCandle(i*3_600_000L,open,high,low,close,volume,(i+1)*3_600_000L-1,volume*close)
        }
        return out
    }
    @Test fun indicatorsStayFiniteAndExposeExpectedPeriods(){
        val result=MarketActivityEngine.indicators(candles())
        assertNotNull(result.ema9);assertNotNull(result.ema20);assertNotNull(result.ema50);assertNotNull(result.ema200)
        assertTrue(result.rsi14!!.isFinite());assertTrue(result.atr14!!.isFinite())
        assertTrue(result.relativeVolume!!.isFinite())
    }
    @Test fun insufficientHistoryIsNotScored(){
        assertNull(MarketActivityEngine.analyze("BTCUSDT",MarketInterval.M15,candles(20)))
    }
    @Test fun breakoutNeedsClosedRangeAndVolumeSupport(){
        val result=MarketActivityEngine.analyze("BTCUSDT",MarketInterval.M15,candles(breakout=true))!!
        assertEquals(ActivityState.CONFIRMED_HISTORICAL_BREAKOUT,result.state)
        assertEquals(ActivityDirection.BULLISH,result.direction)
        assertTrue(result.score.total in 0..100)
    }
    @Test fun replayReportsEmptySampleWithoutInventingWinRate(){
        val result=MarketActivityEngine.replay(candles(40),horizon=5)
        assertEquals(0,result.setups)
        assertNull(result.falsePositiveRate)
        assertNull(result.averageReturnPercent)
    }
    @Test fun invalidReplayParametersAreRejected(){
        try { MarketActivityEngine.replay(candles(),horizon=0);fail("Expected invalid horizon") }
        catch(_:IllegalArgumentException) { }
    }
    @Test fun configuredVolumeThresholdChangesBreakoutClassification(){
        val result=MarketActivityEngine.analyze(
            "BTCUSDT",MarketInterval.M15,candles(breakout=true),
            ActivityThresholds(relativeVolumeBreakout=20.0)
        )!!
        assertNotEquals(ActivityState.CONFIRMED_HISTORICAL_BREAKOUT,result.state)
    }
    @Test fun symbolParserNormalizesAndRejectsDuplicates(){
        assertEquals(listOf("BTCUSDT","ETHUSDT"),MarketActivityPreferences.parseSymbols(" btcusdt, ethusdt "))
        try {
            MarketActivityPreferences.parseSymbols("BTCUSDT,BTCUSDT")
            fail("Expected duplicate symbols to be rejected")
        } catch(_:IllegalArgumentException) { }
    }

}
