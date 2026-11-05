package com.risktracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class ScanTicker(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double)
private data class ScanCandle(val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long)
internal data class TradeIdea(
    val symbol: String, val side: String, val score: Int, val volume: Double, val change: Double,
    val last: Double, val entry: Double, val stop: Double, val target1: Double, val target2: Double,
    val entryTriggered: Boolean,
    val rsi: Double, val volumeRatio: Double, val bias4h: String, val bias1h: String,
    val setup: String, val reasons: List<String>, val warning: String, val horizon: String,
    val bias5m: String, val spreadBps: Double?, val bookImbalance: Double?,
    val fundingRate: Double?, val oiChangePct: Double?, val backtest: TradeIdeaBacktestStats
)
private data class MarketMicrostructure(
    val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double?, val oiChangePct: Double?
)

internal object TradeIdeaCache {
    private const val PREFS = "market_scan_cache_v1"
    private const val KEY = "latest_scan"

    fun load(context: android.content.Context): Pair<Long, List<TradeIdea>>? = runCatching {
        val root = JSONObject(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null) ?: return null)
        val timestamp = root.optLong("updatedAt", 0L)
        val rows = root.optJSONArray("ideas") ?: return null
        val ideas = (0 until rows.length()).mapNotNull { i ->
            val x = rows.optJSONObject(i) ?: return@mapNotNull null
            val reasonsJson = x.optJSONArray("reasons") ?: JSONArray()
            val reasons = (0 until reasonsJson.length()).map { reasonsJson.optString(it) }
            val stats = x.optJSONObject("backtest") ?: JSONObject()
            TradeIdea(
                symbol = x.getString("symbol"), side = x.getString("side"), score = x.getInt("score"),
                volume = x.getDouble("volume"), change = x.getDouble("change"), last = x.getDouble("last"),
                entry = x.getDouble("entry"), stop = x.getDouble("stop"), target1 = x.getDouble("target1"),
                target2 = x.getDouble("target2"), entryTriggered = x.getBoolean("entryTriggered"),
                rsi = x.getDouble("rsi"), volumeRatio = x.getDouble("volumeRatio"),
                bias4h = x.getString("bias4h"), bias1h = x.getString("bias1h"), setup = x.getString("setup"),
                reasons = reasons, warning = x.getString("warning"), horizon = x.getString("horizon"),
                bias5m = x.getString("bias5m"),
                spreadBps = x.optNullableDouble("spreadBps"), bookImbalance = x.optNullableDouble("bookImbalance"),
                fundingRate = x.optNullableDouble("fundingRate"), oiChangePct = x.optNullableDouble("oiChangePct"),
                backtest = TradeIdeaBacktestStats(stats.optInt("trades"), stats.optInt("wins"), stats.optDouble("averageR"))
            )
        }
        if (timestamp <= 0L || ideas.isEmpty()) null else timestamp to ideas
    }.getOrNull()

    fun save(context: android.content.Context, updatedAt: Long, ideas: List<TradeIdea>) {
        runCatching {
            val rows = JSONArray()
            ideas.forEach { idea ->
                val reasons = JSONArray().apply { idea.reasons.forEach { put(it) } }
                val stats = JSONObject().put("trades", idea.backtest.trades)
                    .put("wins", idea.backtest.wins).put("averageR", idea.backtest.averageR)
                rows.put(JSONObject()
                    .put("symbol", idea.symbol).put("side", idea.side).put("score", idea.score)
                    .put("volume", idea.volume).put("change", idea.change).put("last", idea.last)
                    .put("entry", idea.entry).put("stop", idea.stop).put("target1", idea.target1)
                    .put("target2", idea.target2).put("entryTriggered", idea.entryTriggered)
                    .put("rsi", idea.rsi).put("volumeRatio", idea.volumeRatio)
                    .put("bias4h", idea.bias4h).put("bias1h", idea.bias1h).put("setup", idea.setup)
                    .put("reasons", reasons).put("warning", idea.warning).put("horizon", idea.horizon)
                    .put("bias5m", idea.bias5m)
                    .put("spreadBps", idea.spreadBps ?: JSONObject.NULL)
                    .put("bookImbalance", idea.bookImbalance ?: JSONObject.NULL)
                    .put("fundingRate", idea.fundingRate ?: JSONObject.NULL)
                    .put("oiChangePct", idea.oiChangePct ?: JSONObject.NULL)
                    .put("backtest", stats))
            }
            val root = JSONObject().put("updatedAt", updatedAt).put("ideas", rows)
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY, root.toString()).apply()
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }
}

internal object BinanceMarketScanner {
    private const val BASE = "https://fapi.binance.com"

    fun latestPrice(symbol: String): Double? = runCatching {
        val row = JSONObject(get("/fapi/v1/ticker/price?symbol=" + symbol))
        row.optString("price").toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    }.getOrNull()

    private fun get(path: String): String {
        BinanceRestGuard.beforeRequest()
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 16000
        c.requestMethod = "GET"
        return try {
            val code = c.responseCode
            val body = readBinanceBody(if (code in 200..299) c.inputStream else c.errorStream)
            BinanceRestGuard.recordResponse(code, body, c.getHeaderField("Retry-After"))
            if (code !in 200..299) {
                val message = binanceMessage(body) ?: "no message returned"
                throw IllegalStateException("Binance HTTP " + code + ": " + message)
            }
            body
        } finally { c.disconnect() }
    }

