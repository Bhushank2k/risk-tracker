package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ---------- Daily PNL ----------

/** One row of the Daily_PNL tab. [balance] is the opening balance for the day (USD). */
data class PnlEntry(val date: String, val balance: Double, val pnl: Double, val notes: String = "", val disciplineScore: Int = 0) {
    val wlPercent: Double get() = if (balance != 0.0) pnl / balance * 100 else 0.0
    val closing: Double get() = balance + pnl
}

data class PnlStats(
    val totalWin: Double,
    val totalLoss: Double,
    val net: Double,
    val winDays: Int,
    val lossDays: Int,
    val winRate: Double,
    val currentBalance: Double
)

/**
 * Finds the recorded daily result for a specific local calendar date.
 * Daily P&L entries have historically used d/M/yyyy; ISO dates are also accepted
 * for imported or restored records. Missing today's record must not trigger a
 * stale daily profit/loss warning.
 */
fun pnlEntryForDate(entries: List<PnlEntry>, date: Date = Date()): PnlEntry? {
    val dateKeys = listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd")
        .map { SimpleDateFormat(it, Locale.US).format(date) }
        .toSet()
    return entries.lastOrNull { it.date.trim() in dateKeys }
}

fun computeStats(entries: List<PnlEntry>): PnlStats {
    val win = entries.filter { it.pnl > 0 }.sumOf { it.pnl }
    val loss = entries.filter { it.pnl < 0 }.sumOf { it.pnl }
    val wd = entries.count { it.pnl > 0 }
    val ld = entries.count { it.pnl < 0 }
    val traded = wd + ld
    return PnlStats(
        totalWin = win,
        totalLoss = loss,
        net = win + loss,
        winDays = wd,
        lossDays = ld,
        winRate = if (traded > 0) wd * 100.0 / traded else 0.0,
        currentBalance = entries.lastOrNull()?.closing ?: 0.0
    )
}

// ---------- Position calculator ----------

data class CalcInput(
    val balance: Double = 50.0,
    val riskPercent: Double = 2.0,
    val entry: Double = 100.0,
    val stopLoss: Double = 98.0,
    val target: Double = 105.0,
    val leverage: Double = 10.0,
    val mmrPercent: Double = 0.5
)

data class SideResult(
    val valid: Boolean,
    val message: String?,
    val riskAmount: Double,
    val slDistancePercent: Double,
    val positionSize: Double,
    val tokens: Double,
    val margin: Double,
    val liqSimple: Double,
    val liqExact: Double,
    val tpPercent: Double,
    val profit: Double,
    val roiPercent: Double,
    val rr: Double
)

