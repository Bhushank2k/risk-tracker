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
import androidx.compose.material.icons.filled.ExpandMore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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

private data class ScanTicker(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double, val volatilityPct: Double, val atrPct: Double = 0.0, val relativeVolume: Double = 0.0)
private data class ScanCandle(val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long)
internal data class TradeIdea(
    val symbol: String, val side: String, val score: Int, val volume: Double, val change: Double,
    val last: Double, val entry: Double, val stop: Double, val target1: Double, val target2: Double,
    val entryTriggered: Boolean,
    val rsi: Double, val volumeRatio: Double, val bias4h: String, val bias1h: String,
    val setup: String, val reasons: List<String>, val warning: String, val horizon: String,
    val bias5m: String, val spreadBps: Double?, val bookImbalance: Double?,
    val fundingRate: Double?, val oiChangePct: Double?, val backtest: TradeIdeaBacktestStats,
    val volatilityPct: Double = 0.0
)
internal data class MarketMicrostructure(
    val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double?, val oiChangePct: Double?
)

internal object TradeIdeaCache {
    private const val PREFS = "market_scan_cache_v2"
    private const val KEY = "latest_scan"

    fun load(context: android.content.Context, november2Mode: Boolean = false): Pair<Long, List<TradeIdea>>? = runCatching {
        val root = JSONObject(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(if (november2Mode) KEY + "_nov2_scalp" else KEY, null) ?: return null)
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
                backtest = TradeIdeaBacktestStats(stats.optInt("trades"), stats.optInt("wins"), stats.optDouble("averageR")),
                volatilityPct = x.optDouble("volatilityPct", 0.0)
            )
        }
        if (timestamp <= 0L) null else timestamp to ideas
    }.getOrNull()

    fun save(context: android.content.Context, updatedAt: Long, ideas: List<TradeIdea>, november2Mode: Boolean = false) {
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
                    .put("backtest", stats).put("volatilityPct", idea.volatilityPct))
            }
            val root = JSONObject().put("updatedAt", updatedAt).put("ideas", rows)
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                 .edit().putString(if (november2Mode) KEY + "_nov2_scalp" else KEY, root.toString()).apply()
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }
}

internal object BinanceMarketScanner {
    private const val BASE = "https://fapi.binance.com"
    private const val MAX_DAILY_VOLATILE_MARKETS = 30
    private val scanMutex = Mutex()
    @Volatile private var restBlockedUntilMs = 0L
    @Volatile private var restLimitPrefs: android.content.SharedPreferences? = null

    private class BinanceRateLimitException(message: String) : IllegalStateException(message)

    fun initialize(context: android.content.Context) {
        val prefs = context.applicationContext.getSharedPreferences("binance_rest_guard_v1", android.content.Context.MODE_PRIVATE)
        restLimitPrefs = prefs
        restBlockedUntilMs = maxOf(restBlockedUntilMs, prefs.getLong("blocked_until", 0L))
    }

    private fun ensureRestAllowed() {
        val remaining = restBlockedUntilMs - System.currentTimeMillis()
        if (remaining > 0L) throw BinanceRateLimitException(
            "Binance REST is paused after a rate-limit response. Retry in about " + ((remaining / 1000L) + 1L) + "s; live WebSocket data remains preferred."
        )
    }

    fun latestPrice(symbol: String): Double? = BinanceFuturesStream.latestTicker(symbol)?.first

    private fun get(path: String): String {
        ensureRestAllowed()
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 16000
        c.requestMethod = "GET"
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("msg") }.getOrDefault("request failed")
                if (code == 418 || code == 429) {
                    val now = System.currentTimeMillis()
                    val retrySeconds = c.getHeaderField("Retry-After")?.toLongOrNull()
                    val headerPauseMs = retrySeconds?.coerceAtLeast(1L)?.times(1000L)
                    val banUntilText = message.substringAfter("banned until", "").trim().takeWhile { it.isDigit() }
                    val bodyPauseMs = banUntilText.toLongOrNull()?.minus(now)?.coerceAtLeast(1L)
                    val serverPauseMs = maxOf(headerPauseMs ?: 0L, bodyPauseMs ?: 0L)
                    val pauseMs = serverPauseMs.takeIf { it > 0L }
                        ?: if (code == 418) 15L * 60_000L else 60_000L
                    restBlockedUntilMs = maxOf(restBlockedUntilMs, now + pauseMs)
                    restLimitPrefs?.edit()?.putLong("blocked_until", restBlockedUntilMs)?.apply()
                    throw BinanceRateLimitException("Binance HTTP " + code + ": " + message + ". REST calls paused; WebSocket remains the live-data path.")
                }
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

