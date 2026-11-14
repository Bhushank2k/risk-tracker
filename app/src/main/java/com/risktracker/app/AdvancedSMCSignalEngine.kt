package com.risktracker.app

import kotlin.math.abs

internal data class AdvancedSMCCandle(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long)
internal data class AdvancedSMCSignal(
    val id: String, val symbol: String, val side: String, val signalType: String, val horizon: String,
    val score: Int, val status: String, val entryLow: Double, val entryHigh: Double, val stop: Double,
    val target1: Double, val target2: Double, val rewardRisk: Double, val explanation: String,
    val confirmation: String, val detectedAt: Long
)

/** Candle-derived pattern detection; not proof of institutional orders or guaranteed outcomes. */
internal object AdvancedSMCSignalEngine {
    fun evaluate(symbol: String, livePrice: Double, setupRaw: List<AdvancedSMCCandle>,
                 executionRaw: List<AdvancedSMCCandle>, benchmarkRaw: List<AdvancedSMCCandle>,
                 horizon: String): List<AdvancedSMCSignal> {
        val rows = setupRaw.dropLast(1); val execution = executionRaw.dropLast(1); val benchmark = benchmarkRaw.dropLast(1)
        if (livePrice <= 0.0 || rows.size < 45 || execution.size < 20) return emptyList()
        val atr = rows.zipWithNext { p, c -> maxOf(c.high-c.low, abs(c.high-p.close), abs(c.low-p.close)) }.takeLast(14).average()
        if (!atr.isFinite() || atr <= 0.0) return emptyList()
        return listOfNotNull(
            ifvg(symbol, livePrice, rows, execution, atr, horizon),
            breaker(symbol, livePrice, rows, execution, atr, horizon),
            smt(symbol, livePrice, rows, benchmark, atr, horizon),
            sessionRaid(symbol, livePrice, rows, atr, horizon)
        ).distinctBy { it.signalType }.sortedByDescending { it.score }
    }

    private fun ifvg(symbol: String, price: Double, r: List<AdvancedSMCCandle>, x: List<AdvancedSMCCandle>, atr: Double, h: String): AdvancedSMCSignal? {
        for (i in r.lastIndex-3 downTo maxOf(2, r.size-36)) {
            val a=r[i-2]; val g=r[i]; val e=x.last()
            if (g.low>a.high) {
                val lo=a.high; val hi=g.low
                if ((i+1 until r.size).any { r[it].close<lo } && price<=hi && abs(price-(lo+hi)/2)<=2*atr) {
                    val reject=e.high>=lo && e.low<=hi && e.close<lo && e.close<e.open
                    return plan(symbol,"SHORT","BEARISH IFVG",h,lo,hi,maxOf(hi,e.high)+atr*.12,r,price,atr,88,
                        if(reject) "CONFIRMED RETEST" else "WAIT FOR RETEST",
                        "Bullish FVG was invalidated by a close below its lower boundary.",
                        if(reject) "Execution candle retested and rejected the inverted gap." else "Wait for bearish rejection at the inverted gap.")
                }
            }
            if (g.high<a.low) {
                val lo=g.high; val hi=a.low
                if ((i+1 until r.size).any { r[it].close>hi } && price>=lo && abs(price-(lo+hi)/2)<=2*atr) {
                    val reject=e.low<=hi && e.high>=lo && e.close>hi && e.close>e.open
                    return plan(symbol,"LONG","BULLISH IFVG",h,lo,hi,minOf(lo,e.low)-atr*.12,r,price,atr,88,
                        if(reject) "CONFIRMED RETEST" else "WAIT FOR RETEST",
                        "Bearish FVG was invalidated by a close above its upper boundary.",
                        if(reject) "Execution candle retested and rejected the inverted gap upward." else "Wait for bullish rejection at the inverted gap.")
                }
            }
        }
        return null
    }

    private fun breaker(symbol: String, price: Double, r: List<AdvancedSMCCandle>, x: List<AdvancedSMCCandle>, atr: Double, h: String): AdvancedSMCSignal? {
        val e=x.last(); val prior=r.dropLast(1).takeLast(12); if(prior.size<8) return null
        val last=r.last(); val up=last.close>prior.maxOf{it.high} && last.close>last.open
        val down=last.close<prior.minOf{it.low} && last.close<last.open
        if(!up && !down) return null
        val idx=(r.lastIndex downTo maxOf(1,r.lastIndex-5)).firstOrNull { n ->
            val p=r.subList(maxOf(0,n-10),n); p.isNotEmpty() && if(up) r[n].close>p.maxOf{it.high} else r[n].close<p.minOf{it.low}
        } ?: return null
        val ob=(idx-1 downTo maxOf(0,idx-6)).firstOrNull { n -> if(up) r[n].close<r[n].open else r[n].close>r[n].open } ?: return null
        val b=r[ob]; val lo=minOf(b.open,b.close); val hi=maxOf(b.open,b.close)
        if(hi<=lo || abs(price-(lo+hi)/2)>2*atr) return null
        val reject=e.low<=hi && e.high>=lo && if(up) e.close>hi && e.close>e.open else e.close<lo && e.close<e.open
        val stop=if(up) minOf(b.low,e.low)-atr*.12 else maxOf(b.high,e.high)+atr*.12
        return plan(symbol,if(up)"LONG" else "SHORT",if(up)"BULLISH BREAKER" else "BEARISH BREAKER",h,lo,hi,stop,r,price,atr,82,
            if(reject)"CONFIRMED RETEST" else "WAIT FOR RETEST",
            "A failed opposing candle block preceded a directional structure break.",
            if(reject)"Execution candle retested and rejected the breaker." else "Wait for a retest and directional rejection.")
    }