fun calcSide(i: CalcInput, long: Boolean): SideResult {
    val numericInputs = listOf(i.balance, i.riskPercent, i.entry, i.stopLoss, i.target, i.leverage, i.mmrPercent)
    if (numericInputs.any { !it.isFinite() }) {
        return SideResult(false, "Enter finite numeric values", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    val risk = i.balance * i.riskPercent / 100
    val slDist = if (i.entry > 0) abs(i.entry - i.stopLoss) / i.entry else 0.0
    val tpDist = if (i.entry > 0) abs(i.target - i.entry) / i.entry else 0.0
    val size = if (slDist > 0) risk / slDist else 0.0
    val tokens = if (i.entry > 0) size / i.entry else 0.0
    val margin = if (i.leverage > 0) size / i.leverage else 0.0
    val inv = if (i.leverage > 0) 1 / i.leverage else 0.0
    val mmr = i.mmrPercent / 100
    val liqSimple = if (long) i.entry * (1 - inv) else i.entry * (1 + inv)
    val liqExact = if (long) i.entry * (1 - inv + mmr) else i.entry * (1 + inv - mmr)
    val profit = size * tpDist
    val roi = if (margin > 0) profit / margin * 100 else 0.0
    val rr = if (risk > 0) profit / risk else 0.0
    val computedValues = listOf(
        risk, slDist, size, tokens, margin, liqSimple, liqExact, tpDist, profit,
        slDist * 100, tpDist * 100, roi, rr
    )
    if (computedValues.any { !it.isFinite() }) {
        return SideResult(false, "Calculated values exceed the supported numeric range",
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    val validDirection = if (long) i.stopLoss < i.entry && i.target > i.entry
    else i.stopLoss > i.entry && i.target < i.entry
    val slBeyondLiq = if (long) i.stopLoss <= liqExact else i.stopLoss >= liqExact
    val message = when {
        i.balance <= 0 || i.entry <= 0 -> "Enter a balance and entry price greater than zero"
        i.riskPercent <= 0 || i.riskPercent > 100 -> "Risk per trade must be greater than 0% and at most 100%"
        i.stopLoss <= 0 || i.target <= 0 -> "Stop loss and target prices must be greater than zero"
        i.leverage < 1 || i.leverage > 125 -> "Leverage must be between 1x and 125x"
        i.mmrPercent < 0 || i.mmrPercent >= 100 -> "Maintenance margin rate must be at least 0% and below 100%"
        !validDirection -> if (long) "Long needs stop loss below entry and target above entry"
        else "Short needs stop loss above entry and target below entry"
        slBeyondLiq -> "Stop loss is beyond the liquidation price"
        margin > i.balance -> "Required margin exceeds the available balance"
        else -> null
    }
    return SideResult(
        valid = message == null,
        message = message,
        riskAmount = risk,
        slDistancePercent = slDist * 100,
        positionSize = size,
        tokens = tokens,
        margin = margin,
        liqSimple = liqSimple,
        liqExact = liqExact,
        tpPercent = tpDist * 100,
        profit = profit,
        roiPercent = roi,
        rr = rr
    )
}

// ---------- Leverage matrix ----------

val LEVERAGES = listOf(1, 2, 3, 5, 10, 20, 25, 50, 75, 100)

data class LevRow(val lev: Int, val movePercent: Double, val longLiq: Double, val shortLiq: Double)

fun leverageMatrix(entry: Double): List<LevRow> = LEVERAGES.map {
    val move = 1.0 / it
    LevRow(it, move * 100, entry * (1 - move), entry * (1 + move))
}

// ---------- Trade journal & analytics ----------

data class TradeEntry(
    val id: String, val date: String, val symbol: String, val side: String,
    val entry: Double, val exit: Double, val quantity: Double,
    val fees: Double = 0.0, val notes: String = "",
    val strategy: String = "", val mistake: String = "", val riskUsd: Double = 0.0, val screenshotUri: String = ""
) {
    /** Estimated realized P&L after fees; not exchange-reported P&L. */
    val pnl: Double get() {
        val direction = when (side.lowercase(Locale.ROOT)) {
            "long" -> 1.0
            "short" -> -1.0
            else -> return Double.NaN
        }
        return ((exit - entry) * quantity * direction) - fees
    }
}

data class TradeStats(
    val count: Int, val wins: Int, val losses: Int, val netPnl: Double,
    val winRate: Double, val profitFactor: Double, val maxDrawdown: Double
)

fun computeTradeStats(trades: List<TradeEntry>): TradeStats {
    // Ignore malformed legacy/imported rows rather than letting NaN corrupt every metric.
    val ordered = trades.filter { it.pnl.isFinite() }.sortedBy { it.date }
    val wins = ordered.filter { it.pnl > 0 }
    val losses = ordered.filter { it.pnl < 0 }
    val grossWin = wins.sumOf { it.pnl }
    val grossLoss = -losses.sumOf { it.pnl }
    var equity = 0.0
    var peak = 0.0
    var maxDrawdown = 0.0
    ordered.forEach {
        equity += it.pnl
        if (equity > peak) peak = equity
        maxDrawdown = maxOf(maxDrawdown, peak - equity)
    }
    val decisive = wins.size + losses.size
    return TradeStats(
        count = ordered.size, wins = wins.size, losses = losses.size,
        netPnl = ordered.sumOf { it.pnl },
        winRate = if (decisive > 0) wins.size * 100.0 / decisive else 0.0,
        profitFactor = when {
            grossLoss > 0 -> grossWin / grossLoss
            grossWin > 0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        },
        maxDrawdown = maxDrawdown
    )
}

/**
 * Exports closed journal trades as RFC-4180-style CSV.
 * User-entered text is quoted and embedded quotes are doubled so commas,
 * quotes, and newlines do not corrupt adjacent columns.
 */
fun tradesToCsv(trades: List<TradeEntry>): String = buildString {
    append("Date,Symbol,Side,Entry,Exit,Quantity,Fees,Net PNL,Strategy,Mistake,Risk USD,Notes\n")
    trades.sortedBy { it.date }.forEach { trade ->
        fun textCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        append(textCell(trade.date)).append(',')
        append(textCell(trade.symbol)).append(',')
        append(textCell(trade.side)).append(',')
        append(trade.entry).append(',')
        append(trade.exit).append(',')
        append(trade.quantity).append(',')
        append(trade.fees).append(',')
        append(trade.pnl).append(',')
        append(textCell(trade.strategy)).append(',')
        append(textCell(trade.mistake)).append(',')
        append(trade.riskUsd).append(',')
        append(textCell(trade.notes)).append('\n')
    }
}

// ---------- Storage (SharedPreferences, JSON) ----------

class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("risk_tracker", Context.MODE_PRIVATE)

    var webUrl: String
        get() = prefs.getString("sync_url", "") ?: ""
        set(v) = prefs.edit().putString("sync_url", v).apply()

    var token: String
        get() = prefs.getString("sync_token", "") ?: ""
        set(v) = prefs.edit().putString("sync_token", v).apply()

    /** Number of entries known to be in the sheet. -1 means never synced. */
    var syncedCount: Int
        get() = prefs.getInt("synced_count", -1)
        set(v) = prefs.edit().putInt("synced_count", v).apply()

    var inr: Double
        get() = prefs.getString("inr", null)?.toDoubleOrNull() ?: INR_RATE
        set(v) = prefs.edit().putString("inr", v.toString()).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "System") ?: "System"
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    fun loadEntries(): List<PnlEntry> {
        val raw = prefs.getString("entries", null) ?: return seedEntries()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PnlEntry(o.getString("date"), o.getDouble("balance"), o.getDouble("pnl"), o.optString("notes", ""), o.optInt("disciplineScore", 0))
        }
    }

    fun saveEntries(list: List<PnlEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("date", it.date).put("balance", it.balance).put("pnl", it.pnl).put("notes", it.notes).put("disciplineScore", it.disciplineScore))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    fun loadTrades(): List<TradeEntry> {
        val raw = prefs.getString("trade_journal_v1", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                TradeEntry(
                    id = o.optString("id", "legacy-$idx"), date = o.getString("date"),
                    symbol = o.getString("symbol"), side = o.getString("side"),
                    entry = o.getDouble("entry"), exit = o.getDouble("exit"),
                    quantity = o.getDouble("quantity"), fees = o.optDouble("fees", 0.0),
                    notes = o.optString("notes", ""), strategy = o.optString("strategy", ""),
                    mistake = o.optString("mistake", ""), riskUsd = o.optDouble("riskUsd", 0.0),
                    screenshotUri = o.optString("screenshotUri", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveTrades(trades: List<TradeEntry>) {
        val arr = JSONArray()
        trades.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("date", t.date).put("symbol", t.symbol)
                .put("side", t.side).put("entry", t.entry).put("exit", t.exit)
                .put("quantity", t.quantity).put("fees", t.fees).put("notes", t.notes)
                .put("strategy", t.strategy).put("mistake", t.mistake).put("riskUsd", t.riskUsd).put("screenshotUri", t.screenshotUri))
        }
        prefs.edit().putString("trade_journal_v1", arr.toString()).apply()
    }

    var maxDailyLoss: Double
        get() = prefs.getString("max_daily_loss", "20")?.toDoubleOrNull() ?: 20.0
        set(v) { prefs.edit().putString("max_daily_loss", v.toString()).apply() }
    var maxTradeRisk: Double
        get() = prefs.getString("max_trade_risk", "5")?.toDoubleOrNull() ?: 5.0
        set(v) { prefs.edit().putString("max_trade_risk", v.toString()).apply() }
    var dailyProfitTarget: Double
        get() = prefs.getString("daily_profit_target", "10")?.toDoubleOrNull() ?: 10.0
        set(v) { prefs.edit().putString("daily_profit_target", v.toString()).apply() }

    fun exportBackup(): String {
        val root = JSONObject().put("format", "risk-tracker-backup-v1")
        val daily = JSONArray()
        loadEntries().forEach { daily.put(JSONObject().put("date",it.date).put("balance",it.balance).put("pnl",it.pnl).put("notes",it.notes).put("disciplineScore",it.disciplineScore)) }
        val journal = JSONArray()
        loadTrades().forEach { t -> journal.put(JSONObject().put("id",t.id).put("date",t.date).put("symbol",t.symbol).put("side",t.side).put("entry",t.entry).put("exit",t.exit).put("quantity",t.quantity).put("fees",t.fees).put("notes",t.notes).put("strategy",t.strategy).put("mistake",t.mistake).put("riskUsd",t.riskUsd).put("screenshotUri",t.screenshotUri)) }
        root.put("tradePlans", JSONArray(TradePlanStore(appContext).exportJson()))
        root.put("tradeReviews", JSONArray(TradeReviewStore(appContext).exportJson()))
        return root.put("dailyEntries",daily).put("trades",journal).put("maxDailyLoss",maxDailyLoss).put("maxTradeRisk",maxTradeRisk).put("dailyProfitTarget",dailyProfitTarget).toString(2)
    }

    fun importBackup(raw: String): Pair<List<PnlEntry>, List<TradeEntry>> {
        val root = JSONObject(raw)
        require(root.optString("format") == "risk-tracker-backup-v1") { "Unsupported backup format." }
        val a = root.getJSONArray("dailyEntries")
        val daily = (0 until a.length()).map { i -> a.getJSONObject(i).let { PnlEntry(it.getString("date"),it.getDouble("balance"),it.getDouble("pnl"),it.optString("notes",""),it.optInt("disciplineScore",0)) } }
        val b = root.getJSONArray("trades")
        val journal = (0 until b.length()).map { i -> b.getJSONObject(i).let { o -> TradeEntry(o.getString("id"),o.getString("date"),o.getString("symbol"),o.getString("side"),o.getDouble("entry"),o.getDouble("exit"),o.getDouble("quantity"),o.optDouble("fees",0.0),o.optString("notes",""),o.optString("strategy",""),o.optString("mistake",""),o.optDouble("riskUsd",0.0),o.optString("screenshotUri","")) } }
        saveEntries(daily); saveTrades(journal)
        if (root.has("tradePlans")) TradePlanStore(appContext).importJson(root.getJSONArray("tradePlans").toString())
        if (root.has("tradeReviews")) TradeReviewStore(appContext).importJson(root.getJSONArray("tradeReviews").toString())
        maxDailyLoss = root.optDouble("maxDailyLoss",maxDailyLoss); maxTradeRisk = root.optDouble("maxTradeRisk",maxTradeRisk); dailyProfitTarget = root.optDouble("dailyProfitTarget",dailyProfitTarget)
        return daily to journal
    }
    fun loadInput(): CalcInput {
        val d = CalcInput()
        return CalcInput(
            balance = prefs.getFloat("c_balance", d.balance.toFloat()).toDouble(),
            riskPercent = prefs.getFloat("c_risk", d.riskPercent.toFloat()).toDouble(),
            entry = prefs.getFloat("c_entry", d.entry.toFloat()).toDouble(),
            stopLoss = prefs.getFloat("c_sl", d.stopLoss.toFloat()).toDouble(),
            target = prefs.getFloat("c_tp", d.target.toFloat()).toDouble(),
            leverage = prefs.getFloat("c_lev", d.leverage.toFloat()).toDouble(),
            mmrPercent = prefs.getFloat("c_mmr", d.mmrPercent.toFloat()).toDouble()
        )
    }

    fun saveInput(i: CalcInput) {
        prefs.edit()
            .putFloat("c_balance", i.balance.toFloat())
            .putFloat("c_risk", i.riskPercent.toFloat())
            .putFloat("c_entry", i.entry.toFloat())
            .putFloat("c_sl", i.stopLoss.toFloat())
            .putFloat("c_tp", i.target.toFloat())
            .putFloat("c_lev", i.leverage.toFloat())
            .putFloat("c_mmr", i.mmrPercent.toFloat())
            .apply()
    }
}

fun entriesToCsv(list: List<PnlEntry>, inrRate: Double): String {
    val sb = StringBuilder("Sr,Date,Balance,DailyPNL,WL%,INR,INR PNL,INR Closing,DisciplineScore,Notes\n")
    fun textCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    list.forEachIndexed { idx, e ->
        val inr = e.balance * inrRate
        val inrPnl = e.pnl * inrRate
        sb.append(idx + 1).append(',').append(textCell(e.date)).append(',')
            .append(String.format(Locale.US, "%.2f", e.balance)).append(',')
            .append(String.format(Locale.US, "%.2f", e.pnl)).append(',')
            .append(String.format(Locale.US, "%.2f", e.wlPercent)).append("%,")
            .append(inr.toLong()).append(',').append(inrPnl.toLong()).append(',')
            .append((inr + inrPnl).toLong()).append(',').append(e.disciplineScore).append(',')
            .append(textCell(e.notes)).append('\n')
    }
    return sb.toString()
}
const val INR_RATE = 94.74

/** USD->INR rate; replaced by the sheet's rate (cell G3) on every sync. */
var inrRate = INR_RATE

/** The 21 days already in the Google Sheet (Daily_PNL tab, 6 Jun - 26 Jun 2026). */
fun seedEntries(): List<PnlEntry> {
    val rows = listOf(
        Triple("6/6/2026", 47.76, 1.71), Triple("7/6/2026", 49.47, 0.00),
        Triple("8/6/2026", 49.47, -1.35), Triple("9/6/2026", 48.12, 2.47),
        Triple("10/6/2026", 50.59, 1.54), Triple("11/6/2026", 52.13, -1.45),
        Triple("12/6/2026", 50.68, 2.11), Triple("13/6/2026", 52.79, -2.34),
        Triple("14/6/2026", 50.45, 4.70), Triple("15/6/2026", 55.15, 4.60),
        Triple("16/6/2026", 59.75, -0.86), Triple("17/6/2026", 58.89, -0.54),
        Triple("18/6/2026", 58.35, -8.12), Triple("19/6/2026", 50.23, -0.34),
        Triple("20/6/2026", 49.89, 3.35), Triple("21/6/2026", 101.32, -11.49),
        Triple("22/6/2026", 89.83, 5.99), Triple("23/6/2026", 95.82, 9.33),
        Triple("24/6/2026", 105.15, 2.72), Triple("25/6/2026", 107.87, 12.63),
        Triple("26/6/2026", 120.50, 6.32)
    )
    return rows.map { PnlEntry(it.first, it.second, it.third) }
}