    private fun atrPercent(rows: List<ScanCandle>): Double {
        val value = atr(rows) ?: return 0.0
        val close = rows.lastOrNull()?.close ?: return 0.0
        return if (close > 0.0) value / close * 100.0 else 0.0
    }

    private fun relativeVolume(rows: List<ScanCandle>): Double {
        if (rows.size < 22) return 0.0
        val average = rows.dropLast(1).takeLast(20).map { it.volume }.average()
        return if (average > 0.0) rows.last().volume / average else 0.0
    }

    private fun btcRegime(h4: List<ScanCandle>, h1: List<ScanCandle>): String {
        val c4 = h4.map { it.close }; val c1 = h1.map { it.close }
        val e4_20 = ema(c4, 20); val e4_50 = ema(c4, 50); val e4_200 = ema(c4, 200)
        val e1_20 = ema(c1, 20); val e1_50 = ema(c1, 50); val e1_200 = ema(c1, 200)
        if (e4_20 == null || e4_50 == null || e4_200 == null || e1_20 == null || e1_50 == null || e1_200 == null) return "Neutral"
        val bullish = h4.last().close > e4_200 && e4_20 > e4_50 && e4_50 > e4_200 &&
            h1.last().close > e1_200 && e1_20 > e1_50 && e1_50 > e1_200
        val bearish = h4.last().close < e4_200 && e4_20 < e4_50 && e4_50 < e4_200 &&
            h1.last().close < e1_200 && e1_20 < e1_50 && e1_50 < e1_200
        return when { bullish -> "Bullish"; bearish -> "Bearish"; else -> "Neutral" }
    }

    private fun vwap(rows: List<ScanCandle>): Double? {
        val s = rows.takeLast(24)
        val volume = s.sumOf { it.volume }
        return if (volume <= 0) null else s.sumOf { it.close * it.volume } / volume
    }

