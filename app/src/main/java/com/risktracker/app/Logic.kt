package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// ---------- Daily PNL ----------

/** One row of the Daily_PNL tab. [balance] is the opening balance for the day (USD). */
data class PnlEntry(val date: String, val balance: Double, val pnl: Double) {
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

    val validDirection = if (long) i.stopLoss < i.entry && i.target > i.entry
    else i.stopLoss > i.entry && i.target < i.entry
    val slBeyondLiq = if (long) i.stopLoss <= liqExact else i.stopLoss >= liqExact
    val message = when {
        i.entry <= 0 || i.balance <= 0 -> "Enter a balance and entry price"
        !validDirection -> if (long) "Long needs stop loss below entry and target above entry"
        else "Short needs stop loss above entry and target below entry"
        slBeyondLiq -> "Stop loss is beyond the liquidation price"
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
        roiPercent = if (margin > 0) profit / margin * 100 else 0.0,
        rr = if (risk > 0) profit / risk else 0.0
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
    val fees: Double = 0.0, val notes: String = ""
) {
    /** Estimated realized P&L after fees; not exchange-reported P&L. */
    val pnl: Double get() = ((exit - entry) * quantity *
        (if (side.equals("Long", true)) 1.0 else -1.0)) - fees
}

data class TradeStats(
    val count: Int, val wins: Int, val losses: Int, val netPnl: Double,
    val winRate: Double, val profitFactor: Double, val maxDrawdown: Double
)

fun computeTradeStats(trades: List<TradeEntry>): TradeStats {
    val ordered = trades.sortedBy { it.date }
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
        count = trades.size, wins = wins.size, losses = losses.size,
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

// ---------- Storage (SharedPreferences, JSON) ----------

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("risk_tracker", Context.MODE_PRIVATE)

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

    fun loadEntries(): List<PnlEntry> {
        val raw = prefs.getString("entries", null) ?: return seedEntries()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PnlEntry(o.getString("date"), o.getDouble("balance"), o.getDouble("pnl"))
        }
    }

    fun saveEntries(list: List<PnlEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("date", it.date).put("balance", it.balance).put("pnl", it.pnl))
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
                    notes = o.optString("notes", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveTrades(trades: List<TradeEntry>) {
        val arr = JSONArray()
        trades.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("date", t.date).put("symbol", t.symbol)
                .put("side", t.side).put("entry", t.entry).put("exit", t.exit)
                .put("quantity", t.quantity).put("fees", t.fees).put("notes", t.notes))
        }
        prefs.edit().putString("trade_journal_v1", arr.toString()).apply()
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
    val sb = StringBuilder("Sr,Date,Balance,DailyPNL,WL%,INR,INR PNL,INR Closing\n")
    list.forEachIndexed { idx, e ->
        val inr = e.balance * inrRate
        val inrPnl = e.pnl * inrRate
        sb.append("${idx + 1},${e.date},${"%.2f".format(e.balance)},${"%.2f".format(e.pnl)},")
            .append("${"%.2f".format(e.wlPercent)}%,${inr.toLong()},${inrPnl.toLong()},${(inr + inrPnl).toLong()}\n")
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
