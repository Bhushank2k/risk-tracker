package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal const val MIN_TRADE_QUOTE_VOLUME_USD = 2_000_000.0
internal const val MIN_BOOK_SIDE_DEPTH_USD = 15_000.0
internal const val MAX_TRADE_SPREAD_BPS = 10.0

internal fun isTradeIdeaVolumeEligible(quoteVolume: Double): Boolean =
    quoteVolume.isFinite() && quoteVolume >= MIN_TRADE_QUOTE_VOLUME_USD

internal enum class TradeIdeaStructure { BULLISH, BEARISH, MIXED, UNKNOWN }

/** Uses confirmed two-candle-left/two-candle-right pivots; the newest two bars cannot be pivots. */
internal fun classifyTradeIdeaStructure(candles: List<V2Candle>): TradeIdeaStructure {
    if (candles.size < 9 || candles.any {
        !it.high.isFinite() || !it.low.isFinite() || it.high < it.low
    }) return TradeIdeaStructure.UNKNOWN

    val pivotHighs = mutableListOf<Double>()
    val pivotLows = mutableListOf<Double>()
    for (i in 2 until candles.size - 2) {
        val neighbours = (i - 2..i + 2).filter { it != i }
        if (neighbours.all { candles[i].high > candles[it].high }) pivotHighs.add(candles[i].high)
        if (neighbours.all { candles[i].low < candles[it].low }) pivotLows.add(candles[i].low)
    }
    if (pivotHighs.size < 2 || pivotLows.size < 2) return TradeIdeaStructure.UNKNOWN
    val (previousHigh, latestHigh) = pivotHighs.takeLast(2)
    val (previousLow, latestLow) = pivotLows.takeLast(2)
    return when {
        latestHigh > previousHigh && latestLow > previousLow -> TradeIdeaStructure.BULLISH
        latestHigh < previousHigh && latestLow < previousLow -> TradeIdeaStructure.BEARISH
        else -> TradeIdeaStructure.MIXED
    }
}

/** Detects a sweep of the prior range followed by a close back inside that range. */
internal fun hasTradeIdeaLiquiditySweep(
    candles: List<V2Candle>, side: String, lookback: Int = 20
): Boolean {
    if (side !in setOf("LONG", "SHORT") || lookback < 2 || candles.size <= lookback) return false
    val current = candles.last()
    val prior = candles.subList(candles.size - lookback - 1, candles.lastIndex)
    if (prior.isEmpty() || current.high < current.low) return false
    val priorHigh = prior.maxOf { it.high }
    val priorLow = prior.minOf { it.low }
    val body = abs(current.close - current.open)
    val lowerWick = min(current.open, current.close) - current.low
    val upperWick = current.high - max(current.open, current.close)
    return when (side) {
        "LONG" -> current.low < priorLow && current.close > priorLow && lowerWick > 0.0 && lowerWick >= body * 0.5
        "SHORT" -> current.high > priorHigh && current.close < priorHigh && upperWick > 0.0 && upperWick >= body * 0.5
        else -> false
    }
}

internal fun isTradeIdeaLiquidityAcceptable(
    side: String, spreadBps: Double?, bookImbalance: Double?,
    bidDepthUsd: Double?, askDepthUsd: Double?
): Boolean {
    if (side !in setOf("LONG", "SHORT")) return false
    if (spreadBps == null || !spreadBps.isFinite() || spreadBps !in 0.0..MAX_TRADE_SPREAD_BPS) return false
    if (bookImbalance == null || !bookImbalance.isFinite() || bookImbalance !in -1.0..1.0) return false
    if (bidDepthUsd == null || !bidDepthUsd.isFinite() || bidDepthUsd < MIN_BOOK_SIDE_DEPTH_USD) return false
    if (askDepthUsd == null || !askDepthUsd.isFinite() || askDepthUsd < MIN_BOOK_SIDE_DEPTH_USD) return false
    return if (side == "LONG") bookImbalance >= -0.15 else bookImbalance <= 0.15
}

internal fun isConfluenceReady(
    liquid: Boolean, geometry: Boolean, rewardRisk: Boolean, funding: Boolean,
    volatility: Boolean, notExtended: Boolean, volume: Boolean, momentum: Boolean,
    entryConfirmed: Boolean, score: Int, structureConfirmed: Boolean = true
): Boolean = liquid && geometry && rewardRisk && funding && volatility &&
    notExtended && entryConfirmed && structureConfirmed && score >= 75

