package com.risktracker.app
import org.junit.Assert.*
import org.junit.Test
class ConfirmedReversalEngineTest {
 private fun c(i:Int,o:Double,h:Double,l:Double,cl:Double,v:Double=100.0)=ReversalCandle(i*900000L,i*900000L+899999L,o,h,l,cl,v)
 private fun bullishReversalSample():List<ReversalCandle> = buildList {
  for(i in 0 until 40){val close=120.0-i*.8;val open=close+.2;add(c(i,open,open+.5,close-.5,close))}
  add(c(40,88.8,89.0,85.0,87.0,150.0))
  add(c(41,87.0,88.8,85.5,88.2))
  add(c(42,88.2,89.5,86.5,89.0))
  add(c(43,89.0,91.0,88.0,90.0))
  add(c(44,90.0,91.0,89.0,90.5))
  add(c(45,90.5,102.0,90.2,101.0))
  for(i in 46 until 60)add(c(i,101.0,101.5,100.5,101.1))
 }
 private fun bearishReversalSample():List<ReversalCandle> = buildList {
  for(i in 0 until 40){val close=80.0+i*.8;val open=close-.2;add(c(i,open,open+.5,close-.5,close))}
  add(c(40,111.2,115.0,110.5,113.0,150.0))
  add(c(41,113.0,114.5,111.0,111.8))
  add(c(42,111.8,114.0,110.0,111.0))
  add(c(43,111.0,112.0,108.5,109.0))
  add(c(44,109.0,110.0,108.0,108.5))
  add(c(45,108.5,109.0,95.0,96.0))
  for(i in 46 until 60)add(c(i,96.0,96.5,95.5,96.1))
 }
 @Test fun pivotWaitsForEveryRightHandClose(){
  val x=listOf(c(0,10.0,11.0,9.0,10.0),c(1,10.0,10.5,8.0,9.0),c(2,9.0,9.5,6.0,8.0),c(3,8.0,9.0,7.0,8.5),c(4,8.5,10.0,8.0,9.5),c(5,9.5,10.5,9.0,10.0),c(6,10.0,11.0,9.5,10.5))
  assertFalse(isConfirmedPivotLow(x,2,2,3,4));assertTrue(isConfirmedPivotLow(x,2,2,3,5));assertTrue(isConfirmedPivotLow(x,2,2,3,6))
 }
 @Test fun malformedAndOutOfOrderRowsAreRejected(){
  val x=listOf(c(0,10.0,11.0,9.0,10.0),c(1,10.0,11.0,9.0,10.0));assertTrue(validReversalCandles(x));assertFalse(validReversalCandles(x.reversed()));assertFalse(validReversalCandles(listOf(c(0,10.0,8.0,9.0,10.0),x[1])))
 }
 @Test fun stopWinsIfStopAndTargetHitSameBar(){
  val b=c(10,100.0,111.0,94.0,101.0);assertEquals(-1.0,resolveReversalBar(true,100.0,95.0,110.0,b).exitR!!,0.0);assertEquals(-1.0,resolveReversalBar(false,100.0,105.0,90.0,b).exitR!!,0.0)
 }
 @Test fun confirmedBuyIsEmittedOnlyAfterPivotAndBreakoutClose(){
  val x=bullishReversalSample()
  assertTrue(ConfirmedReversalEngine.detect("BTCUSDT",x.take(43)).isEmpty())
  val signal=ConfirmedReversalEngine.detect("BTCUSDT",x).single()
  assertEquals("BUY",signal.side);assertEquals(43,signal.confirmationIndex);assertTrue(signal.stop<signal.entry);assertTrue(signal.target>signal.entry);assertTrue(signal.score>=ConfirmedReversalEngine.MIN_SCORE)
 }
 @Test fun replayEntersNextBarAndRecordsTargetWin(){
  val stats=ConfirmedReversalEngine.backtest(bullishReversalSample(),maxHoldingBars=10)
  assertEquals(1,stats.trades);assertEquals(1,stats.wins);assertTrue(stats.winRatePct==100.0);assertTrue(stats.averageR>0.0)
 }
 @Test fun confirmedShortIsEmittedAtSwingHighAndReplayed(){
  val x=bearishReversalSample();val signal=ConfirmedReversalEngine.detect("ETHUSDT",x).single()
  assertEquals("SHORT",signal.side);assertEquals(43,signal.confirmationIndex);assertTrue(signal.stop>signal.entry);assertTrue(signal.target<signal.entry)
  val stats=ConfirmedReversalEngine.backtest(x,maxHoldingBars=10);assertEquals(1,stats.trades);assertEquals(1,stats.wins)
 }
 @Test fun replaySkipsRightCensoredTradesWithoutFullHoldingWindow(){assertEquals(0,ConfirmedReversalEngine.backtest(bullishReversalSample(),maxHoldingBars=64).trades)}
 @Test fun invalidBacktestInputsReturnZeroTrades(){assertEquals(0,ConfirmedReversalEngine.backtest(emptyList()).trades);assertEquals(0,ConfirmedReversalEngine.backtest(emptyList(),roundTripCostBps=-1.0).trades)}
}
