package com.risktracker.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Supplemental pattern detectors. Results are watchlist candidates, never execution approvals. */
internal object SupplementalTradeStrategies {
    fun evaluate(t: ScanTicker, context: List<ScanCandle>, setup: List<ScanCandle>,
                 benchmark: List<ScanCandle>, micro: MarketMicrostructure): List<TradeIdea> {
        val c = context.dropLast(1)
        val s = setup.dropLast(1)
        if (c.size < 50 || s.size < 30 || t.last <= 0.0) return emptyList()
        val atr = atr(s) ?: return emptyList()
        val ema20 = ema(c.map { it.close }, 20) ?: return emptyList()
        val ema50 = ema(c.map { it.close }, 50) ?: return emptyList()
        val setupEma = ema(s.map { it.close }, 20) ?: return emptyList()
        val last = s.last()
        val previous = s.dropLast(1)
        val volumeAvg = previous.takeLast(20).map { it.volume }.average()
        val volumeRatio = if (volumeAvg > 0.0) last.volume / volumeAvg else 0.0
        val rsi = rsi(s.map { it.close }) ?: 50.0
        val out = mutableListOf<TradeIdea>()

        fun add(name: String, side: String, score: Int, reason: String) {
            val long = side == "LONG"
            val swing = s.takeLast(12)
            val structural = if (long) swing.minOf { it.low } - atr * 0.15 else swing.maxOf { it.high } + atr * 0.15
            val entry = last.close
            val stop = if (long) min(structural, entry - atr * 1.15) else max(structural, entry + atr * 1.15)
            val risk = abs(entry - stop)
            if (!risk.isFinite() || risk <= 0.0) return
            val checks = listOf(
                (name + " pattern detected") to true,
                "15M volume >= 1.2x" to (volumeRatio >= 1.2),
                "Live spread <= 10 bps" to micro.spreadBps?.let { it <= 10.0 },
                "Two-sided book depth available" to if (micro.bidDepthUsd != null && micro.askDepthUsd != null) micro.bidDepthUsd > 0 && micro.askDepthUsd > 0 else null,
                "Entry confirmation candle" to false
            )
            out += TradeIdea(
                symbol=t.symbol, side=side, score=score, volume=t.quoteVolume, change=t.changePct,
                last=t.last, entry=entry, stop=stop,
                target1=if (long) entry + risk * 2.2 else entry - risk * 2.2,
                target2=if (long) entry + risk * 3.0 else entry - risk * 3.0,
                entryTriggered=false, rsi=rsi, volumeRatio=volumeRatio,
                bias4h=if (ema20 > ema50) "Bullish" else "Bearish",
                bias1h=if (ema20 > ema50) "Bullish" else "Bearish",
                setup=name, reasons=listOf(reason, "Supplemental watchlist candidate; execution gates still need live validation."),
                warning="WATCHLIST ONLY · entry confirmation and live risk gates required",
                horizon="Intraday · 15M", bias5m="Awaiting confirmation",
                spreadBps=micro.spreadBps, bookImbalance=micro.bookImbalance,
                fundingRate=micro.fundingRate, oiChangePct=micro.oiChangePct,
                backtest=TradeIdeaBacktestStats(), volatilityPct=t.volatilityPct,
                strategyChecks=checks, bidDepthUsd=micro.bidDepthUsd, askDepthUsd=micro.askDepthUsd
            )
        }

        // Trend pullback: higher-timeframe EMA alignment and 15M EMA20 reclaim/rejection.
        if (ema20 > ema50 && c.last().close > ema20 &&
            previous.takeLast(3).any { it.low <= setupEma * 1.003 } && last.close > setupEma && last.close > last.open)
            add("Trend Pullback", "LONG", 78, "1H EMA20 above EMA50; 15M pulled back to and reclaimed EMA20.")
        if (ema20 < ema50 && c.last().close < ema20 &&
            previous.takeLast(3).any { it.high >= setupEma * 0.997 } && last.close < setupEma && last.close < last.open)
            add("Trend Pullback", "SHORT", 78, "1H EMA20 below EMA50; 15M retested and rejected EMA20.")

        // Range reversal: rejection at an outer range boundary plus RSI.
        val range = s.takeLast(30)
        val hi = range.maxOf { it.high }; val lo = range.minOf { it.low }; val width = hi - lo
        if (width > atr * 2.0) {
            val location = (last.close - lo) / width
            if (location <= 0.15 && rsi <= 40.0 && last.close > last.open)
                add("Range Reversal", "LONG", 73, "Rejected lower 15% of 30-bar range; RSI " + "%.1f".format(rsi) + ".")
            if (location >= 0.85 && rsi >= 60.0 && last.close < last.open)
                add("Range Reversal", "SHORT", 73, "Rejected upper 15% of 30-bar range; RSI " + "%.1f".format(rsi) + ".")
        }

        // Volatility squeeze then expansion through the prior 20-bar range.
        val compressed = atr(s.takeLast(8)); val baseline = atr(s.takeLast(30))
        if (compressed != null && baseline != null && compressed < baseline * 0.78 && volumeRatio >= 1.5) {
            val box = s.dropLast(1).takeLast(20)
            if (last.close > box.maxOf { it.high } && last.close > last.open)
                add("Volatility Breakout", "LONG", 82, "ATR compression followed by 20-bar high breakout on " + "%.2f".format(volumeRatio) + "x volume.")
            if (last.close < box.minOf { it.low } && last.close < last.open)
                add("Volatility Breakout", "SHORT", 82, "ATR compression followed by 20-bar low breakdown on " + "%.2f".format(volumeRatio) + "x volume.")
        }

        // Futures squeeze: require measured OI and funding; unavailable data never creates a signal.
        val oi = micro.oiChangePct; val funding = micro.fundingRate
        if (oi != null && funding != null && oi >= 2.0 && abs(funding) >= 0.0005) {
            val base = s[s.lastIndex - 4].close
            val move = if (base > 0.0) (last.close / base - 1.0) * 100.0 else 0.0
            if (funding > 0 && move < -0.6) add("Futures Squeeze", "SHORT", 76, "Positive crowded funding, rising OI and downside impulse; possible long squeeze.")
            if (funding < 0 && move > 0.6) add("Futures Squeeze", "LONG", 76, "Negative crowded funding, rising OI and upside impulse; possible short squeeze.")
        }

        // Relative strength / SMT divergence against BTC over matching higher-timeframe windows.
        if (benchmark.size >= 22) {
            val coin = c.takeLast(20); val btc = benchmark.dropLast(1).takeLast(20)
            if (btc.size == 20) {
                val relative = ((coin.last().close / coin.first().close) - (btc.last().close / btc.first().close)) * 100.0
                val coinLL = coin.takeLast(10).minOf { it.low } < coin.take(10).minOf { it.low }
                val btcLL = btc.takeLast(10).minOf { it.low } < btc.take(10).minOf { it.low }
                val coinHH = coin.takeLast(10).maxOf { it.high } > coin.take(10).maxOf { it.high }
                val btcHH = btc.takeLast(10).maxOf { it.high } > btc.take(10).maxOf { it.high }
                if (coinLL && !btcLL && relative > 0.5)
                    add("Relative Strength / SMT", "LONG", 75, "Coin made a lower low while BTC did not; relative return outperformed BTC.")
                if (coinHH && !btcHH && relative < -0.5)
                    add("Relative Strength / SMT", "SHORT", 75, "Coin made a higher high while BTC did not; relative return underperformed BTC.")
            }
        }

        // Session liquidity raid during approximate London / New York UTC windows.
        val hour = (last.closeTime / 3_600_000L % 24L).toInt()
        if (hour in 7..11 || hour in 13..17) {
            val ref = previous.takeLast(8)
            if (ref.size == 8) {
                val low = ref.minOf { it.low }; val high = ref.maxOf { it.high }
                val body = abs(last.close - last.open)
                val lowerWick = min(last.open, last.close) - last.low
                val upperWick = last.high - max(last.open, last.close)
                if (last.low < low && last.close > low && lowerWick > body * 0.5)
                    add("Session Liquidity Raid", "LONG", 79, "London/New York UTC window swept the prior 8-bar low and reclaimed it.")
                if (last.high > high && last.close < high && upperWick > body * 0.5)
                    add("Session Liquidity Raid", "SHORT", 79, "London/New York UTC window swept the prior 8-bar high and reclaimed it.")
            }
        }
        return out.distinctBy { it.symbol + "|" + it.side + "|" + it.setup }
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        values.drop(period).forEach { value = it * alpha + value * (1.0 - alpha) }
        return value.takeIf { it.isFinite() }
    }
    private fun rsi(values: List<Double>): Double? {
        if (values.size < 15) return null
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(14)
        val gain = changes.filter { it > 0 }.sum() / 14.0
        val loss = -changes.filter { it < 0 }.sum() / 14.0
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }
    private fun atr(rows: List<ScanCandle>): Double? {
        if (rows.size < 3) return null
        val ranges = rows.zipWithNext { p, c -> max(c.high-c.low, max(abs(c.high-p.close), abs(c.low-p.close))) }.takeLast(14)
        return ranges.average().takeIf { it.isFinite() && it > 0.0 }
    }
}