    suspend fun scan(november2Mode: Boolean = false, progress: (String) -> Unit): List<TradeIdea> = scanMutex.withLock { withContext(Dispatchers.IO) {
        progress("Loading active USDT perpetual contracts (one REST bootstrap)…")
        val info = JSONObject(get("/fapi/v1/exchangeInfo")).optJSONArray("symbols") ?: JSONArray()
        val allowed = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter {
            it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" && it.optString("quoteAsset") == "USDT"
        }.map { it.optString("symbol") }.toSet()
        val all = JSONArray(get("/fapi/v1/ticker/24hr"))
        val prefiltered = (0 until all.length()).mapNotNull { i ->
            val x = all.optJSONObject(i) ?: return@mapNotNull null
            val symbol = x.optString("symbol")
            val quote = x.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            val last = x.optString("lastPrice").toDoubleOrNull() ?: 0.0
            if (symbol !in allowed || quote < 5_000_000.0 || last <= 0.0) null
            else {
                val change = x.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                val high = x.optString("highPrice").toDoubleOrNull() ?: last
                val low = x.optString("lowPrice").toDoubleOrNull() ?: last
                val rangePct = ((high - low) / last * 100.0).coerceAtLeast(0.0)
                ScanTicker(symbol, quote, change, last, rangePct)
            }
        }.sortedByDescending { it.volatilityPct + abs(it.changePct) * 0.5 }
            .take(60)
        if (prefiltered.isEmpty()) throw IllegalStateException("No liquid USDT perpetual pairs were returned.")

        // ATR% and relative volume refine the 24h-range prefilter before the final 30 are selected.
        progress("Measuring hourly ATR% and relative volume across the liquid volatility shortlist…")
        val hourlyCache = linkedMapOf<String, List<ScanCandle>>()
        val ranked = mutableListOf<ScanTicker>()
        for (ticker in prefiltered) {
            coroutineContext.ensureActive()
            try {
                delay(750)
                val hourly = candles(ticker.symbol, "1h", 260)
                hourlyCache[ticker.symbol] = hourly
                ranked.add(ticker.copy(atrPct = atrPercent(hourly), relativeVolume = relativeVolume(hourly)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BinanceRateLimitException) {
                throw e
            } catch (_: Exception) {
                // Incomplete hourly history is not eligible for ATR-based ranking.
            }
        }
        val candidates = ranked.sortedByDescending {
            it.atrPct * (1.0 + 0.12 * it.relativeVolume.coerceIn(0.0, 3.0)) + it.volatilityPct * 0.05
        }.take(MAX_DAILY_VOLATILE_MARKETS)
        if (candidates.isEmpty()) throw IllegalStateException("Hourly ATR and relative-volume data were unavailable for the liquid market shortlist.")

        // BTC trend is a broad-market risk filter; neutral conditions do not impose a directional bias.
        delay(750)
        val btcH4 = candles("BTCUSDT", "4h", 260)
        delay(750)
        val btcH1 = candles("BTCUSDT", "1h", 260)
        val btcBias = btcRegime(btcH4, btcH1)

        // Market-wide aggregate sockets provide live data without per-symbol stream subscriptions.
        BinanceFuturesStream.start(candidates.map { it.symbol })
        delay(2_000)
        progress("Daily radar · evaluating ${candidates.size} markets ranked by hourly ATR% and relative volume; BTC regime: $btcBias…")
        val results = mutableListOf<TradeIdea>()
        for ((index, ticker) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            try {
                // Serial, paced REST candle bootstrap. The hourly candles from pre-ranking are reused.
                delay(750)
                val h4 = candles(ticker.symbol, "4h", 260)
                val h1 = hourlyCache[ticker.symbol] ?: candles(ticker.symbol, "1h", 260)
                delay(750)
                val m15 = candles(ticker.symbol, "15m", 500)
                delay(750)
                val m5 = candles(ticker.symbol, "5m", 1500)
                val micro = BinanceFuturesStream.microstructure(ticker.symbol)
                val liveTicker = BinanceFuturesStream.latestTicker(ticker.symbol)
                val live = ticker.copy(
                    last = liveTicker?.first ?: ticker.last,
                    quoteVolume = liveTicker?.second ?: ticker.quoteVolume,
                    changePct = liveTicker?.third ?: ticker.changePct
                )
                if (november2Mode) {
                    val four = h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
                    val one = h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
                    val fifteen = m15.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
                    val five = m5.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
                    val plan = November5ScalpRules.evaluate(four, one, fifteen, five,
                        V2Microstructure(micro.spreadBps, micro.bookImbalance, micro.fundingRate))
                    if (plan != null) results.add(buildIdea(live, plan, m15, m5, h4, h1, micro, false, runBacktest = false))
                } else {
                    results.addAll(evaluateModes(live, h4, h1, m15, m5, micro, btcBias))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BinanceRateLimitException) {
                throw e
            } catch (_: Exception) {
                // Skip incomplete symbols; never fan out retries after a rate-limit response.
            }
            progress("Daily volatility radar · analyzed ${index + 1}/${candidates.size} top-ranked pairs…")
        }
        results.sortedWith(compareByDescending<TradeIdea> { it.score }.thenByDescending { it.volatilityPct })
    } }

    private fun evaluateModes(t: ScanTicker, h4: List<ScanCandle>, h1: List<ScanCandle>, m15: List<ScanCandle>, m5: List<ScanCandle>, micro: MarketMicrostructure, btcBias: String): List<TradeIdea> {
        val four = h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
        val one = h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
        val fifteen = m15.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
        val five = m5.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
        val market = V2Microstructure(micro.spreadBps, micro.bookImbalance, micro.fundingRate)
        val scalpPlan = TradeIdeasV2.evaluate(four, one, fifteen, five, market, t.quoteVolume, t.changePct, false, btcBias)
        // For swing mode, the H1 candles become the setup series and M15 candles become execution.
        val swingPlan = TradeIdeasV2.evaluate(four, one, one, fifteen, market, t.quoteVolume, t.changePct, true, btcBias)
        return listOfNotNull(
            scalpPlan?.let { buildIdea(t, it, m15, m5, h4, h1, micro, false) },
            swingPlan?.let { buildIdea(t, it, h1, m15, h4, h1, micro, true) }
        )
    }

    private fun buildIdea(t: ScanTicker, plan: V2TradePlan, setupRows: List<ScanCandle>, executionRows: List<ScanCandle>,
                          h4: List<ScanCandle>, h1: List<ScanCandle>, micro: MarketMicrostructure, swingMode: Boolean, runBacktest: Boolean = true): TradeIdea {
        val rsiValue = rsi(setupRows.map { it.close }) ?: 50.0
        val priorVolume = setupRows.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (priorVolume > 0.0) setupRows.last().volume / priorVolume else 0.0
        val bias4 = plan.bias4h
        val bias1 = plan.bias1h
        val bias5 = plan.bias5m
        val reasons = plan.reasons.toMutableList()
        micro.oiChangePct?.let { reasons.add("Open interest changed " + "%+.2f".format(Locale.US, it) + "% over the latest 15M samples") }
        val replay = if (swingMode || !runBacktest) TradeIdeaBacktestStats() else TradeIdeasV2.backtest(
            h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) },
            h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) },
            setupRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) },
            executionRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime) }
        )
        return TradeIdea(
            t.symbol, plan.side, plan.score, t.quoteVolume, t.changePct, t.last,
            plan.entry, plan.stop, plan.target1, plan.target2, plan.entryTriggered,
            rsiValue, volumeRatio, bias4, bias1, plan.setup, reasons, plan.warning,
            plan.horizon, bias5, micro.spreadBps, micro.bookImbalance, micro.fundingRate,
            micro.oiChangePct, if (swingMode) TradeIdeaBacktestStats() else replay, t.volatilityPct
        )
    }
}

