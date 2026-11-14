package com.risktracker.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class MarketTicker(val symbol:String,val lastPrice:Double,val changePercent:Double,val quoteVolume:Double)
data class MarketCandle(val openTime:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double,val closeTime:Long,val quoteVolume:Double)
enum class MarketInterval(val api:String,val label:String){M5("5m","5m"),M15("15m","15m"),H1("1h","1h"),H4("4h","4h"),D1("1d","1D")}
enum class ActivityState{WATCH,CONFIRMED_HISTORICAL_BREAKOUT,INVALIDATED,NO_SETUP}
enum class ActivityDirection{BULLISH,BEARISH,NEUTRAL}
data class IndicatorSnapshot(val ema9:Double?,val ema20:Double?,val ema50:Double?,val ema200:Double?,val rsi14:Double?,val macd:Double?,val macdSignal:Double?,val bbUpper:Double?,val bbMiddle:Double?,val bbLower:Double?,val bbWidthPercent:Double?,val atr14:Double?,val atrPercent:Double?,val vwap:Double?,val historicalVolatility:Double?,val relativeVolume:Double?,val volumeBaseline:Double?)
data class ActivityBreakdown(val relativeVolume:Int,val volatilityExpansion:Int,val rangeCompression:Int,val bollingerBehavior:Int,val emaAlignment:Int,val macdMomentum:Int,val rsiConfirmation:Int){val total get()=relativeVolume+volatilityExpansion+rangeCompression+bollingerBehavior+emaAlignment+macdMomentum+rsiConfirmation}
data class ActivityAnalysis(val symbol:String,val interval:MarketInterval,val last:MarketCandle,val indicators:IndicatorSnapshot,val score:ActivityBreakdown,val state:ActivityState,val direction:ActivityDirection,val rangeHigh:Double?,val rangeLow:Double?,val explanation:List<String>)