/** Unified Confluence V4: trend pullback, breakout/retest and range reversal share one risk engine. */
internal object ConfluenceScannerV4 {
    private data class Setup(val name: String, val side: String, val level: Double, val range: Boolean, val detail: String)
    fun evaluate(h4: List<V2Candle>, h1: List<V2Candle>, m15: List<V2Candle>, m5: List<V2Candle>,
        micro: V2Microstructure, quoteVolume: Double, btcBias: String?, horizon: String = "Intraday"): V2TradePlan? {
        if (h4.size < 50 || h1.size < 50 || m15.size < 45 || m5.size < 30 || !isTradeIdeaVolumeEligible(quoteVolume)) return null
        if (!valid(h4) || !valid(h1) || !valid(m15) || !valid(m5)) return null
        val now = System.currentTimeMillis()
        val freshness = when (horizon) {
            "Scalp" -> longArrayOf(2 * 3600000L, 30 * 60000L, 15 * 60000L, 5 * 60000L)
            "Swing" -> longArrayOf(48 * 3600000L, 8 * 3600000L, 2 * 3600000L, 30 * 60000L)
            else -> longArrayOf(8 * 3600000L, 2 * 3600000L, 30 * 60000L, 15 * 60000L)
        }
        if (!fresh(h4.last().closeTime,now,freshness[0]) || !fresh(h1.last().closeTime,now,freshness[1]) ||
            !fresh(m15.last().closeTime,now,freshness[2]) || !fresh(m5.last().closeTime,now,freshness[3])) return null
        val c4=h4.map{it.close}; val c1=h1.map{it.close}; val c15=m15.map{it.close}; val c5=m5.map{it.close}
        val bias1Label = if (horizon == "Scalp") "1H" else if (horizon == "Swing") "1D" else "1H"
        val bias2Label = if (horizon == "Scalp") "15M" else if (horizon == "Swing") "4H" else "4H context"
        val setupLabel = if (horizon == "Scalp") "5M" else if (horizon == "Swing") "1H" else "15M"
        val executionLabel = if (horizon == "Scalp") "1M" else if (horizon == "Swing") "15M" else "5M"
        val e420=ema(c4,20)?:return null; val e450=ema(c4,50)
        val e120=ema(c1,20)?:return null; val e150=ema(c1,50)?:return null
        val e1520=ema(c15,20)?:return null; val e59=ema(c5,9)?:return null; val e521=ema(c5,21)?:return null
        val atr=atr(m15)?:return null; val cur=m15.last(); val prev=m15[m15.lastIndex-1]; val exec=m5.last()
        val up4=e450?.let { h4.last().close>it&&e420>it } ?: false
        val dn4=e450?.let { h4.last().close<it&&e420<it } ?: false
        val up1=h1.last().close>e150&&e120>e150; val dn1=h1.last().close<e150&&e120<e150
        // 1H is the primary intraday directional filter; 4H remains contextual and may be neutral.
        val up=up1; val down=dn1
        val h1Structure = classifyTradeIdeaStructure(h1)
        val rangeMode=!up&&!down&&e450!=null&&abs(e420-e450)/h4.last().close*100<=1.2&&abs(e120-e150)/h1.last().close*100<=.8
        val avg=m15.dropLast(1).takeLast(20).map{it.volume}.average()
        val rv=if(avg>0)cur.volume/avg else 0.0
        val rsi=rsi(c15)?:return null
        val macd=(ema(c15,12)?:return null)-(ema(c15,26)?:return null)
        val prevMacd=(ema(c15.dropLast(1),12)?:macd)-(ema(c15.dropLast(1),26)?:0.0)
        val setups=mutableListOf<Setup>()
        if(up&&cur.low<=e1520+atr*.2&&cur.low>=e1520-atr*.7&&cur.close>e1520&&cur.close>prev.close)
            setups.add(Setup("Trend pullback","LONG",e1520,false,"${setupLabel} EMA20 pullback reclaimed in aligned ${bias1Label}/${bias2Label} uptrend"))
        if(down&&cur.high>=e1520-atr*.2&&cur.high<=e1520+atr*.7&&cur.close<e1520&&cur.close<prev.close)
            setups.add(Setup("Trend pullback","SHORT",e1520,false,"${setupLabel} EMA20 pullback rejected in aligned ${bias1Label}/${bias2Label} downtrend"))
        for(i in (m15.lastIndex-4).coerceAtLeast(20) until m15.lastIndex){
            val hist=m15.subList(i-20,i); val av=hist.map{it.volume}.average()
            if(av<=0||m15[i].volume/av<1.5)continue
            val hi=hist.maxOf{it.high}; val lo=hist.minOf{it.low}
            if(up&&m15[i].close>hi&&cur.low<=hi+atr*.25&&cur.close>=hi&&cur.close>=prev.close)
                setups.add(Setup("Breakout + retest","LONG",hi,false,"${setupLabel} 20-bar breakout on ≥1.5× volume, then retest and hold"))
            if(down&&m15[i].close<lo&&cur.high>=lo-atr*.25&&cur.close<=lo&&cur.close<=prev.close)
                setups.add(Setup("Breakout + retest","SHORT",lo,false,"${setupLabel} 20-bar breakdown on ≥1.5× volume, then retest and hold"))
        }
        if(rangeMode){
            val band=m15.dropLast(1).takeLast(30); val hi=band.maxOf{it.high}; val lo=band.minOf{it.low}; val width=hi-lo
            if(width>atr*2){
                val lw=min(cur.open,cur.close)-cur.low; val uw=cur.high-max(cur.open,cur.close)
                if(cur.low<=lo+width*.12&&cur.close>lo&&lw>=abs(cur.close-cur.open)*.8&&rsi<=42)
                    setups.add(Setup("Range reversal","LONG",lo,true,"Neutral ${bias1Label}/${bias2Label} regime; lower range rejection and RSI reclaim"))
                if(cur.high>=hi-width*.12&&cur.close<hi&&uw>=abs(cur.close-cur.open)*.8&&rsi>=58)
                    setups.add(Setup("Range reversal","SHORT",hi,true,"Neutral ${bias1Label}/${bias2Label} regime; upper range rejection and RSI rollover"))
            }
        }
        if(setups.isEmpty())return null
        val spread=micro.spreadBps; val depth=micro.bookImbalance; val funding=micro.fundingRate
        val bidDepth=micro.bidDepthUsd; val askDepth=micro.askDepthUsd
        return setups.mapNotNull{s->
            val long=s.side=="LONG"; val sign=if(long)1.0 else -1.0
            val expectedStructure = if (long) TradeIdeaStructure.BULLISH else TradeIdeaStructure.BEARISH
            val structureAligned = if (s.range) h1Structure == TradeIdeaStructure.MIXED
                else h1Structure == expectedStructure
            val fourHourNotOpposed = if (s.range) !up4 && !dn4 else if (long) !dn4 else !up4
            val structureConfirmed = structureAligned && fourHourNotOpposed
            val liquiditySweep = hasTradeIdeaLiquiditySweep(m15, s.side)
            val entryOk=if(long)exec.close>e59&&e59>e521 else exec.close<e59&&e59<e521
            val liquid=isTradeIdeaLiquidityAcceptable(s.side, spread, depth, bidDepth, askDepth)
            val fundingOk=isFundingDataAcceptable(s.side, funding)
            val fundingUnavailable=funding==null||!funding.isFinite()
            val momentum=confluenceMomentumConfirmed(s.side, s.range, rsi, macd, prevMacd)
            val atrPct=atr/cur.close*100; val volatility=atrPct in .12..3.0
            val entry=exec.close
            val extended=confluenceEntryWithinExtension(entry, s.level, atr)
            val stop=if(long)m15.takeLast(3).minOf{it.low}-atr*.2 else m15.takeLast(3).maxOf{it.high}+atr*.2
            val risk=abs(entry-stop); val geometry=risk>=atr*.35&&risk<=atr*2.5&&(if(long)stop<entry else stop>entry)
            val setupBarMinutes=when(horizon){"Scalp"->5;"Swing"->60;else->15}
            // Set Target 1 far enough away that estimated fees, slippage and funding still leave 2R net.
            val target1GrossR=if(geometry&&risk>0) tradeIdeaGrossRForMinimumNetReturn(
                minimumNetR=2.0, entry=entry, riskDistance=risk, heldBars=8, setupBarMinutes=setupBarMinutes
            ) else Double.NaN
            val target2GrossR=max(3.0,target1GrossR+1.0)
            val t1=entry+sign*risk*target1GrossR; val t2=entry+sign*risk*target2GrossR
            val netTarget1R=if(geometry&&risk>0) tradeIdeaNetReturnR(target1GrossR,entry,risk,heldBars=8,setupBarMinutes=setupBarMinutes) else Double.NaN
            val rr=geometry&&netTarget1R.isFinite()&&netTarget1R>=2.0
            val btcAgainst=btcBias!=null&&btcBias!="Neutral"&&(if(long)btcBias=="Bearish" else btcBias=="Bullish")
            val score=confluenceScore(
                regimeConfirmed = up || down || rangeMode,
                structureConfirmed = structureConfirmed,
                entryConfirmed = entryOk,
                relativeVolume = rv,
                momentumConfirmed = momentum,
                liquidityConfirmed = liquid,
                btcAgainst = btcAgainst,
                stopGeometryConfirmed = geometry
            )
            val blocks=mutableListOf<String>()
            if(!liquid)blocks.add("spread/depth"); if(!geometry)blocks.add("stop geometry"); if(!rr)blocks.add("2R net R:R")
            if(!fundingOk)blocks.add(if(fundingUnavailable)"funding data unavailable" else "adverse funding"); if(!volatility)blocks.add("ATR%"); if(!extended)blocks.add("extended entry")
            if(rv<1.0)blocks.add("low relative volume (scoring penalty)"); if(!momentum)blocks.add("momentum (scoring penalty)"); if(score<60)blocks.add("score")
            val ready=isConfluenceReady(
                liquid, geometry, rr, fundingOk || fundingUnavailable, volatility, extended, rv>=1.0,
                momentum, entryOk, score, structureConfirmed = structureConfirmed
            )
            val riskCodes = buildSet {
                if (!liquid) add(TradeIdeaRiskCode.LOW_LIQUIDITY)
                if (!geometry) add(TradeIdeaRiskCode.INVALID_STOP)
                if (!rr) add(TradeIdeaRiskCode.INSUFFICIENT_REWARD_RISK)
                if (fundingUnavailable) add(TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE)
                else if (!fundingOk) add(TradeIdeaRiskCode.ADVERSE_FUNDING)
                if (!volatility) add(TradeIdeaRiskCode.VOLATILITY_OUT_OF_RANGE)
                if (!extended) add(TradeIdeaRiskCode.EXTENDED_ENTRY)
                if (rv < 1.0) add(TradeIdeaRiskCode.LOW_RELATIVE_VOLUME)
                if (!momentum) add(TradeIdeaRiskCode.MOMENTUM_NOT_CONFIRMED)
                if (score < 60) add(TradeIdeaRiskCode.LOW_CONFLUENCE_SCORE)
                if (!entryOk) add(TradeIdeaRiskCode.WAITING_FOR_ENTRY_CONFIRMATION)
                if (score in 60..74) add(TradeIdeaRiskCode.BELOW_READY_SCORE_THRESHOLD)
                if (!structureConfirmed) add(TradeIdeaRiskCode.STRUCTURE_NOT_CONFIRMED)
                add(TradeIdeaRiskCode.COSTS_NOT_INCLUDED)
            }
            val warning=when{
                !liquid->"BLOCK: Spread must be ≤10 bps, both 5-level book sides must each show ≥$15k depth, and directional imbalance must be acceptable."
                !geometry->"BLOCK: Structural stop is invalid or outside 0.35–2.5 ATR."
                !rr->"BLOCK: Target 1 estimated net reward/risk is below 2R after costs."
                fundingUnavailable->"WATCH: Funding data unavailable; verify current funding before execution."
                !fundingOk->"BLOCK: Funding is extremely adverse to this direction."
                !volatility->"BLOCK: ${setupLabel} ATR% is outside 0.12–3.0%."
                !extended->"BLOCK: Price is extended from setup level; do not chase."
                !structureConfirmed->"WAIT: Confirmed 1H swing structure must agree with the setup and 4H must not oppose it."
                rv<1.0->"WATCH: Relative volume is below 1.0×; volume confluence is weak."
                !momentum->"WATCH: RSI/MACD momentum is not aligned; other evidence may still qualify."
                score<60->"BLOCK: Confluence score is below 60/100."
                !entryOk->"WAIT: Completed ${executionLabel} EMA9/21 entry confirmation is missing."
                score<75->"WAIT: Setup qualifies, but score is below the 75-point READY threshold."
                else->"BALANCED INTRADAY: Hard gates passed; estimated net Target 1 reward/risk is ≥2R."
            }
            val reasons=listOf("Confluence score "+score+"/100: regime 20 · setup structure 25 · ${executionLabel} entry 15 · volume 10 · momentum 10 · liquidity 10 · BTC 5 · stop geometry 5",
                s.detail,
                "1H swing structure: "+h1Structure+" · 4H context: "+(if(up4)"Bullish" else if(dn4)"Bearish" else "Neutral")+(if(structureConfirmed)" (aligned)" else " (review required)"),
                if(liquiditySweep)"15M liquidity sweep and reclaim detected (supporting evidence; not a standalone trigger)" else "No confirmed 15M liquidity sweep/reclaim (not required for trend continuation)",
                if(s.range)"Range regime: compressed ${bias1Label}/${bias2Label}; reversal restricted to range edges" else "1H EMA20/50 directional bias; 4H context "+(if(up4)"Bullish" else if(dn4)"Bearish" else "Neutral"),
                if(entryOk)"${executionLabel} EMA9/21 confirms entry" else "Waiting for completed ${executionLabel} EMA9/21 confirmation",
                "${setupLabel} relative volume "+"%.2f×".format(java.util.Locale.US,rv)+" (baseline ≥1.2×; breakout ≥1.5×)",
                "${setupLabel} RSI "+"%.1f".format(java.util.Locale.US,rsi)+" · MACD "+(if(momentum)if(s.range)"turning toward setup" else "aligned" else "not aligned"),
                "Spread "+(spread?.let{"%.1f bps".format(java.util.Locale.US,it)}?:"unavailable")+" · imbalance "+(depth?.let{"%+.2f".format(java.util.Locale.US,it)}?:"unavailable")+" · 5-level bid/ask depth "+(bidDepth?.let{"$"+"%.0f".format(java.util.Locale.US,it)}?:"unavailable")+"/"+(askDepth?.let{"$"+"%.0f".format(java.util.Locale.US,it)}?:"unavailable"),
                "BTC regime "+(btcBias?:"unavailable")+(if(btcAgainst)" (headwind, not veto)" else " (context only)"),
                "Risk plan: structural stop + 0.2 ATR; Target 1 is sized for ≥2R net after estimated costs, with Target 2 at least 1R farther.")
            V2TradePlan(s.side,score,entry,stop,if(geometry)t1 else 0.0,if(geometry)t2 else 0.0,ready,s.name,warning,
                if (horizon == "Scalp") "Scalp · M5 setup / M1 execution"
                else if (horizon == "Swing") "Swing · H1 setup / M15 execution"
                else if (s.range) "Intraday · M15 range / M5 execution"
                else "Intraday · M15 setup / M5 execution",
                if(up4)"Bullish" else if(dn4)"Bearish" else "Neutral",if(up1)"Bullish" else if(dn1)"Bearish" else "Neutral",
                if(entryOk)if(long)"Bullish" else "Bearish" else "Mixed",reasons,riskCodes)
        }.sortedWith(compareByDescending<V2TradePlan>{it.entryTriggered}.thenByDescending{it.score}).firstOrNull()
    }
    private fun fresh(t:Long,n:Long,m:Long)=t<n&&n-t<=m
    private fun valid(r:List<V2Candle>):Boolean{var last=Long.MIN_VALUE;return r.all{val ok=it.high.isFinite()&&it.low.isFinite()&&it.open.isFinite()&&it.close.isFinite()&&it.volume.isFinite()&&it.high>=it.low&&it.close in it.low..it.high&&it.open in it.low..it.high&&it.close>0&&it.volume>=0&&it.closeTime>last;last=it.closeTime;ok}}
    private fun ema(v:List<Double>,p:Int):Double?{if(v.size<p)return null;val a=2.0/(p+1);var x=v.take(p).average();v.drop(p).forEach{x=it*a+x*(1-a)};return x.takeIf{it.isFinite()}}
    private fun atr(r:List<V2Candle>):Double?{if(r.size<16)return null;return r.zipWithNext{p,c->max(c.high-c.low,max(abs(c.high-p.close),abs(c.low-p.close)))}.takeLast(14).average().takeIf{it.isFinite()&&it>0}}
    private fun rsi(v:List<Double>):Double?{if(v.size<15)return null;val d=v.zipWithNext{a,b->b-a}.takeLast(14);val g=d.filter{it>0}.sum()/14.0;val l=-d.filter{it<0}.sum()/14.0;if(l==0.0)return if(g==0.0)50.0 else 100.0;return 100-100/(1+g/l)}
}
