package com.risktracker.app
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
internal data class ReversalCandle(val openTime:Long,val closeTime:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double)
internal data class ConfirmedReversalSignal(val symbol:String,val side:String,val pivotTime:Long,val confirmedAt:Long,val entry:Double,val stop:Double,val target:Double,val score:Int,val reasons:List<String>,val confirmationIndex:Int)
internal data class ReversalBacktestStats(val trades:Int=0,val wins:Int=0,val winRatePct:Double=0.0,val profitFactor:Double?=null,val averageR:Double=0.0,val maxDrawdownR:Double=0.0)
internal data class ReversalBarOutcome(val exitR:Double?)
internal fun validReversalCandles(rows:List<ReversalCandle>):Boolean {
 if(rows.size<2)return false
 var previous=Long.MIN_VALUE
 return rows.all { c ->
  val ok=c.openTime>=0L&&c.closeTime>c.openTime&&c.closeTime>previous&&listOf(c.open,c.high,c.low,c.close,c.volume).all{it.isFinite()}&&c.low>0&&c.high>=c.low&&c.open in c.low..c.high&&c.close in c.low..c.high&&c.volume>=0
  previous=c.closeTime;ok
 }
}
internal fun isConfirmedPivotLow(rows:List<ReversalCandle>,pivotIndex:Int,leftBars:Int=3,rightBars:Int=3,asOfIndex:Int=rows.lastIndex):Boolean {
 if (pivotIndex < 0 || pivotIndex >= rows.size || asOfIndex >= rows.size ||
     pivotIndex + rightBars > asOfIndex) return false
 return ScannerCalculationCore.confirmedPivots(
  rows.map { it.high }, rows.map { it.low }, leftBars, rightBars
 ).any { !it.isHigh && it.index == pivotIndex }
}
internal fun isConfirmedPivotHigh(rows:List<ReversalCandle>,pivotIndex:Int,leftBars:Int=3,rightBars:Int=3,asOfIndex:Int=rows.lastIndex):Boolean {
 if (pivotIndex < 0 || pivotIndex >= rows.size || asOfIndex >= rows.size ||
     pivotIndex + rightBars > asOfIndex) return false
 return ScannerCalculationCore.confirmedPivots(
  rows.map { it.high }, rows.map { it.low }, leftBars, rightBars
 ).any { it.isHigh && it.index == pivotIndex }
}
internal fun resolveReversalBar(isLong:Boolean,entry:Double,stop:Double,target:Double,c:ReversalCandle):ReversalBarOutcome {
 if(!listOf(entry,stop,target,c.high,c.low).all{it.isFinite()}||entry<=0||stop<=0||target<=0||c.high<c.low)return ReversalBarOutcome(-1.0)
 val stopped=if(isLong)c.low<=stop else c.high>=stop
 val targeted=if(isLong)c.high>=target else c.low<=target
 return when { stopped->ReversalBarOutcome(-1.0);targeted->ReversalBarOutcome(if(isLong)(target-entry)/abs(entry-stop) else (entry-target)/abs(entry-stop));else->ReversalBarOutcome(null) }
}
internal object ConfirmedReversalEngine {
 const val INTERVAL="15m";const val MIN_SCORE=65;const val TARGET_R=2.0
 fun detect(symbol:String,rows:List<ReversalCandle>,leftBars:Int=3,rightBars:Int=3,minimumScore:Int=MIN_SCORE):List<ConfirmedReversalSignal> {
  if(!validReversalCandles(rows)||rows.size<40||leftBars<1||rightBars<1||minimumScore !in 1..100)return emptyList()
  val out=mutableListOf<ConfirmedReversalSignal>();var priorLow:Pair<Int,Double>?=null;var priorHigh:Pair<Int,Double>?=null;val emitted=mutableSetOf<Int>()
  for(ci in leftBars+rightBars until rows.size) {
   val p=ci-rightBars;val pivot=rows[p];val confirm=rows[ci];val rsi=rsiAt(rows,p)?:continue;val atr=atrAt(rows,ci)?:continue
   val avg=ScannerCalculationCore.averageVolume(rows.take(p).map { it.volume },20) ?: 0.0
   val rv=if(avg>0)pivot.volume/avg else 0.0;val body=abs(pivot.close-pivot.open);val range=(pivot.high-pivot.low).coerceAtLeast(1e-12)
   val lower=min(pivot.open,pivot.close)-pivot.low;val upper=pivot.high-max(pivot.open,pivot.close)
   if(isConfirmedPivotLow(rows,p,leftBars,rightBars,ci)) {
    val prev=priorLow;val div=prev!=null&&pivot.low<rows[prev.first].low&&rsi>=prev.second+2;val os=rsi<=35;val wick=lower>=max(body*.8,range*.30)
    if(p !in emitted&&(div||os)&&confirm.close>pivot.high&&confirm.close>confirm.open) {
     val stop=pivot.low-atr*.15;val entry=confirm.close;val risk=entry-stop
     if(risk.isFinite()&&risk>0) {val score=(if(div)30 else 0)+(if(wick)15 else 0)+(if(os)15 else 0)+(if(rv>=1.2)15 else 0)+25
      if(score>=minimumScore) {val evidence=buildList {add("Confirmed swing low after $rightBars closed candles");add(if(div)"Bullish RSI divergence" else "Pivot RSI oversold");if(wick)add("Lower-wick rejection at pivot");if(rv>=1.2)add("Pivot relative volume confirmed");add("Bullish candle closed above pivot high")}
       val why=listOf(ScannerCalculationCore.primarySignalReason("Confirmed Reversal","BUY",evidence))+evidence
       out.add(ConfirmedReversalSignal(symbol.uppercase(),"BUY",pivot.closeTime,confirm.closeTime,entry,stop,entry+risk*TARGET_R,score,why,ci));emitted.add(p)
      }
     }
    };priorLow=p to rsi
   }
   if(isConfirmedPivotHigh(rows,p,leftBars,rightBars,ci)) {
    val prev=priorHigh;val div=prev!=null&&pivot.high>rows[prev.first].high&&rsi<=prev.second-2;val ob=rsi>=65;val wick=upper>=max(body*.8,range*.30)
    if(p !in emitted&&(div||ob)&&confirm.close<pivot.low&&confirm.close<confirm.open) {
     val stop=pivot.high+atr*.15;val entry=confirm.close;val risk=stop-entry
     if(risk.isFinite()&&risk>0) {val score=(if(div)30 else 0)+(if(wick)15 else 0)+(if(ob)15 else 0)+(if(rv>=1.2)15 else 0)+25
      if(score>=minimumScore) {val evidence=buildList {add("Confirmed swing high after $rightBars closed candles");add(if(div)"Bearish RSI divergence" else "Pivot RSI overbought");if(wick)add("Upper-wick rejection at pivot");if(rv>=1.2)add("Pivot relative volume confirmed");add("Bearish candle closed below pivot low")}
       val why=listOf(ScannerCalculationCore.primarySignalReason("Confirmed Reversal","SHORT",evidence))+evidence
       out.add(ConfirmedReversalSignal(symbol.uppercase(),"SHORT",pivot.closeTime,confirm.closeTime,entry,stop,entry-risk*TARGET_R,score,why,ci));emitted.add(p)
      }
     }
    };priorHigh=p to rsi
   }
  }
  return out.sortedBy{it.confirmedAt}
 }
 /** Next-candle-open entry; conservative stop-first OHLC replay; estimated costs deducted. */
 fun backtest(rows:List<ReversalCandle>,maxHoldingBars:Int=64,roundTripCostBps:Double=12.0):ReversalBacktestStats {
  if(!validReversalCandles(rows)||maxHoldingBars<1||!roundTripCostBps.isFinite()||roundTripCostBps<0)return ReversalBacktestStats()
  val results=mutableListOf<Double>();var nextFree=0
  for(s in detect("BACKTEST",rows)) {
   val ei=s.confirmationIndex+1;if(ei>=rows.size||ei<nextFree||ei+maxHoldingBars-1>rows.lastIndex)continue
   val long=s.side=="BUY";val entry=rows[ei].open;val risk=if(long)entry-s.stop else s.stop-entry
   if(!risk.isFinite()||risk<=0)continue
   val target=if(long)entry+risk*TARGET_R else entry-risk*TARGET_R;val end=min(rows.lastIndex,ei+maxHoldingBars-1);var result:Double?=null
   for(j in ei..end){val o=resolveReversalBar(long,entry,s.stop,target,rows[j]);if(o.exitR!=null){result=o.exitR;nextFree=j+1;break}}
   if(result==null){val last=rows[end].close;result=if(long)(last-entry)/risk else (entry-last)/risk;nextFree=end+1}
   results.add(result-entry*roundTripCostBps/10000.0/risk)
  }
  if(results.isEmpty())return ReversalBacktestStats()
  val wins=results.count{it>0};val gw=results.filter{it>0}.sum();val gl=-results.filter{it<0}.sum();var equity=0.0;var peak=0.0;var dd=0.0
  results.forEach{equity+=it;peak=max(peak,equity);dd=max(dd,peak-equity)}
  return ReversalBacktestStats(results.size,wins,wins.toDouble()/results.size*100,if(gl>0)gw/gl else null,results.average(),dd)
 }
 private fun rsiAt(rows:List<ReversalCandle>,i:Int,period:Int=14):Double? {
  if (i < period || i >= rows.size) return null
  return ScannerCalculationCore.rsiSimple(rows.take(i + 1).map { it.close }, period)
 }
 private fun atrAt(rows:List<ReversalCandle>,i:Int):Double? {
  if (i < 14 || i >= rows.size) return null
  val sample = rows.take(i + 1)
  return ScannerCalculationCore.atr(sample.map { it.high }, sample.map { it.low }, sample.map { it.close }, 14)
 }
}