    private fun smt(symbol: String, price: Double, r: List<AdvancedSMCCandle>, b: List<AdvancedSMCCandle>, atr: Double, h: String): AdvancedSMCSignal? {
        if(symbol=="BTCUSDT" || r.size<30 || b.size<30) return null
        val an=r.takeLast(5); val ap=r.dropLast(5).takeLast(20); val bn=b.takeLast(5); val bp=b.dropLast(5).takeLast(20)
        val last=r.last()
        val bull=an.minOf{it.low}<ap.minOf{it.low} && bn.minOf{it.low}>=bp.minOf{it.low} && last.close>ap.minOf{it.low} && last.close>last.open
        val bear=an.maxOf{it.high}>ap.maxOf{it.high} && bn.maxOf{it.high}<=bp.maxOf{it.high} && last.close<ap.maxOf{it.high} && last.close<last.open
        if(!bull && !bear) return null
        val lo=if(bull) last.low else last.close-atr*.25; val hi=if(bull) last.close+atr*.25 else last.high
        val stop=if(bull) last.low-atr*.15 else last.high+atr*.15
        return plan(symbol,if(bull)"LONG" else "SHORT",if(bull)"BULLISH SMT" else "BEARISH SMT",h,lo,hi,stop,r,price,atr,78,
            "DIVERGENCE DETECTED","This contract made a swing extreme that BTC did not confirm.",
            "SMT is context only; wait for local structure shift and execution confirmation.")
    }

    private fun sessionRaid(symbol: String, price: Double, r: List<AdvancedSMCCandle>, atr: Double, h: String): AdvancedSMCSignal? {
        val day=(r.last().closeTime/86400000L)*86400000L
        val asia=r.filter{it.closeTime>=day && it.closeTime<day+8L*3600000L}
        val later=r.filter{it.closeTime>=day+8L*3600000L}
        if(asia.size<4 || later.size<2) return null
        val ah=asia.maxOf{it.high}; val al=asia.minOf{it.low}; val last=later.last()
        val p=r.filter{it.closeTime<last.closeTime}.takeLast(12); if(p.size<6) return null
        val bull=last.low<al && last.close>al && last.close>last.open && last.close>p.takeLast(5).maxOf{it.high}
        val bear=last.high>ah && last.close<ah && last.close<last.open && last.close<p.takeLast(5).minOf{it.low}
        if(!bull && !bear) return null
        val lo=if(bull) al-atr*.05 else last.close-atr*.2; val hi=if(bull) last.close+atr*.2 else ah+atr*.05
        val stop=if(bull) last.low-atr*.12 else last.high+atr*.12
        return plan(symbol,if(bull)"LONG" else "SHORT","ASIA RANGE RAID",h,lo,hi,stop,r,price,atr,84,
            "RAID + SHIFT DETECTED","Price swept the UTC 00:00–08:00 Asian-session range and reclaimed it.",
            "A directional close also broke recent local structure; wait for a controlled retest.")
    }

    private fun plan(symbol:String,side:String,type:String,h:String,lo:Double,hi:Double,stop:Double,
                     r:List<AdvancedSMCCandle>,price:Double,atr:Double,score:Int,status:String,
                     why:String,confirm:String):AdvancedSMCSignal? {
        if(!lo.isFinite() || !hi.isFinite() || lo<=0 || hi<=lo) return null
        val entry=(lo+hi)/2; val risk=if(side=="LONG") entry-stop else stop-entry
        if(risk<=0 || !risk.isFinite() || abs(price-entry)>atr*2.5) return null
        val levels=if(side=="LONG") r.takeLast(60).map{it.high}.filter{it>entry}.distinct().sorted()
                   else r.takeLast(60).map{it.low}.filter{it<entry}.distinct().sortedDescending()
        val t1=levels.firstOrNull{(if(side=="LONG")it-entry else entry-it)/risk>=2.0} ?: return null
        val t2=levels.lastOrNull() ?: t1
        val rr=(if(side=="LONG")t1-entry else entry-t1)/risk
        if(rr<2.0 || !rr.isFinite()) return null
        val inZone=price in lo..hi
        val shown=if(status=="CONFIRMED RETEST" && inZone) "CONFIRMED — RISK CHECK REQUIRED"
                   else if(inZone || abs(price-entry)<=atr*.5) "ENTRY ZONE — WAIT FOR CONFIRMATION" else status
        return AdvancedSMCSignal("\${symbol}_\${type.replace(' ','_')}_\${h}",symbol,side,type,h,
            (score+if(inZone)4 else 0).coerceAtMost(96),shown,lo,hi,stop,t1,t2,rr,why,confirm,r.last().closeTime)
    }
}