@Composable
fun DailyTradeIdeasScreen(november2Mode: Boolean = false) {
    val context = LocalContext.current
    val cachedScan = remember(november2Mode) { TradeIdeaCache.load(context, november2Mode) }
    val trackingPrefs = remember { context.getSharedPreferences("trade_idea_tracking_v1", android.content.Context.MODE_PRIVATE) }
    fun readTrackedSymbols(): Set<String> = trackingPrefs.all.keys
        .filter { it.startsWith("entry_") && (trackingPrefs.all[it] as? Float ?: 0f) > 0f }
        .map { it.removePrefix("entry_") }.toSet()
    var ideas by remember { mutableStateOf(cachedScan?.second ?: emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (cachedScan != null) "Showing saved scan while fresh Binance data loads…" else "Refresh to scan live Binance USDT-M perpetual markets.") }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(cachedScan?.first) }
    var scanHealth by remember { mutableStateOf(TradeIdeaScanHealthStore.load(context)) }
    var showingCached by remember { mutableStateOf(cachedScan != null) }
    var filter by remember { mutableStateOf("All") }
    var strategyMode by remember(november2Mode) { mutableStateOf(if (november2Mode) "Scalping" else "Daily Picks") }
    var density by remember { mutableStateOf("Compact") }
    var scanExpanded by remember { mutableStateOf(false) }
    var trackedSymbols by remember { mutableStateOf(readTrackedSymbols()) }
    var livePrices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
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
        status = if (november2Mode) "Loading November 5 Daily Scalp rules from Binance public market data…" else "Connecting to Binance public market data…"
        TradeIdeaScanHealthStore.started(context)
        scanHealth = TradeIdeaScanHealthStore.load(context)
        scope.launch {
            try {
                val freshIdeas = BinanceMarketScanner.scan(november2Mode) { status = it }
                val scanTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) { TradeIdeaCache.save(context, scanTime, freshIdeas, november2Mode) }
                TradeIdeaScanHealthStore.succeeded(context, scalp = november2Mode, at = scanTime)
                scanHealth = TradeIdeaScanHealthStore.load(context)
                ideas = freshIdeas
                updatedAt = scanTime
                showingCached = false
                trackedSymbols = readTrackedSymbols()
                paperStats = TradeIdeaPaperJournal.stats(context)
                status = "Scan complete · " + freshIdeas.size + " trade ideas from eligible markets."
                if (freshIdeas.isEmpty()) status = "Scan complete · no setups passed the current filters."
            } catch (e: Exception) {
                error = e.message ?: "Market scan failed. Check your connection and retry."
                TradeIdeaScanHealthStore.failed(context, error ?: "Market scan failed")
                scanHealth = TradeIdeaScanHealthStore.load(context)
                status = "Scan could not be completed."
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) {
        BinanceMarketScanner.initialize(context)
        // Start all-market public streams first, so cached ideas still receive live prices if REST is banned.
        BinanceFuturesStream.start(emptyList())
        paperStats = TradeIdeaPaperJournal.stats(context)
    }
    LaunchedEffect(november2Mode) {
        while (true) {
            delay(60_000)
            scanHealth = TradeIdeaScanHealthStore.load(context)
        }
    }
    LaunchedEffect(ideas) {
        // Keep stage counts and filters in sync with the same live WebSocket cache used by idea cards.
        while (true) {
            val latest = withContext(Dispatchers.IO) {
                ideas.mapNotNull { idea ->
                    BinanceMarketScanner.latestPrice(idea.symbol)?.let { idea.symbol to it }
                }.toMap()
            }
            if (latest.isNotEmpty()) livePrices = livePrices + latest
            delay(2_000)
        }
    }
    val liveIdeas = ideas.map { idea -> idea.copy(last = livePrices[idea.symbol] ?: idea.last) }
    val allModeIdeas = liveIdeas.filter { idea ->
        strategyMode == "All" || strategyMode == "Daily Picks" ||
            (strategyMode == "Scalping" && idea.horizon.contains("Scalp", ignoreCase = true)) ||
            (strategyMode == "Intraday" && idea.horizon.contains("Swing", ignoreCase = true))
    }
    val dailyPicks = selectDailyPicks(allModeIdeas)
    val modeIdeas = if (strategyMode == "Daily Picks") {
        (dailyPicks + allModeIdeas.filter { it.symbol in trackedSymbols }).distinctBy { it.symbol + "|" + it.horizon }
    } else allModeIdeas
    val rankedIdeas = modeIdeas.sortedWith(
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
            "Scalp" -> idea.horizon.contains("scalp", ignoreCase = true)
            "Intraday" -> idea.horizon.contains("swing", ignoreCase = true)
            "Long" -> idea.side == "LONG"
            "Short" -> idea.side == "SHORT"
            else -> true
        }
    }
    val trackedVisible = visible.filter { it.symbol in trackedSymbols }
    val opportunityVisible = visible.filterNot { it.symbol in trackedSymbols }
    val readyCount = modeIdeas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.ENTRY_CONFIRMED }
    val monitorCount = modeIdeas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.MONITOR }
    val blockedCount = modeIdeas.count { tradeIdeaStage(it, it.symbol in trackedSymbols) == TradeIdeaStage.DO_NOT_ENTER }
    val trackedCount = modeIdeas.count { it.symbol in trackedSymbols }
    val longCount = modeIdeas.count { it.side == "LONG" }
    val shortCount = modeIdeas.count { it.side == "SHORT" }
    val waitCount = modeIdeas.count { it.side == "WAIT" }
    val timeLabel = updatedAt?.let {
        SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(it)) + " IST"
    } ?: "Not scanned yet"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TradeSessionTimingCard() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (november2Mode) "November 5 Daily Scalp rules · 4H/1H/15M EMA alignment, 15M VWAP + breakout, 5M confirmation. Advisory only." else "Rule-based setups from liquid USDT perpetual markets. Review the risk before acting.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Live prices and order-book updates use Binance WebSocket; REST is reserved for initial history and recovery.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (showingCached) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (showingCached) "SAVED SNAPSHOT" else if (loading) "SCANNING MARKET" else "LIVE SCAN RESULT",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (showingCached) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val healthTime = if (november2Mode) scanHealth.lastScalpSuccessAt else scanHealth.lastSuccessAt
                val healthLabel = if (healthTime > 0L) {
                    val ageMinutes = ((System.currentTimeMillis() - healthTime).coerceAtLeast(0L) / 60_000L)
                    val stamp = SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(healthTime))
                    "Last successful ${if (november2Mode) "Daily Scalp" else "market"} scan: $stamp IST · ${ageMinutes}m ago"
                } else "No successful ${if (november2Mode) "Daily Scalp" else "market"} scan recorded yet"
                val healthAge = if (healthTime > 0L) System.currentTimeMillis() - healthTime else Long.MAX_VALUE
                Text((if (healthAge <= 90 * 60_000L) "FRESH · " else "STALE · ") + healthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (healthAge <= 90 * 60_000L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("Background monitoring is best-effort; opening this screen does not start a scan. Use Refresh for a manual market scan. Price alerts are not real-time guarantees.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!scanHealth.lastError.isNullOrBlank()) Text("Last scan warning: ${scanHealth.lastError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (showingCached) Text("Showing the last saved scan while fresh market data is unavailable.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScannerStat("Ready", readyCount.toString(), Modifier.weight(1f))
                    ScannerStat("Monitor", monitorCount.toString(), Modifier.weight(1f))
                    ScannerStat("Tracking", trackedCount.toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Market scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(longCount.toString() + " long · " + shortCount.toString() + " short · " + waitCount.toString() + " no direction",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { scanExpanded = !scanExpanded }) { Text(if (scanExpanded) "Less" else "Details") }
                    }
                    Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(if (loading) "Scanning markets…" else "Refresh market scan")
                    }
                    if (scanExpanded) {
                        Text("Source · Binance Futures public market data. The scanner prefilters liquid USDT perpetuals by 24h range, then ranks the shortlist by 1H ATR% and relative volume. BTC 4H/1H trend alignment, setup volume, breakout validity and liquidity gates apply.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScannerStat("LONG", longCount.toString(), Modifier.weight(1f))
                            ScannerStat("SHORT", shortCount.toString(), Modifier.weight(1f))
                            ScannerStat("No direction", waitCount.toString(), Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Entry & exit notifications", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("Local alerts only · no orders are placed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!november2Mode) Text("Strategy mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!november2Mode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Daily Picks", "Scalping", "Intraday", "All").forEach { mode ->
                        FilterChip(selected = strategyMode == mode, onClick = { strategyMode = mode },
                            label = { Text(mode) })
                    }
                }
                if (!november2Mode) Text(
                    when (strategyMode) {
                        "Daily Picks" -> "Up to 2 scalp setups (M15/M5) + 1 intraday setup (H1/M15), ranked by quality. Fewer may qualify."
                        "Scalping" -> "H1 trend · M15 breakout/retest · M5 entry confirmation"
                        "Intraday" -> "H4 + H1 trend · H1 breakout/retest · M15 entry confirmation"
                        else -> "Showing all qualifying scalp and intraday strategy results."
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!november2Mode && strategyMode == "Daily Picks") Text(
                    "Selected ${dailyPicks.size}/3 · ${dailyPicks.count { it.horizon.contains("Scalp", ignoreCase = true) }} scalp · ${dailyPicks.count { it.horizon.contains("Swing", ignoreCase = true) }} intraday. No quota is forced.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Opportunities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(readyCount.toString() + " ready · " + blockedCount + " do not enter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Ready", "Monitor", "Waiting", "Tracking", "Do not enter", "Scalp", "Intraday", "Long", "Short").forEach { option ->
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
            items(trackedVisible, key = { "tracked_" + it.symbol + "_" + it.horizon }) { idea ->
                TradeIdeaCard(idea, density) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
            }
        }
        if (opportunityVisible.isNotEmpty()) {
            item { Text(if (filter == "All") "Best opportunities" else filter + " ideas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(opportunityVisible, key = { "idea_" + it.symbol + "_" + it.horizon }) { idea ->
                TradeIdeaCard(idea, density) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
            }
        } else if (trackedVisible.isEmpty()) {
            item {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (ideas.isEmpty()) "No scan results yet" else if (strategyMode == "Daily Picks") "No qualifying daily picks right now" else "No ideas in this filter", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (ideas.isEmpty()) "Refresh the market scan to load current setups."
                            else if (strategyMode == "Daily Picks") "No setup passed the existing score and risk gates. The shortlist will stay empty rather than force a weak trade."
                            else "Try another state or timeframe filter.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Paper-trade results are historical observations, not a forecast. Replay excludes fees, funding, slippage and exchange execution effects.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val warningBlocksEntry = warning.startsWith("block:", ignoreCase = true) || listOf("overbought", "oversold", "elevated", "falling sharply", "weak").any { it in warning }
    return geometryValid && !warningBlocksEntry
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

private fun displayIdeaHorizon(horizon: String): String =
    horizon.replace("Swing · H1 setup", "Intraday · H1 setup")

/**
 * A daily shortlist is a target, not a quota: only directional, risk-valid ideas
 * scoring at least 75 are eligible. Prefer confirmed triggers, then stronger
 * scores/volume/liquidity. Keep symbols unique and cap one direction at two picks.
 */
private fun selectDailyPicks(ideas: List<TradeIdea>): List<TradeIdea> {
    val eligible = ideas.filter { idea ->
        idea.side in setOf("LONG", "SHORT") &&
            idea.score >= TradeIdeasV2.MIN_SCORE &&
            tradeIdeaRiskValid(idea) &&
            !idea.warning.startsWith("BLOCK:", ignoreCase = true)
    }.sortedWith(
        compareByDescending<TradeIdea> { it.entryTriggered }
            .thenByDescending { it.score }
            .thenByDescending { it.volumeRatio }
            .thenBy { it.spreadBps ?: Double.POSITIVE_INFINITY }
            .thenBy { abs(it.last - it.entry) / it.entry.coerceAtLeast(0.00000001) }
    )

    val selected = mutableListOf<TradeIdea>()
    val usedSymbols = mutableSetOf<String>()
    val directionCounts = mutableMapOf("LONG" to 0, "SHORT" to 0)

    fun addBest(horizon: String, limit: Int) {
        eligible.asSequence()
            .filter { it.horizon.contains(horizon, ignoreCase = true) }
            .filter { it.symbol !in usedSymbols && (directionCounts[it.side] ?: 0) < 2 }
            .take(limit)
            .forEach { idea ->
                selected += idea
                usedSymbols += idea.symbol
                directionCounts[idea.side] = (directionCounts[idea.side] ?: 0) + 1
            }
    }

    addBest("Scalp", 2)
    addBest("Swing", 1) // Existing H1 setup / M15 execution strategy is presented as Intraday.
    return selected
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
    LaunchedEffect(idea.symbol) {
        // Poll only the in-memory WebSocket cache; this loop never makes a REST request.
        while (true) {
            val quote = withContext(Dispatchers.IO) { BinanceMarketScanner.latestPrice(idea.symbol) }
            if (quote != null) livePrice = quote
            delay(2_000)
        }
    }
    val liveIdea = idea.copy(last = livePrice)
    val stage = tradeIdeaStage(liveIdea, trackedEntry != null)
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
        Column(Modifier.padding(if (density == "Compact" && !expanded) 11.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (density == "Compact" && !expanded) 5.dp else 8.dp)) {
            if (density == "Compact" && !expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(idea.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(displayIdeaHorizon(idea.horizon) + " · " + idea.setup, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (idea.side == "WAIT") "NO DIRECTION" else idea.side, style = MaterialTheme.typography.labelMedium, color = sideTint, fontWeight = FontWeight.Bold)
                        Text(stage.title, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Entry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(idea.entry.usd(6), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Last price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(livePrice.usd(6), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Risk / reward", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (tradeIdeaRiskValid(idea)) "%.2fR".format(Locale.US, rewardRisk) else "Unavailable",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("View details")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(idea.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(displayIdeaHorizon(idea.horizon) + " · " + idea.setup, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(livePrice.usd(6), fontWeight = FontWeight.SemiBold)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("24h high-low range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.2f%%".format(Locale.US, idea.volatilityPct), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }

            if (stage == TradeIdeaStage.ENTRY_CONFIRMED || stage == TradeIdeaStage.TRACKING) {
                HorizontalDivider()
                TextButton(onClick = { levelsExpanded = !levelsExpanded }, modifier = Modifier.align(Alignment.End)) { Text(if (levelsExpanded) "Hide levels" else "Show all levels") }
                if (levelsExpanded) {
                Text("Entry & risk plan · retest confirmation", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                IdeaLevel("Entry zone low", zoneLow)
                IdeaLevel("Entry trigger", idea.entry)
                IdeaLevel("Entry zone high", zoneHigh)
                IdeaLevel("Stop-loss", idea.stop)
                IdeaLevel("Target 1", idea.target1)
                IdeaLevel("Target 2", idea.target2)
                ScannerMetric("Target 1 reward / risk", "%.2fR".format(Locale.US, rewardRisk))
                ScannerMetric("Exit plan", "TP1 50% at 1.5R · TP2 30% at 2.5R · trail 20% by 1 ATR")
                ScannerMetric("Risk budget", "0.5% equity · max 3× leverage")
                }
            }
            if (stage == TradeIdeaStage.TRACKING && trackedEntry != null) {
                HorizontalDivider()
                Text("Live trade tracking", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                ScannerMetric("Tracked entry", trackedEntry!!.usd(6))
                ScannerMetric("Live P&L", "%+.2f%%".format(Locale.US, pnlPercent ?: 0.0))
                ScannerMetric("Live price source", "Binance Futures WebSocket · refreshes every 2s")
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
                        val entry = withContext(Dispatchers.IO) { BinanceMarketScanner.latestPrice(idea.symbol) }
                            ?: livePrice.takeIf { it > 0.0 } ?: idea.last
                        trackingPrefs.edit().putFloat(trackedEntryKey, entry.toFloat()).apply()
                        livePrice = entry
                        trackedEntry = entry
                        onTrackingChanged(idea.symbol, true)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Track this idea") }
            } else if (stage == TradeIdeaStage.MONITOR) {
                Text("Entry zone: " + zoneLow.usd(6) + " – " + zoneHigh.usd(6), style = MaterialTheme.typography.bodySmall)
                Text("Wait for the 15M close and volume trigger. Do not anticipate the breakout.", style = MaterialTheme.typography.bodySmall)
            } else if (stage == TradeIdeaStage.WAIT_CONFIRMATION) {
                Text("Entry trigger: " + idea.entry.usd(6) + " · confirmation is still missing.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(if (idea.side == "WAIT") "Trend, momentum and liquidity filters are not aligned. No entry is permitted."
                    else "The risk plan failed validation or has a blocking warning: " + idea.warning,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (density == "Expanded") HistoricalOutcomePanel(idea) else Text(historyOneLine(idea), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) { Text(if (expanded && density == "Compact") "Show less" else if (expanded) "Hide analysis" else "Why this result?") }
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
            Text("Historical replay uses closed candles and a 24-hour time stop. It is a simplified estimate, not a live fill simulation; fees, funding payments, spread and slippage can reduce actual results.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val timeZoneId: String,
    val openHour: Int,
    val closeHour: Int
)

private data class TradeSessionStatus(
    val slot: TradeSessionSlot,
    val isActive: Boolean,
    val nextChangeAtMillis: Long
)

private fun tradeSessionStatus(slot: TradeSessionSlot, nowMillis: Long): TradeSessionStatus {
    val zone = TimeZone.getTimeZone(slot.timeZoneId)
    val localNow = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
    val minute = localNow.get(Calendar.HOUR_OF_DAY) * 60 + localNow.get(Calendar.MINUTE)
    val active = minute in (slot.openHour * 60) until (slot.closeHour * 60)
    val nextChange = (localNow.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, if (active) slot.closeHour else slot.openHour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
    }
    return TradeSessionStatus(slot, active, nextChange.timeInMillis)
}

@Composable
private fun TradeSessionTimingCard() {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val sessions = remember {
        listOf(
            TradeSessionSlot("Tokyo / Asia", "Asia/Tokyo", 9, 18),
            TradeSessionSlot("London", "Europe/London", 8, 17),
            TradeSessionSlot("New York", "America/New_York", 8, 17)
        )
    }
    val statuses = sessions.map { tradeSessionStatus(it, nowMillis) }
    val active = statuses.filter { it.isActive }
    val next = statuses.filterNot { it.isActive }.minByOrNull { it.nextChangeAtMillis }
    val ist = remember { TimeZone.getTimeZone("Asia/Kolkata") }
    val timeFormat = remember {
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = ist }
    }
    val currentTime = remember(nowMillis) { timeFormat.format(Date(nowMillis)) }
    val nextTime = remember(next?.nextChangeAtMillis) {
        next?.let { timeFormat.format(Date(it.nextChangeAtMillis)) } ?: "—"
    }
    val currentName = when {
        active.isEmpty() -> "No active session"
        else -> active.joinToString(" + ") { it.slot.name }
    }
    val currentAccent = Color(0xFF65D49A)
    val nextAccent = Color(0xFF79B8F5)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (active.isEmpty()) "CURRENT · CLOSED" else "CURRENT · OPEN",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else currentAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    currentName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    currentTime,
                    style = MaterialTheme.typography.titleMedium,
                    color = currentAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier.width(1.dp).height(42.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "NEXT · UPCOMING",
                    style = MaterialTheme.typography.labelSmall,
                    color = nextAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    next?.slot?.name ?: "No upcoming session",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    nextTime,
                    style = MaterialTheme.typography.titleMedium,
                    color = nextAccent,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