    private fun candles(symbol: String, interval: String, limit: Int): List<ScanCandle> {
        val raw = JSONArray(get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit))
        val now = System.currentTimeMillis()
        return (0 until raw.length()).mapNotNull { i ->
            val a = raw.optJSONArray(i) ?: return@mapNotNull null
            runCatching { ScanCandle(a.getString(2).toDouble(), a.getString(3).toDouble(),
                a.getString(4).toDouble(), a.getString(5).toDouble(), a.getLong(6)) }.getOrNull()
        }.filter { it.closeTime < now && it.close > 0.0 }
    }

    private fun ema(v: List<Double>, p: Int): Double? {
        if (v.size < p) return null
        val alpha = 2.0 / (p + 1.0)
        var e = v.take(p).average()
        v.drop(p).forEach { e = it * alpha + e * (1.0 - alpha) }
        return e.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rsi(v: List<Double>): Double? {
        if (v.size < 15) return null
        val changes = v.zipWithNext { a, b -> b - a }.takeLast(14)
        val gain = changes.filter { it > 0 }.sum() / 14.0
        val loss = -changes.filter { it < 0 }.sum() / 14.0
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }

    private fun atr(rows: List<ScanCandle>): Double? {
        if (rows.size < 16) return null
        val ranges = rows.zipWithNext { p, c -> maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close)) }.takeLast(14)
        return ranges.average().takeIf { it > 0.0 && it.isFinite() }
    }

    private fun vwap(rows: List<ScanCandle>): Double? {
        val s = rows.takeLast(24)
        val volume = s.sumOf { it.volume }
        return if (volume <= 0) null else s.sumOf { it.close * it.volume } / volume
    }

    private suspend fun microstructure(symbol: String): MarketMicrostructure = coroutineScope {
        val bookTask = async(Dispatchers.IO) {
            runCatching { JSONObject(get("/fapi/v1/depth?symbol=$symbol&limit=20")) }.getOrNull()
        }
        val fundingTask = async(Dispatchers.IO) {
            runCatching {
                JSONObject(get("/fapi/v1/premiumIndex?symbol=$symbol"))
                    .optString("lastFundingRate").toDoubleOrNull()
            }.getOrNull()
        }
        val oiTask = async(Dispatchers.IO) {
            runCatching {
                val rows = JSONArray(get("/futures/data/openInterestHist?symbol=$symbol&period=15m&limit=2"))
                if (rows.length() < 2) null else {
                    val old = rows.getJSONObject(0).optString("sumOpenInterest").toDoubleOrNull() ?: 0.0
                    val latest = rows.getJSONObject(rows.length() - 1).optString("sumOpenInterest").toDoubleOrNull() ?: 0.0
                    if (old <= 0.0) null else (latest / old - 1.0) * 100.0
                }
            }.getOrNull()
        }
        val bookData = bookTask.await()
        val bids = bookData?.optJSONArray("bids") ?: JSONArray()
        val asks = bookData?.optJSONArray("asks") ?: JSONArray()
        val bid = bids.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
        val ask = asks.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
        val spread = if (bid > 0.0 && ask > bid) ((ask - bid) / ((ask + bid) / 2.0)) * 10_000.0 else null
        fun notional(rows: JSONArray): Double = (0 until rows.length()).sumOf { i ->
            val row = rows.optJSONArray(i) ?: return@sumOf 0.0
            (row.optString(0).toDoubleOrNull() ?: 0.0) * (row.optString(1).toDoubleOrNull() ?: 0.0)
        }
        val bidValue = notional(bids)
        val askValue = notional(asks)
        val imbalance = if (bidValue + askValue <= 0.0) null else (bidValue - askValue) / (bidValue + askValue)
        MarketMicrostructure(spread, imbalance, fundingTask.await(), oiTask.await())
    }

    suspend fun scan(context: android.content.Context, progress: (String) -> Unit): List<TradeIdea> = withContext(Dispatchers.IO) {
        BinanceRestGuard.configure(context)
        progress("Loading active USDT perpetual contracts…")
        val info = JSONObject(get("/fapi/v1/exchangeInfo")).optJSONArray("symbols") ?: JSONArray()
        val allowed = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter {
            it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" && it.optString("quoteAsset") == "USDT"
        }.map { it.optString("symbol") }.toSet()
        progress("Ranking eligible contracts by 24-hour traded value…")
        val all = JSONArray(get("/fapi/v1/ticker/24hr"))
        val candidates = (0 until all.length()).mapNotNull { i ->
            val x = all.optJSONObject(i) ?: return@mapNotNull null
            val symbol = x.optString("symbol")
            val quote = x.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            val last = x.optString("lastPrice").toDoubleOrNull() ?: 0.0
            if (symbol !in allowed || quote < 5_000_000.0 || last <= 0.0) null
            else ScanTicker(symbol, quote, x.optString("priceChangePercent").toDoubleOrNull() ?: 0.0, last)
        }.sortedByDescending { it.quoteVolume }
        if (candidates.isEmpty()) throw IllegalStateException("No liquid USDT perpetual pairs were returned.")
        // Keep scans bounded: use REST for historical snapshots, WebSockets for live prices.
        val scanCandidates = candidates.take(12)
        val microstructureSymbols = scanCandidates.take(4).map { it.symbol }.toSet()
        val gate = Semaphore(2)
        progress("Checking the top " + scanCandidates.size + " liquid USDT perpetual pairs (rate-limited REST snapshot)…")
        // Keep the first failure of each pair so a total failure reports its real cause
        // (a rate-limit pause, a regional block, a timeout) instead of an empty result.
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val results = coroutineScope {
            scanCandidates.map { ticker -> async {
                gate.withPermit {
                    runCatching {
                        coroutineScope {
                            val h4Task = async(Dispatchers.IO) { candles(ticker.symbol, "4h", 200) }
                            val h1Task = async(Dispatchers.IO) { candles(ticker.symbol, "1h", 200) }
                            val m15Task = async(Dispatchers.IO) { candles(ticker.symbol, "15m", 200) }
                            val m5Task = async(Dispatchers.IO) { candles(ticker.symbol, "5m", 90) }
                            val microTask = async {
                                if (ticker.symbol in microstructureSymbols) microstructure(ticker.symbol)
                                else MarketMicrostructure(null, null, null, null)
                            }
                            evaluate(ticker, h4Task.await(), h1Task.await(), m15Task.await(), m5Task.await(), microTask.await())
                        }
                    }.onFailure { failures.add(it) }.getOrNull()
                }
            } }.awaitAll().filterNotNull().sortedByDescending { it.score }
        }
        if (results.isEmpty() && failures.size == scanCandidates.size) {
            throw IllegalStateException("Could not load Binance market data: " + describeBinanceError(failures.first()))
        }
        results
    }

    private fun evaluate(t: ScanTicker, h4: List<ScanCandle>, h1: List<ScanCandle>, m: List<ScanCandle>, m5: List<ScanCandle>, micro: MarketMicrostructure): TradeIdea? {
        if (h4.size < 55 || h1.size < 25 || m.size < 80 || m5.size < 25) return null
        val c4 = h4.map { it.close }; val c1 = h1.map { it.close }; val cm = m.map { it.close }
        val e4f = ema(c4, 9) ?: return null; val e4s = ema(c4, 21) ?: return null; val e4b = ema(c4, 50) ?: return null
        val e1f = ema(c1, 9) ?: return null; val e1s = ema(c1, 21) ?: return null
        val emf = ema(cm, 9) ?: return null; val ems = ema(cm, 21) ?: return null
        val c5 = m5.map { it.close }
        val e5f = ema(c5, 9) ?: return null; val e5s = ema(c5, 21) ?: return null
        val vwap = vwap(m) ?: return null; val rsi = rsi(cm) ?: return null; val atr = atr(m) ?: return null
        val last = m.last()
        val averageVolume = m.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (averageVolume > 0) last.volume / averageVolume else 0.0
        val bull4 = h4.last().close > e4b && e4f > e4s && e4s > e4b
        val bear4 = h4.last().close < e4b && e4f < e4s && e4s < e4b
        val bull1 = h1.last().close > e1s && e1f > e1s
        val bear1 = h1.last().close < e1s && e1f < e1s
        val bull15 = last.close > vwap && emf > ems
        val bear15 = last.close < vwap && emf < ems
        val bull5 = m5.last().close > e5f && e5f > e5s
        val bear5 = m5.last().close < e5f && e5f < e5s
        val spreadOkay = micro.spreadBps == null || micro.spreadBps <= 12.0
        val longBookOkay = micro.bookImbalance == null || micro.bookImbalance > -0.45
        val shortBookOkay = micro.bookImbalance == null || micro.bookImbalance < 0.45
        val side = when {
            bull4 && bull1 && bull15 && bull5 && spreadOkay && longBookOkay -> "LONG"
            bear4 && bear1 && bear15 && bear5 && spreadOkay && shortBookOkay -> "SHORT"
            else -> "WAIT"
        }
        val reasons = mutableListOf<String>()
        if (side == "WAIT") {
            reasons.add("4H, 1H, 15M and 5M trend conditions must all align")
            if (!spreadOkay) reasons.add("Order-book spread is wider than the 12 bps filter")
            if (!longBookOkay && !shortBookOkay) reasons.add("Order-book depth is unusually one-sided")
            if (volumeRatio < 1.0) reasons.add("15M volume is below its recent average")
        } else {
            reasons.add("4H, 1H, 15M and 5M trend direction agree")
            reasons.add(if (side == "LONG") "Price is above 15M VWAP" else "Price is below 15M VWAP")
            if (volumeRatio >= 1.2) reasons.add("15M volume is " + "%.2f".format(Locale.US, volumeRatio) + "× its prior-20-bar average")
            micro.spreadBps?.let { reasons.add("Order-book spread " + "%.1f".format(Locale.US, it) + " bps") }
            micro.bookImbalance?.let { reasons.add("Top-20 order-book imbalance " + "%.2f".format(Locale.US, it)) }
            micro.oiChangePct?.let { reasons.add("Open interest changed " + "%+.2f".format(Locale.US, it) + "% over the latest 15M samples") }
        }
        var score = if (side == "WAIT") 15 else 50
        if (side != "WAIT") {
            score += if (volumeRatio >= 1.5) 15 else if (volumeRatio >= 1.2) 10 else if (volumeRatio >= 0.8) 5 else 0
            val rsiOkay = if (side == "LONG") rsi in 42.0..68.0 else rsi in 32.0..58.0
            if (rsiOkay) score += 8
            score += if (t.quoteVolume >= 100_000_000) 8 else if (t.quoteVolume >= 20_000_000) 6 else 3
            if (micro.spreadBps != null && micro.spreadBps <= 5.0) score += 5
            if (micro.bookImbalance != null && (if (side == "LONG") micro.bookImbalance > 0.10 else micro.bookImbalance < -0.10)) score += 5
            if (micro.oiChangePct != null && micro.oiChangePct > 0.5) score += 5
            if (micro.fundingRate != null && abs(micro.fundingRate) <= 0.0005) score += 4
            if (t.changePct * (if (side == "LONG") 1 else -1) > 0) score += 3
        } else if (volumeRatio >= 1.2) score += 5
        score = score.coerceIn(0, 100)
        val trigger = if (side == "LONG") m.dropLast(1).takeLast(5).maxOf { it.high }
            else if (side == "SHORT") m.dropLast(1).takeLast(5).minOf { it.low } else t.last
        val previousClose = m[m.lastIndex - 1].close
        val entryTriggered = when (side) {
            "LONG" -> last.close > trigger && previousClose <= trigger && volumeRatio >= 1.2
            "SHORT" -> last.close < trigger && previousClose >= trigger && volumeRatio >= 1.2
            else -> false
        }
        val entry = if (side == "LONG" || side == "SHORT") trigger else t.last
        val distance = atr * 1.5
        val stop = if (side == "LONG") entry - distance else if (side == "SHORT") entry + distance else 0.0
        val sign = if (side == "LONG") 1.0 else -1.0
        val t1 = if (side == "WAIT") 0.0 else entry + sign * distance * 1.5
        val t2 = if (side == "WAIT") 0.0 else entry + sign * distance * 2.5
        val warning = when {
            side == "WAIT" -> "No trade. Wait for alignment and a 15M close through the trigger with volume."
            side == "LONG" && rsi > 70 -> "RSI is overbought; do not chase. Wait for a pullback."
            side == "SHORT" && rsi < 30 -> "RSI is oversold; do not chase. Wait for a bounce."
            micro.fundingRate != null && side == "LONG" && micro.fundingRate > 0.001 -> "Positive funding is elevated; long positioning may be crowded. Reduce risk or skip."
            micro.fundingRate != null && side == "SHORT" && micro.fundingRate < -0.001 -> "Negative funding is elevated; short positioning may be crowded. Reduce risk or skip."
            micro.oiChangePct != null && micro.oiChangePct < -1.0 -> "Open interest is falling sharply; breakout participation may be weak."
            volumeRatio < 0.8 -> "Volume confirmation is weak. Keep this on watch until volume expands."
            else -> "Conditional only: wait for a fresh 15M close through the trigger. Check live spread, funding and slippage."
        }
        val horizon = if ((bull4 || bear4) && (bull1 || bear1)) "Swing bias" else "Intraday / scalp"
        val replay = TradeIdeaBacktest.run(
            h4.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) },
            h1.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) },
            m.map { BacktestCandle(it.high, it.low, it.close, it.volume, it.closeTime) },
            side.takeIf { it == "LONG" || it == "SHORT" }
        )
        return TradeIdea(t.symbol, side, score, t.quoteVolume, t.changePct, t.last, entry, stop, t1, t2, entryTriggered,
            rsi, volumeRatio, if (bull4) "Bullish" else if (bear4) "Bearish" else "Mixed",
            if (bull1) "Bullish" else if (bear1) "Bearish" else "Mixed",
            if (side == "WAIT") "Trend alignment watch" else if (volumeRatio >= 1.2) "Trend continuation with volume" else "Trend pullback",
            reasons, warning, horizon, if (bull5) "Bullish" else if (bear5) "Bearish" else "Mixed",
            micro.spreadBps, micro.bookImbalance, micro.fundingRate, micro.oiChangePct, replay)
    }
}

