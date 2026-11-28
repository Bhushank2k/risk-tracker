package com.risktracker.app

/** Immutable signal snapshot for deterministic, offline replay. No exchange/order APIs are used. */
data class ReplaySignal(
    val id: String,
    val symbol: String,
    val side: String,
    val signalTime: Long,
    val score: Int,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val entryWasTriggered: Boolean = false
)

data class ReplayCandle(
    val openTime: Long,
    val high: Double,
    val low: Double,
    val close: Double
)

enum class ReplayOutcome {
    STOP_BEFORE_TP1,
    TP1_THEN_STOP,
    TP1_ONLY_TIMEOUT,
    TP2,
    TIMEOUT_NO_TP,
    INVALID
}

data class ReplayTradeResult(
    val signalId: String,
    val symbol: String,
    val side: String,
    val score: Int,
    val outcome: ReplayOutcome,
    val entryTime: Long?,
    val exitTime: Long?,
    val barsHeld: Int,
    val grossR: Double,
    val netR: Double,
    val entryTriggered: Boolean,
    val note: String
)

data class ReplayConfig(
    val maxBars: Int = 120,
    val setupBarMinutes: Int = 5,
    val tp1PositionFraction: Double = 0.5,
    val roundTripFeeBps: Double = 8.0,
    val slippageBps: Double = 4.0,
    val fundingBpsPer8h: Double = 1.0
) {
    fun isValid(): Boolean = maxBars > 0 && setupBarMinutes > 0 &&
        tp1PositionFraction in 0.0..1.0 &&
        listOf(roundTripFeeBps, slippageBps, fundingBpsPer8h).all { it.isFinite() && it >= 0.0 }
}

object HistoricalSignalReplay {
    /**
     * Bars must be chronologically ordered and begin after signalTime. For an untriggered
     * signal, entry is activated when a candle range touches the entry. If entry, stop,
     * and target are touched in one candle, this engine resolves the path conservatively:
     * stop first. TP1 closes a configurable fraction; the remaining fraction keeps the
     * original stop until TP2 or timeout (no assumed breakeven move).
     */
    fun replay(signal: ReplaySignal, candles: List<ReplayCandle>, config: ReplayConfig = ReplayConfig()): ReplayTradeResult {
        if (!isSignalValid(signal) || !config.isValid()) return invalid(signal, "Invalid signal geometry or replay configuration")
        val bars = candles.filter { it.openTime > signal.signalTime }.sortedBy { it.openTime }.take(config.maxBars)
        if (bars.isEmpty()) return invalid(signal, "No post-signal candles available")

        val long = signal.side == "LONG"
        val risk = if (long) signal.entry - signal.stop else signal.stop - signal.entry
        val r1 = absDistance(signal.target1, signal.entry) / risk
        val r2 = absDistance(signal.target2, signal.entry) / risk
        var entered = signal.entryWasTriggered
        var entryTime: Long? = if (entered) signal.signalTime else null
        var tp1Hit = false
        var gross = 0.0
        var exitTime: Long? = null
        var held = 0
        var outcome: ReplayOutcome? = null
        var note = ""

        for (bar in bars) {
            if (!entered) {
                val entryTouched = if (long) bar.low <= signal.entry else bar.high >= signal.entry
                if (!entryTouched) continue
                entered = true
                entryTime = bar.openTime
            }
            held += 1

            val stopHit = if (long) bar.low <= signal.stop else bar.high >= signal.stop
            val tp1HitNow = if (long) bar.high >= signal.target1 else bar.low <= signal.target1
            val tp2HitNow = if (long) bar.high >= signal.target2 else bar.low <= signal.target2

            // Ambiguous candle path: adverse stop takes precedence before TP1.
            if (!tp1Hit && stopHit) {
                outcome = ReplayOutcome.STOP_BEFORE_TP1
                gross = -1.0
                exitTime = bar.openTime
                note = "Stop and/or target touched in same candle; conservative stop-first resolution."
                break
            }
            if (!tp1Hit && tp1HitNow) {
                tp1Hit = true
                gross += config.tp1PositionFraction * r1
                note = "TP1 partial realized; remaining position continues with original stop."
            }
            if (tp1Hit) {
                val remaining = 1.0 - config.tp1PositionFraction
                if (stopHit && tp2HitNow) {
                    // For the remaining size, the same-bar path is ambiguous: stop first.
                    gross -= remaining
                    outcome = ReplayOutcome.TP1_THEN_STOP
                    exitTime = bar.openTime
                    note = "TP1 realized; same-candle TP2/stop ambiguity resolved stop-first."
                    break
                }
                if (stopHit) {
                    gross -= remaining
                    outcome = ReplayOutcome.TP1_THEN_STOP
                    exitTime = bar.openTime
                    break
                }
                if (tp2HitNow) {
                    gross += remaining * r2
                    outcome = ReplayOutcome.TP2
                    exitTime = bar.openTime
                    break
                }
            }
        }

        if (!entered) {
            return ReplayTradeResult(signal.id, signal.symbol, signal.side, signal.score,
                ReplayOutcome.TIMEOUT_NO_TP, null, bars.last().openTime, bars.size, 0.0, 0.0,
                false, "Entry price was never touched within replay window.")
        }
        if (outcome == null) {
            val last = bars.last()
            val remaining = if (tp1Hit) 1.0 - config.tp1PositionFraction else 1.0
            val closeR = (if (long) last.close - signal.entry else signal.entry - last.close) / risk
            gross += remaining * closeR
            outcome = if (tp1Hit) ReplayOutcome.TP1_ONLY_TIMEOUT else ReplayOutcome.TIMEOUT_NO_TP
            exitTime = last.openTime
            note = if (tp1Hit) "TP1 partial realized; remaining position marked to timeout close." else "Position marked to timeout close."
        }

        val net = tradeIdeaNetReturnR(
            grossR = gross, entry = signal.entry, riskDistance = risk, heldBars = held,
            setupBarMinutes = config.setupBarMinutes, roundTripFeeBps = config.roundTripFeeBps,
            slippageBps = config.slippageBps, fundingBpsPer8h = config.fundingBpsPer8h
        )
        return ReplayTradeResult(signal.id, signal.symbol, signal.side, signal.score, outcome,
            entryTime, exitTime, held, gross, net, entered, note)
    }