object MarketActivityEngine {
    private fun ema(v:List<Double>,p:Int):List<Double?> = ScannerCalculationCore.emaSeries(v,p)
    private fun sma(v:List<Double>,p:Int):Double?=if(v.size<p)null else v.takeLast(p).average()
    private fun sd(v:List<Double>):Double {val m=v.average();return sqrt(v.sumOf{(it-m).pow(2)}/v.size)}
    private fun rsi(v:List<Double>):Double? = ScannerCalculationCore.rsiWilder(v,14)
    fun indicators(cs:List<MarketCandle>):IndicatorSnapshot {
        val c=cs.map{it.close}; if(c.isEmpty())return IndicatorSnapshot(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null)
        val e9=ema(c,9).lastOrNull{it!=null};val e20=ema(c,20).lastOrNull{it!=null};val e50=ema(c,50).lastOrNull{it!=null};val e200=ema(c,200).lastOrNull{it!=null}
        val f=ema(c,12);val s=ema(c,26);val ml=c.indices.map{if(f[it]!=null&&s[it]!=null)f[it]!!-s[it]!! else null};val ms=ema(ml.filterNotNull(),9).lastOrNull{it!=null}
        val mid=sma(c,20);val dev=if(c.size>=20)sd(c.takeLast(20))else null;val up=if(mid!=null&&dev!=null)mid+2*dev else null;val low=if(mid!=null&&dev!=null)mid-2*dev else null
        val atr=ScannerCalculationCore.atr(cs.map { it.high },cs.map { it.low },c,14)
        val rets=c.zipWithNext().takeLast(20).map{ln(it.second/it.first)}
        val hv=if(rets.size>=2)sd(rets)*100 else null
        val vb=ScannerCalculationCore.averageVolume(cs.map { it.volume },20,excludeLast=true)
        val vwapBars=cs.takeLast(20);val vd=vwapBars.sumOf{it.volume};val vw=if(vd>0)vwapBars.sumOf{((it.high+it.low+it.close)/3)*it.volume}/vd else null
        return IndicatorSnapshot(e9,e20,e50,e200,rsi(c),ml.lastOrNull{it!=null},ms,up,mid,low,if(up!=null&&low!=null&&mid!=null&&mid!=0.0)(up-low)/mid*100 else null,atr,if(atr!=null)atr/c.last()*100 else null,vw,hv,if(vb!=null&&vb>0)cs.last().volume/vb else null,vb)
    }
    fun analyze(symbol:String,interval:MarketInterval,cs:List<MarketCandle>,thresholds:ActivityThresholds=ActivityThresholds()):ActivityAnalysis? {
        val c=cs.filter{listOf(it.open,it.high,it.low,it.close,it.volume).all{v->v.isFinite()}&&it.close>0&&it.volume>=0};if(c.size<30)return null
        val x=c.last();val p=c.dropLast(1).takeLast(20);val hi=p.maxOf{it.high};val lo=p.minOf{it.low};val range=(hi-lo)/lo*100
        val ind=indicators(c);val prev=indicators(c.dropLast(1)).bbWidthPercent;val contracted=ind.bbWidthPercent!=null&&prev!=null&&ind.bbWidthPercent<prev
        val compressed=range<thresholds.rangeCompressionPercent;val up=x.close>hi&&x.close>x.open;val down=x.close<lo&&x.close<x.open;val breakout=up||down
        val dir=when{up->ActivityDirection.BULLISH;down->ActivityDirection.BEARISH;ind.ema9!=null&&ind.ema20!=null&&ind.ema9>ind.ema20->ActivityDirection.BULLISH;ind.ema9!=null&&ind.ema20!=null&&ind.ema9<ind.ema20->ActivityDirection.BEARISH;else->ActivityDirection.NEUTRAL}
        val rv=ind.relativeVolume?:0.0;val rvPts=when{rv>=3->20;rv>=2->16;rv>=1.5->12;rv>=1.2->7;else->0}
        val atrBase=c.dropLast(1).takeLast(20).mapIndexedNotNull{i,_->val end=c.size-21+i;if(end>=14)indicators(c.take(end+1)).atr14 else null}.takeIf{it.isNotEmpty()}?.average()
        val ar=if(atrBase!=null&&atrBase>0&&ind.atr14!=null)ind.atr14/atrBase else 1.0
        val atrPts=when{ar>=1.8->15;ar>=1.4->11;ar>=1.15->7;else->0}
        val cp=if(compressed)15 else if(range<5)8 else 0
        val bp=when{ind.bbWidthPercent!=null&&ind.bbWidthPercent<2->15;contracted->10;breakout->15;else->0}
        val ep=if(ind.ema9!=null&&ind.ema20!=null&&ind.ema50!=null&&((dir==ActivityDirection.BULLISH&&ind.ema9>ind.ema20&&ind.ema20>ind.ema50)||(dir==ActivityDirection.BEARISH&&ind.ema9<ind.ema20&&ind.ema20<ind.ema50)))15 else 0
        val mp=if(ind.macd!=null&&ind.macdSignal!=null&&((dir==ActivityDirection.BULLISH&&ind.macd>ind.macdSignal)||(dir==ActivityDirection.BEARISH&&ind.macd<ind.macdSignal)))10 else 0
        val rp=if(ind.rsi14!=null&&((dir==ActivityDirection.BULLISH&&ind.rsi14 in 50.0..70.0)||(dir==ActivityDirection.BEARISH&&ind.rsi14 in 30.0..50.0)))10 else 0
        val state=when{breakout&&rv>=thresholds.relativeVolumeBreakout->ActivityState.CONFIRMED_HISTORICAL_BREAKOUT;compressed&&(rv>=thresholds.relativeVolumeWatch||contracted||ar<0.8)->ActivityState.WATCH;else->ActivityState.NO_SETUP}
        val evidence=listOf("Relative volume %.2f× baseline".format(rv),"Prior 20-candle range %.2f%%".format(range),if(breakout)"Closed candle moved beyond the prior range" else "No confirmed range break")
        val primary=ScannerCalculationCore.primarySignalReason(state.name,dir.name,evidence)
        return ActivityAnalysis(symbol,interval,x,ind,ActivityBreakdown(rvPts,atrPts,cp,bp,ep,mp,rp),state,dir,hi,lo,listOf(primary)+evidence)
    }
    data class ReplayStats(val setups:Int,val successful:Int,val failed:Int,val falsePositiveRate:Double?,val averageReturnPercent:Double?,val maxAdversePercent:Double?,val maxFavorablePercent:Double?)
    fun replay(cs:List<MarketCandle>,horizon:Int=12,feeBps:Double=8.0,slippageBps:Double=5.0,evaluationStartIndex:Int=30):ReplayStats {
        require(horizon>0&&feeBps>=0&&slippageBps>=0&&evaluationStartIndex>=30);val rets=mutableListOf<Double>();val adv=mutableListOf<Double>();val fav=mutableListOf<Double>()
        for(i in evaluationStartIndex until (cs.size-horizon)){val a=analyze("REPLAY",MarketInterval.H1,cs.take(i+1))?:continue;if(a.state!=ActivityState.CONFIRMED_HISTORICAL_BREAKOUT)continue
            val entry=cs[i+1].open;val exit=cs[i+horizon].close;if(entry<=0)continue;val sign=if(a.direction==ActivityDirection.BEARISH)-1.0 else 1.0
            rets.add(sign*(exit/entry-1)*100-2*(feeBps+slippageBps)/100);val path=cs.subList(i+1,i+horizon+1)
            fav.add(if(sign>0)(path.maxOf{it.high}/entry-1)*100 else (entry/path.minOf{it.low}-1)*100)
            adv.add(if(sign>0)(path.minOf{it.low}/entry-1)*100 else (entry/path.maxOf{it.high}-1)*100)}
        val n=rets.size;val wins=rets.count{it>0};return ReplayStats(n,wins,n-wins,if(n>0)(n-wins)*100.0/n else null,rets.average().takeIf{n>0},adv.minOrNull(),fav.maxOrNull())
    }
}
object MarketActivityDataSource {
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val cache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,List<MarketCandle>>>()
    @Volatile private var tickerCache:Pair<Long,Map<String,MarketTicker>>?=null
    suspend fun tickers():Map<String,MarketTicker> = withContext(Dispatchers.IO) {
        val now=System.currentTimeMillis()
        tickerCache?.takeIf{now-it.first<60_000L}?.let{return@withContext it.second}
        val req=Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()
        client.newCall(req).execute().use{res->
            if(!res.isSuccessful)error("Binance 24h ticker HTTP ${res.code}")
            val arr=JSONArray(res.body?.string()?:error("Empty Binance ticker response"))
            val map=(0 until arr.length()).mapNotNull{i->val o=arr.getJSONObject(i);val symbol=o.optString("symbol")
                val last=o.optString("lastPrice").toDoubleOrNull();val change=o.optString("priceChangePercent").toDoubleOrNull();val volume=o.optString("quoteVolume").toDoubleOrNull()
                if(symbol.isNotBlank()&&last!=null&&change!=null&&volume!=null)symbol to MarketTicker(symbol,last,change,volume) else null}.toMap()
            tickerCache=now to map
            map
        }
    }
    suspend fun candles(symbol:String,interval:MarketInterval,limit:Int=240):List<MarketCandle> = withContext(Dispatchers.IO){
        require(symbol.matches(Regex("[A-Z0-9]{5,20}"))&&limit in 30..500);val key="$symbol:${interval.api}:$limit";val now=System.currentTimeMillis()
        cache[key]?.takeIf{now-it.first<60_000}?.let{return@withContext it.second}
        var err:Exception?=null
        repeat(3){attempt->try{
            val req=Request.Builder().url("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=${interval.api}&limit=$limit").build()
            client.newCall(req).execute().use{res->if(!res.isSuccessful)error("Binance market data HTTP ${res.code}")
                val arr=JSONArray(res.body?.string()?:error("Empty Binance response"))
                val list=(0 until arr.length()).map{j->val row=arr.getJSONArray(j);MarketCandle(row.getLong(0),row.getString(1).toDouble(),row.getString(2).toDouble(),row.getString(3).toDouble(),row.getString(4).toDouble(),row.getString(5).toDouble(),row.getLong(6),row.getString(7).toDouble())}.filter{it.closeTime<now&&it.close>0&&it.high>=it.low}
                if(list.size<30)error("Too few completed candles");cache[key]=now to list;return@withContext list}
        }catch(e:Exception){err=e;if(attempt<2)Thread.sleep(300L*(attempt+1))}}
        throw IllegalStateException(err?.message?:"Market data unavailable",err)
    }
}