@Composable
fun DailyTradeIdeasScreen() {
    val context = LocalContext.current
    val cachedScan = remember { TradeIdeaCache.load(context) }
    val trackingPrefs = remember { context.getSharedPreferences("trade_idea_tracking_v1", android.content.Context.MODE_PRIVATE) }
    fun readTrackedSymbols(): Set<String> = trackingPrefs.all.keys
        .filter { it.startsWith("entry_") && (trackingPrefs.all[it] as? Float ?: 0f) > 0f }
        .map { it.removePrefix("entry_") }.toSet()
    var ideas by remember { mutableStateOf(cachedScan?.second ?: emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (cachedScan != null) "Showing saved scan while fresh Binance data loads…" else "Refresh to scan live Binance USDT-M perpetual markets.") }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(cachedScan?.first) }
    var showingCached by remember { mutableStateOf(cachedScan != null) }
    var filter by remember { mutableStateOf("All") }
    var density by remember { mutableStateOf("Compact") }
    var scanExpanded by remember { mutableStateOf(false) }
    var trackedSymbols by remember { mutableStateOf(readTrackedSymbols()) }
    var equityInput by remember { mutableStateOf("") }
    var paperStats by remember { mutableStateOf(TradeIdeaPaperJournal.stats(context)) }
    var alertsEnabled by remember { mutableStateOf(TradeIdeaNotifications.isEnabled(context)) }
    val scope = rememberCoroutineScope()
    fun enableTradeAlerts() {
        TradeIdeaNotifications.setEnabled(context, true)
        TradeIdeaNotifications.schedule(context)
        alertsEnabled = true
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableTradeAlerts()
    }
    fun refresh() {
        if (loading) return
        loading = true
        error = null
        status = "Connecting to Binance public market data…"
        scope.launch {
            try {
                val freshIdeas = BinanceMarketScanner.scan(context) { status = it }
                val scanTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) { TradeIdeaCache.save(context, scanTime, freshIdeas) }
                ideas = freshIdeas
                updatedAt = scanTime
                showingCached = false
                trackedSymbols = readTrackedSymbols()
                paperStats = TradeIdeaPaperJournal.stats(context)
                status = "Scan complete · " + freshIdeas.size + " pairs analysed."
                if (freshIdeas.isEmpty()) error = "No complete readings returned. Retry the scan."
            } catch (e: Exception) {
                error = e.message ?: "Market scan failed. Check your connection and retry."
                status = "Scan could not be completed."
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) {
        paperStats = TradeIdeaPaperJournal.stats(context)
        refresh()
    }
    val rankedIdeas = ideas.sortedWith(
        compareBy<TradeIdea> {
            when (tradeIdeaStage(it, it.symbol in trackedSymbols)) {
                TradeIdeaStage.ENTRY_CONFIRMED -> 0
                TradeIdeaStage.TRACKING -> 1
                TradeIdeaStage.MONITOR -> 2
                TradeIdeaStage.WAIT_CONFIRMATION -> 3
                TradeIdeaStage.DO_NOT_ENTER -> 4
            }
        }.thenByDescending { it.score }
    )
    val visible = rankedIdeas.filter { idea ->
        val stage = tradeIdeaStage(idea, idea.symbol in trackedSymbols)
        when (filter) {
            "Ready" -> stage == TradeIdeaStage.ENTRY_CONFIRMED
            "Monitor" -> stage == TradeIdeaStage.MONITOR
            "Waiting" -> stage == TradeIdeaStage.WAIT_CONFIRMATION
            "Tracking" -> stage == TradeIdeaStage.TRACKING
            "Do not enter" -> stage == TradeIdeaStage.DO_NOT_ENTER
            "Scalp" -> idea.horizon == "Intraday / scalp"
            "Swing" -> idea.horizon == "Swing bias"
            "Long" -> idea.side == "LONG"
            "Short" -> idea.side == "SHORT"
            else -> true
        }
    }
    val trackedVisible = visible.filter { it.symbol in trackedSymbols }
    val opportunityVisible = visible.filterNot { it.symbol in trackedSymbols }
    val readyCount = ideas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.ENTRY_CONFIRMED }
    val monitorCount = ideas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.MONITOR }
    val blockedCount = ideas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.DO_NOT_ENTER }
    val trackedCount = ideas.count { it.symbol in trackedSymbols }
    val longCount = ideas.count { it.side == "LONG" }
    val shortCount = ideas.count { it.side == "SHORT" }
    val waitCount = ideas.count { it.side == "WAIT" }
    val timeLabel = updatedAt?.let {
        SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(it)) + " IST"
    } ?: "Not scanned yet"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("MARKET OPPORTUNITIES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("Daily trade ideas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Confirmation-led setups across liquid USDT perpetual markets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showingCached) "Saved snapshot" else status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannerStat("Ready", readyCount.toString(), Modifier.weight(1f))
                if (monitorCount > 0) ScannerStat("Monitor", monitorCount.toString(), Modifier.weight(1f))
                if (trackedCount > 0) ScannerStat("Tracking", trackedCount.toString(), Modifier.weight(1f))
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Market scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(longCount.toString() + " long · " + shortCount.toString() + " short · " + waitCount.toString() + " no direction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Scanning…" else "Refresh") }
                            TextButton(onClick = { scanExpanded = !scanExpanded }) { Text(if (scanExpanded) "Hide" else "Details") }
                        }
                    }
                    if (scanExpanded) {
                    Text("Full liquid-market scan · Binance Futures public data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScannerStat("LONG", longCount.toString(), Modifier.weight(1f))
                        ScannerStat("SHORT", shortCount.toString(), Modifier.weight(1f))
                        ScannerStat("No direction", waitCount.toString(), Modifier.weight(1f))
                    }
                    Text("Directional mix of scanned ideas — not a market-wide trend prediction.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Entry & exit notifications", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("Alerts are local notifications; they do not place orders.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = alertsEnabled, onCheckedChange = { enabled ->
                            if (!enabled) {
                                TradeIdeaNotifications.setEnabled(context, false)
                                TradeIdeaNotifications.cancel(context)
                                alertsEnabled = false
                            } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else enableTradeAlerts()
                        })
                    }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { TradeSessionTimingCard() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Opportunities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(readyCount.toString() + " ready · " + blockedCount + " do not enter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Ready", "Monitor", "Waiting", "Tracking", "Do not enter", "Scalp", "Swing", "Long", "Short").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("View", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Compact", "Standard", "Expanded").forEach { option ->
                            FilterChip(selected = density == option, onClick = { density = option }, label = { Text(option, maxLines = 1, softWrap = false) })
                        }
                    }
                }
            }
        }
        if (trackedVisible.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Active trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(trackedVisible.size.toString() + " tracking", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(trackedVisible, key = { "tracked_" + it.symbol }) { idea ->
                TradeIdeaCard(idea, density) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
            }
        }
        if (opportunityVisible.isNotEmpty()) {
            item { Text(if (filter == "All") "Best opportunities" else filter + " ideas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(opportunityVisible, key = { "idea_" + it.symbol }) { idea ->
                TradeIdeaCard(idea, density) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
            }
        } else if (trackedVisible.isEmpty()) {
            item {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (ideas.isEmpty()) "No scan results yet" else "No ideas in this filter", fontWeight = FontWeight.SemiBold)
                        Text(if (ideas.isEmpty()) "Refresh the market scan to load current setups." else "Try another state or timeframe filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Position size calculator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val sizingIdea = ideas.firstOrNull { it.side != "WAIT" && tradeIdeaRiskValid(it) && it.entryTriggered && it.symbol !in trackedSymbols }
                    val sizing = sizingIdea?.let { TradeSizing.calculate(equityInput.toDoubleOrNull() ?: 0.0, it.entry, it.stop) }
                    OutlinedTextField(value = equityInput, onValueChange = { equityInput = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Account equity (USDT)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (sizingIdea == null) {
                        Text("A confirmed, risk-valid setup is required before quantity can be estimated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (sizing == null) {
                        Text("Enter valid equity to calculate quantity for " + sizingIdea.symbol + ".", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(sizingIdea.symbol + " · " + sizingIdea.side + " · 0.5% risk", fontWeight = FontWeight.SemiBold)
                        ScannerMetric("Suggested quantity", "%.6f".format(Locale.US, sizing.quantity))
                        ScannerMetric("Notional value", "$" + "%,.2f".format(Locale.US, sizing.notional))
                        ScannerMetric("Approx. margin at 3×", "$" + "%,.2f".format(Locale.US, sizing.marginAtMaxLeverage))
                        ScannerMetric("Planned loss at stop", "$" + "%,.2f".format(Locale.US, sizing.riskAmount) + " (" + "%.2f".format(Locale.US, sizing.actualRiskPercent) + "%)")
                        Text("Estimate excludes fees, funding, slippage and exchange quantity/step-size filters. Recheck before placing an order.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (paperStats.isNotEmpty()) {
            item {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Performance validation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Paper-trade results are historical observations, not a forecast.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        paperStats.forEach { stat ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stat.setup, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                ScannerMetric("Completed samples", stat.closedTrades.toString() + " / 50")
                                ScannerMetric("Win rate", "%.1f%%".format(Locale.US, stat.winRatePercent))
                                ScannerMetric("Average result", "%+.2fR".format(Locale.US, stat.averageR))
                                LinearProgressIndicator(progress = { (stat.closedTrades / 50f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerStat(label: String, value: String, modifier: Modifier = Modifier) {
    RiskCard(modifier) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

private enum class TradeIdeaStage(val title: String, val detail: String) {
    MONITOR("Monitor setup", "Price is approaching the planned entry zone."),
    WAIT_CONFIRMATION("Wait for confirmation", "The entry trigger has not been confirmed yet."),
    ENTRY_CONFIRMED("Entry conditions confirmed", "The trigger is confirmed and the risk plan passed validation."),
    TRACKING("Trade being tracked", "Live market price and progress are being monitored."),
    DO_NOT_ENTER("Do not enter", "The setup is invalid or its risk plan failed validation.")
}

private fun tradeIdeaRiskValid(idea: TradeIdea): Boolean {
    if (idea.side !in listOf("LONG", "SHORT")) return false
    if (!idea.entry.isFinite() || !idea.stop.isFinite() || !idea.target1.isFinite() || !idea.target2.isFinite()) return false
    if (idea.entry <= 0.0 || idea.stop <= 0.0 || idea.target1 <= 0.0 || idea.target2 <= 0.0) return false
    val geometryValid = if (idea.side == "LONG") {
        idea.stop < idea.entry && idea.target1 > idea.entry && idea.target2 > idea.target1
    } else {
        idea.stop > idea.entry && idea.target1 < idea.entry && idea.target2 < idea.target1
    }
    val warning = idea.warning.lowercase(Locale.US)
    val warningBlocksEntry = listOf("overbought", "oversold", "elevated", "falling sharply", "weak").any { it in warning }
    return geometryValid && !warningBlocksEntry
}

private data class EntryGuidance(
    val score: Int,
    val grade: String,
    val action: String,
    val trigger: String,
    val invalidation: String,
    val checks: List<Pair<String, Boolean?>>
)

private fun biasSupportsSide(bias: String, side: String): Boolean {
    val value = bias.lowercase(Locale.US)
    val tokens = if (side == "LONG") listOf("bull", "up", "long", "positive")
        else listOf("bear", "down", "short", "negative")
    return tokens.any { value.contains(it) }
}

/** Rule-based entry coaching; it describes conditions and never promises a perfect fill. */
private fun buildEntryGuidance(idea: TradeIdea): EntryGuidance {
    val longSide = idea.side == "LONG"
    val trend4h = biasSupportsSide(idea.bias4h, idea.side)
    val trend1h = biasSupportsSide(idea.bias1h, idea.side)
    val trend5m = biasSupportsSide(idea.bias5m, idea.side)
    val rsiOk = if (longSide) idea.rsi in 42.0..68.0 else idea.rsi in 32.0..58.0
    val volumeOk = idea.volumeRatio >= 1.15
    val spreadOk = idea.spreadBps?.let { it in 0.0..10.0 } == true
    val riskDistance = abs(idea.entry - idea.stop)
    val rr = if (riskDistance > 0.0) abs(idea.target1 - idea.entry) / riskDistance else 0.0
    val rewardOk = rr >= 1.5
    val score = (if (trend4h) 20 else 0) + (if (trend1h) 15 else 0) +
        (if (trend5m) 10 else 0) + (if (rsiOk) 15 else 0) +
        (if (volumeOk) 15 else 0) + (if (spreadOk) 10 else 0) +
        (if (rewardOk) 15 else 0)
    val grade = when {
        score >= 80 -> "Strong confluence"
        score >= 65 -> "Good, but verify"
        score >= 45 -> "Mixed conditions"
        else -> "Weak confluence"
    }
    val zoneHalfWidth = maxOf(idea.entry * 0.00075, riskDistance * 0.10)
    val zoneLow = idea.entry - zoneHalfWidth
    val zoneHigh = idea.entry + zoneHalfWidth
    val currentInZone = idea.last in zoneLow..zoneHigh
    val tooFar = abs(idea.last - idea.entry) > maxOf(idea.entry * 0.003, riskDistance * 0.50)
    val action = when {
        idea.side !in listOf("LONG", "SHORT") || !tradeIdeaRiskValid(idea) ->
            "Skip this setup: direction or stop/target geometry is not valid."
        idea.entryTriggered && tooFar ->
            "The trigger has fired, but price is now outside the planned entry area. Do not chase; wait for a fresh setup."
        idea.entryTriggered && score >= 65 ->
            "Trigger detected. Check the completed candle and execution spread; only consider the plan while price remains near the entry zone."
        idea.entryTriggered ->
            "Trigger detected, but confluence is weak. Wait for another confirmation candle or skip."
        currentInZone ->
            "Price is in the entry area. Wait for a clear rejection and a close back in the trade direction; do not enter on a touch alone."
        tooFar ->
            "Price is extended from the planned entry. Wait for a pullback into the zone; avoid chasing."
        else ->
            "Wait for price to approach the planned entry zone, then require a directional candle close."
    }
    val trigger = if (longSide)
        "LONG: let price retest the zone, form a 5M higher low or bullish rejection, then close back above the zone. Prefer 15M confirmation."
    else
        "SHORT: let price retest the zone, form a 5M lower high or bearish rejection, then close back below the zone. Prefer 15M confirmation."
    val invalidation = if (longSide)
        "Cancel the long if price closes below the retest support / stop level before entry."
    else
        "Cancel the short if price closes above the retest resistance / stop level before entry."
    return EntryGuidance(score, grade, action, trigger, invalidation, listOf(
        "4H trend aligned" to trend4h,
        "1H trend aligned" to trend1h,
        "5M trigger trend aligned" to trend5m,
        ("RSI not stretched (" + "%.1f".format(Locale.US, idea.rsi) + ")") to rsiOk,
        ("Volume at least 1.15x average (" + "%.2f".format(Locale.US, idea.volumeRatio) + "x)") to volumeOk,
        "Spread at or below 10 bps" to idea.spreadBps?.let { it in 0.0..10.0 },
        ("Target 1 offers at least 1.5R (" + "%.2f".format(Locale.US, rr) + "R)") to rewardOk
    ))
}

@Composable
private fun EntryGuidancePanel(idea: TradeIdea) {
    val guidance = remember(idea) { buildEntryGuidance(idea) }
    val zoneHalfWidth = maxOf(idea.entry * 0.00075, abs(idea.entry - idea.stop) * 0.10)
    val zoneLow = idea.entry - zoneHalfWidth
    val zoneHigh = idea.entry + zoneHalfWidth
    RiskCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("IDEAL ENTRY COACH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(guidance.score.toString() + "/100 · " + guidance.grade, style = MaterialTheme.typography.labelSmall,
                    color = if (guidance.score >= 65) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("Planned entry zone: " + zoneLow.usd(6) + " – " + zoneHigh.usd(6), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(guidance.action, style = MaterialTheme.typography.bodySmall)
            Text("Confirmation: " + guidance.trigger, style = MaterialTheme.typography.bodySmall)
            Text("Invalidation: " + guidance.invalidation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("Entry quality checklist", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            guidance.checks.forEach { (label, passed) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (passed == true) "✓" else "○",
                        color = if (passed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    Text(label + if (passed == null) " · unavailable" else if (passed) " · pass" else " · wait",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Rule-based confluence score, not a win probability. No entry is guaranteed; use the completed candle, limit price, and your own risk rules.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun tradeIdeaStage(idea: TradeIdea, tracked: Boolean): TradeIdeaStage {
    if (tracked) return TradeIdeaStage.TRACKING
    if (idea.side == "WAIT" || !tradeIdeaRiskValid(idea)) return TradeIdeaStage.DO_NOT_ENTER
    if (idea.entryTriggered) return TradeIdeaStage.ENTRY_CONFIRMED
    val riskDistance = abs(idea.entry - idea.stop)
    val approachBand = maxOf(idea.entry * 0.0015, riskDistance * 0.25)
    val approaching = if (idea.side == "LONG") {
        idea.last < idea.entry && idea.entry - idea.last <= approachBand
    } else {
        idea.last > idea.entry && idea.last - idea.entry <= approachBand
    }
    return if (approaching) TradeIdeaStage.MONITOR else TradeIdeaStage.WAIT_CONFIRMATION
}

@Composable
private fun TradeIdeaCard(idea: TradeIdea, density: String, onTrackingChanged: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val trackingPrefs = remember { context.getSharedPreferences("trade_idea_tracking_v1", android.content.Context.MODE_PRIVATE) }
    val trackedEntryKey = "entry_" + idea.symbol
    var trackedEntry by remember(idea.symbol) {
        mutableStateOf(trackingPrefs.getFloat(trackedEntryKey, 0f).toDouble().takeIf { it > 0.0 })
    }
    var livePrice by remember(idea.symbol) { mutableStateOf(idea.last) }
    val trackingScope = rememberCoroutineScope()
    var expanded by remember(idea.symbol, density) { mutableStateOf(density == "Expanded") }
    var levelsExpanded by remember(idea.symbol) { mutableStateOf(false) }
    DisposableEffect(idea.symbol, trackedEntry != null) {
        if (trackedEntry == null) {
            livePrice = idea.last
            onDispose { }
        } else {
            val stream = BinanceMarkPriceStream(
                symbols = listOf(idea.symbol),
                onMarkPrice = { symbol, price ->
                    if (symbol.equals(idea.symbol, ignoreCase = true)) livePrice = price
                },
                onStatus = {}
            )
            stream.start()
            onDispose { stream.close() }
        }
    }
    val stage = tradeIdeaStage(idea, trackedEntry != null)
    val tint = when (stage) {
        TradeIdeaStage.ENTRY_CONFIRMED, TradeIdeaStage.TRACKING -> MaterialTheme.colorScheme.primary
        TradeIdeaStage.DO_NOT_ENTER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val sideTint = when (idea.side) {
        "LONG" -> Color(0xFF16A34A)
        "SHORT" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val riskDistance = abs(idea.entry - idea.stop)
    val rewardDistance = abs(idea.target1 - idea.entry)
    val rewardRisk = if (riskDistance > 0.0) rewardDistance / riskDistance else 0.0
    val zoneHalfWidth = maxOf(idea.entry * 0.00075, riskDistance * 0.10)
    val zoneLow = idea.entry - zoneHalfWidth
    val zoneHigh = idea.entry + zoneHalfWidth
    val pnlPercent = trackedEntry?.let {
        (if (idea.side == "LONG") 1.0 else -1.0) * (livePrice - it) / it * 100.0
    }
    val targetProgress = trackedEntry?.let {
        val total = abs(idea.target1 - it)
        if (total > 0.0) ((if (idea.side == "LONG") livePrice - it else it - livePrice) / total).coerceIn(0.0, 1.0) else 0.0
    } ?: 0.0
    val stopProgress = trackedEntry?.let {
        val total = abs(it - idea.stop)
        if (total > 0.0) ((if (idea.side == "LONG") it - livePrice else livePrice - it) / total).coerceIn(0.0, 1.0) else 0.0
    } ?: 0.0

    RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(idea.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(idea.horizon + " · " + idea.setup, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Score " + idea.score + "/100", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (idea.side == "LONG" || idea.side == "SHORT") {
                        Surface(color = sideTint.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                            Text(
                                idea.side,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = sideTint,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text("No direction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Surface(color = tint.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stage.title.uppercase(Locale.US), color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(stage.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (idea.side == "LONG" || idea.side == "SHORT") {
                EntryGuidancePanel(idea)
            }
            if (stage == TradeIdeaStage.ENTRY_CONFIRMED || stage == TradeIdeaStage.TRACKING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryLevel("Entry", idea.entry.usd(6), Modifier.weight(1f))
                    SummaryLevel("Stop", idea.stop.usd(6), Modifier.weight(1f))
                    SummaryLevel("Target 1", idea.target1.usd(6), Modifier.weight(1f))
                    SummaryLevel("Reward", "%.2fR".format(Locale.US, rewardRisk), Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Last price", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (trackedEntry != null) livePrice else idea.last).usd(6), fontWeight = FontWeight.SemiBold)
            }
            if (density != "Compact") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("24h change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if (idea.change >= 0) "+" else "") + idea.change.pct(), color = if (idea.change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("24h quote volume", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$" + "%,.0f".format(Locale.US, idea.volume), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (stage == TradeIdeaStage.ENTRY_CONFIRMED || stage == TradeIdeaStage.TRACKING) {
                HorizontalDivider()
                TextButton(onClick = { levelsExpanded = !levelsExpanded }, modifier = Modifier.align(Alignment.End)) { Text(if (levelsExpanded) "Hide levels" else "Show all levels") }
                if (levelsExpanded) {
                Text("Entry & risk plan", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                IdeaLevel("Entry zone low", zoneLow)
                IdeaLevel("Entry trigger", idea.entry)
                IdeaLevel("Entry zone high", zoneHigh)
                IdeaLevel("Stop-loss", idea.stop)
                IdeaLevel("Target 1", idea.target1)
                IdeaLevel("Target 2", idea.target2)
                ScannerMetric("Target 1 reward / risk", "%.2fR".format(Locale.US, rewardRisk))
                }
            }
            if (stage == TradeIdeaStage.TRACKING && trackedEntry != null) {
                HorizontalDivider()
                Text("Live trade tracking", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                ScannerMetric("Tracked entry", trackedEntry!!.usd(6))
                ScannerMetric("Live P&L", "%+.2f%%".format(Locale.US, pnlPercent ?: 0.0))
                ScannerMetric("Live price source", "Binance Futures · live WebSocket")
                Text("Progress to target 1", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { targetProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Progress toward stop", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { stopProgress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                Text("P&L is price-only and excludes fees, funding, slippage and position size. Tracking is not connected to an exchange order.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    trackingPrefs.edit().remove(trackedEntryKey).apply()
                    trackedEntry = null
                    livePrice = idea.last
                    onTrackingChanged(idea.symbol, false)
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop tracking") }
            } else if (stage == TradeIdeaStage.ENTRY_CONFIRMED) {
                Text("Risk checks passed. Verify spread, slippage and position size before entering.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    trackingScope.launch {
                        // Use the latest scanner/stream snapshot instead of making another REST quote call.
                        val entry = livePrice.takeIf { it > 0.0 } ?: idea.last
                        trackingPrefs.edit().putFloat(trackedEntryKey, entry.toFloat()).apply()
                        livePrice = entry
                        trackedEntry = entry
                        onTrackingChanged(idea.symbol, true)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Track this idea") }
            } else if (stage == TradeIdeaStage.MONITOR) {
                Text("Entry zone: " + zoneLow.usd(6) + " – " + zoneHigh.usd(6), style = MaterialTheme.typography.bodySmall)
            } else if (stage == TradeIdeaStage.WAIT_CONFIRMATION) {
                Text("Entry trigger: " + idea.entry.usd(6) + " · confirmation is still missing.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(if (idea.side == "WAIT") "Trend, momentum and liquidity filters are not aligned. No entry is permitted."
                    else "The risk plan failed validation or has a blocking warning: " + idea.warning,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (density == "Expanded") HistoricalOutcomePanel(idea) else Text(historyOneLine(idea), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) { Text(if (expanded) "Hide analysis" else "Why this result?") }
            if (expanded) {
                HorizontalDivider()
                Text("Trend: 4H " + idea.bias4h + " · 1H " + idea.bias1h + " · 5M " + idea.bias5m, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("15M RSI " + "%.1f".format(Locale.US, idea.rsi) + " · volume " + "%.2f".format(Locale.US, idea.volumeRatio) + "× recent average", style = MaterialTheme.typography.bodySmall)
                ScannerMetric("Order-book spread", idea.spreadBps?.let { "%.1f bps".format(Locale.US, it) } ?: "Unavailable")
                ScannerMetric("Depth imbalance", idea.bookImbalance?.let { "%+.2f".format(Locale.US, it) } ?: "Unavailable")
                ScannerMetric("Funding rate", idea.fundingRate?.let { "%+.4f%%".format(Locale.US, it * 100.0) } ?: "Unavailable")
                ScannerMetric("Open-interest change", idea.oiChangePct?.let { "%+.2f%%".format(Locale.US, it) } ?: "Unavailable")
                HorizontalDivider()
                Text("Historical replay · " + idea.backtest.trades + " closed samples", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text(if (!idea.backtest.hasUsableEstimate) "Win/loss estimate is hidden until at least 30 completed historical examples are available. The replay currently has " + idea.backtest.trades + "."
                    else "Observed historical win rate " + "%.1f".format(Locale.US, idea.backtest.winRatePercent) + "% · average " +
                        "%+.2f".format(Locale.US, idea.backtest.averageR) + "R.",
                    style = MaterialTheme.typography.bodySmall)
                idea.reasons.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                Text(idea.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SummaryLevel(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One-line history summary for the compact card. */
private fun historyOneLine(idea: TradeIdea): String =
    if (idea.backtest.hasUsableEstimate) "Historical win rate " + "%.1f".format(Locale.US, idea.backtest.winRatePercent) + "% over " + idea.backtest.trades + " samples"
    else "No history yet (" + idea.backtest.trades + " of 30 samples)"

@Composable
private fun IdeaLevel(label: String, value: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.usd(6), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScannerMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoricalOutcomePanel(idea: TradeIdea) {
    val stats = idea.backtest
    val sideLabel = if (idea.side == "WAIT") "Combined LONG + SHORT replay" else idea.side + " historical replay"
    RiskCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Historical win / loss", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stats.confidenceLabel, style = MaterialTheme.typography.labelSmall, color = if (stats.hasUsableEstimate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(sideLabel + " · " + stats.trades + " completed samples", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stats.hasUsableEstimate) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Historical wins", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.1f%%".format(Locale.US, stats.winRatePercent), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Historical losses", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.1f%%".format(Locale.US, stats.lossRatePercent), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
                val lower = stats.winRateLower95 ?: 0.0
                val upper = stats.winRateUpper95 ?: 100.0
                Text("Approx. 95% uncertainty range for win rate: %.1f–%.1f%%".format(Locale.US, lower, upper),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Insufficient data — need at least 30 completed replay samples. Current: " + stats.trades + ".",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class TradeSessionSlot(
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val focus: String
)

@Composable
private fun TradeSessionTimingCard() {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val sessions = remember {
        listOf(
            TradeSessionSlot("Asia activity", 9 * 60, 11 * 60, "Range development · 5M / 15M scalp watch"),
            TradeSessionSlot("London open", 13 * 60 + 30, 16 * 60 + 30, "Breakout follow-through · 15M / 1H intraday"),
            TradeSessionSlot("US open & overlap", 20 * 60, 23 * 60, "Higher liquidity · intraday and 4H swing review")
        )
    }
    val ist = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { timeInMillis = nowMillis }
    val minuteOfDay = ist.get(Calendar.HOUR_OF_DAY) * 60 + ist.get(Calendar.MINUTE)
    val active = sessions.firstOrNull { slot ->
        if (slot.endMinute > slot.startMinute) minuteOfDay in slot.startMinute until slot.endMinute
        else minuteOfDay >= slot.startMinute || minuteOfDay < slot.endMinute
    }
    val next = sessions.map { slot ->
        val delta = (slot.startMinute - minuteOfDay + 1440) % 1440
        slot to delta
    }.filter { it.second > 0 }.minByOrNull { it.second } ?: (sessions.first() to (1440 - minuteOfDay + sessions.first().startMinute))
    var timingExpanded by remember { mutableStateOf(false) }
    fun clock(minutes: Int): String = "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)
    val remaining = if (active != null) {
        val end = active.endMinute
        if (end > minuteOfDay) end - minuteOfDay else 1440 - minuteOfDay + end
    } else next.second
    RiskCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trade timing · India (IST)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (active != null) "Window active: " + active.name + " · ends in " + (remaining / 60) + "h " + (remaining % 60) + "m"
                else "Next window: " + next.first.name + " · starts in " + (remaining / 60) + "h " + (remaining % 60) + "m",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { timingExpanded = !timingExpanded }, modifier = Modifier.align(Alignment.End)) { Text(if (timingExpanded) "Hide windows" else "Show windows") }
            if (timingExpanded) {
            sessions.forEach { slot ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(slot.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(slot.focus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(clock(slot.startMinute) + "–" + clock(slot.endMinute), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider()
            Text("These are approximate activity windows, not guaranteed entry times. UK/US daylight-saving changes shift session alignment; crypto trades 24/7.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
