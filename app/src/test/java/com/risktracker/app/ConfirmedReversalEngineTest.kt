package com.risktracker.app
import org.junit.Assert.*
import org.junit.Test
class ConfirmedReversalEngineTest {
 private fun c(i:Int,o:Double,h:Double,l:Double,cl:Double)=ReversalCandle(i*900000L,i*900000L+899999L,o,h,l,cl,100.0)
 @Test fun pivotWaitsForEveryRightHandClose(){
  val x=listOf(c(0,10.0,11.0,9.0,10.0),c(1,10.0,10.5,8.0,9.0),c(2,9.0,9.5,6.0,8.0),c(3,8.0,9.0,7.0,8.5),c(4,8.5,10.0,8.0,9.5),c(5,9.5,10.5,9.0,10.0),c(6,10.0,11.0,9.5,10.5))
  assertFalse(isConfirmedPivotLow(x,2,2,3,4));assertFalse(isConfirmedPivotLow(x,2,2,3,5));assertTrue(isConfirmedPivotLow(x,2,2,3,6))
 }
 @Test fun rejectsMalformedAndOutOfOrderRows(){
  val x=listOf(c(0,10.0,11.0,9.0,10.0),c(1,10.0,11.0,9.0,10.0));assertTrue(validReversalCandles(x));assertFalse(validReversalCandles(x.reversed()));assertFalse(validReversalCandles(listOf(c(0,10.0,8.0,9.0,10.0),x[1])))
 }
 @Test fun stopWinsIfStopAndTargetHitSameBar(){
  val b=c(10,100.0,111.0,94.0,101.0);assertEquals(-1.0,resolveReversalBar(true,100.0,95.0,110.0,b).exitR!!,0.0);assertEquals(-1.0,resolveReversalBar(false,100.0,105.0,90.0,b).exitR!!,0.0)
 }
 @Test fun invalidBacktestInputsReturnZeroTrades(){assertEquals(0,ConfirmedReversalEngine.backtest(emptyList()).trades);assertEquals(0,ConfirmedReversalEngine.backtest(emptyList(),roundTripCostBps=-1.0).trades)}
}