    fun summarize(results: List<ReplayTradeResult>): ReplaySummary {
        val valid = results.filter { it.outcome != ReplayOutcome.INVALID && it.entryTriggered && it.netR.isFinite() }
        val wins = valid.filter { it.netR > 0.0 }
        val losses = valid.filter { it.netR < 0.0 }
        val grossWins = wins.sumOf { it.netR }
        val grossLosses = -losses.sumOf { it.netR }
        val pf = if (grossLosses > 0.0) grossWins / grossLosses else if (grossWins > 0.0) Double.POSITIVE_INFINITY else 0.0
        return ReplaySummary(
            totalSignals = results.size,
            enteredTrades = valid.size,
            skippedEntries = results.count { it.outcome != ReplayOutcome.INVALID && !it.entryTriggered },
            wins = wins.size,
            losses = losses.size,
            breakeven = valid.size - wins.size - losses.size,
            winRatePct = if (valid.isNotEmpty()) wins.size * 100.0 / valid.size else 0.0,
            expectancyNetR = if (valid.isNotEmpty()) valid.sumOf { it.netR } / valid.size else 0.0,
            profitFactor = pf,
            totalNetR = valid.sumOf { it.netR },
            averageWinR = if (wins.isNotEmpty()) wins.sumOf { it.netR } / wins.size else 0.0,
            averageLossR = if (losses.isNotEmpty()) losses.sumOf { it.netR } / losses.size else 0.0,
            long = summarizeSide(valid.filter { it.side == "LONG" }),
            short = summarizeSide(valid.filter { it.side == "SHORT" }),
            scoreBands = listOf(ScoreBand(0, 59), ScoreBand(60, 69), ScoreBand(70, 79), ScoreBand(80, 89), ScoreBand(90, 100))
                .map { band -> summarizeBand(band, valid.filter { it.score in band.minScore..band.maxScore }) }
        )
    }

    private fun summarizeSide(trades: List<ReplayTradeResult>): ReplaySideSummary = ReplaySideSummary(
        trades = trades.size,
        winRatePct = if (trades.isNotEmpty()) trades.count { it.netR > 0 } * 100.0 / trades.size else 0.0,
        expectancyNetR = if (trades.isNotEmpty()) trades.sumOf { it.netR } / trades.size else 0.0,
        totalNetR = trades.sumOf { it.netR }
    )

    private fun summarizeBand(band: ScoreBand, trades: List<ReplayTradeResult>) = ScoreBandSummary(
        band = band, trades = trades.size,
        winRatePct = if (trades.isNotEmpty()) trades.count { it.netR > 0 } * 100.0 / trades.size else 0.0,
        expectancyNetR = if (trades.isNotEmpty()) trades.sumOf { it.netR } / trades.size else 0.0,
        totalNetR = trades.sumOf { it.netR }
    )

    private fun isSignalValid(s: ReplaySignal): Boolean {
        if (s.side !in setOf("LONG", "SHORT") || s.score !in 0..100) return false
        if (listOf(s.entry, s.stop, s.target1, s.target2).any { !it.isFinite() || it <= 0.0 }) return false
        return if (s.side == "LONG") s.stop < s.entry && s.target1 > s.entry && s.target2 > s.target1
        else s.stop > s.entry && s.target1 < s.entry && s.target2 < s.target1
    }

    private fun invalid(s: ReplaySignal, note: String) = ReplayTradeResult(
        s.id, s.symbol, s.side, s.score, ReplayOutcome.INVALID, null, null, 0, 0.0, 0.0, false, note
    )

    private fun absDistance(a: Double, b: Double) = kotlin.math.abs(a - b)
}

data class ReplaySideSummary(val trades: Int, val winRatePct: Double, val expectancyNetR: Double, val totalNetR: Double)
data class ScoreBand(val minScore: Int, val maxScore: Int)
data class ScoreBandSummary(val band: ScoreBand, val trades: Int, val winRatePct: Double, val expectancyNetR: Double, val totalNetR: Double)

data class ReplaySummary(
    val totalSignals: Int,
    val enteredTrades: Int,
    val skippedEntries: Int,
    val wins: Int,
    val losses: Int,
    val breakeven: Int,
    val winRatePct: Double,
    val expectancyNetR: Double,
    val profitFactor: Double,
    val totalNetR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val long: ReplaySideSummary,
    val short: ReplaySideSummary,
    val scoreBands: List<ScoreBandSummary>
)
