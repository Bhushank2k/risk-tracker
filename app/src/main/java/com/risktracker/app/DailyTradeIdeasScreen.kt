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
import androidx.compose.runtime.collectAsState
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
import java.util.concurrent.ConcurrentHashMap

private data class ScanTicker(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double, val volatilityPct: Double, val atrPct: Double = 0.0, val relativeVolume: Double = 0.0)
private data class ScanCandle(val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long, val open: Double = close)
internal data class TradeIdea(
    val symbol: String, val side: String, val score: Int, val volume: Double, val change: Double,
    val last: Double, val entry: Double, val stop: Double, val target1: Double, val target2: Double,
    val entryTriggered: Boolean,
    val rsi: Double, val volumeRatio: Double, val bias4h: String, val bias1h: String,
    val setup: String, val reasons: List<String>, val warning: String, val horizon: String,
    val bias5m: String, val spreadBps: Double?, val bookImbalance: Double?,
    val fundingRate: Double?, val oiChangePct: Double?, val backtest: TradeIdeaBacktestStats,
    val volatilityPct: Double = 0.0,
    val strategyChecks: List<Pair<String, Boolean?>> = emptyList(),
    val riskCodes: Set<TradeIdeaRiskCode> = emptySet(),
    val bidDepthUsd: Double? = null,
    val askDepthUsd: Double? = null
)
internal data class MarketMicrostructure(
    val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double?, val oiChangePct: Double?,
    val bidDepthUsd: Double? = null, val askDepthUsd: Double? = null
)

internal object TradeIdeaCache {
    private const val PREFS = "market_scan_cache_v2"
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
                bidDepthUsd = x.optNullableDouble("bidDepthUsd"), askDepthUsd = x.optNullableDouble("askDepthUsd"),
                backtest = TradeIdeaBacktestStats(
                    trades = stats.optInt("trades"),
                    wins = stats.optInt("wins"),
                    averageR = stats.optDouble("averageR"),
                    profitFactor = stats.optDouble("profitFactor", Double.NaN).takeIf { it.isFinite() },
                    maxDrawdownR = stats.optDouble("maxDrawdownR", 0.0),
                    longTrades = stats.optInt("longTrades"),
                    longWins = stats.optInt("longWins"),
                    longAverageR = stats.optDouble("longAverageR", Double.NaN).takeIf { it.isFinite() },
                    shortTrades = stats.optInt("shortTrades"),
                    shortWins = stats.optInt("shortWins"),
                    shortAverageR = stats.optDouble("shortAverageR", Double.NaN).takeIf { it.isFinite() },
                    trendTrades = stats.optInt("trendTrades"),
                    trendWins = stats.optInt("trendWins"),
                    trendAverageR = stats.optDouble("trendAverageR", Double.NaN).takeIf { it.isFinite() },
                    rangeTrades = stats.optInt("rangeTrades"),
                    rangeWins = stats.optInt("rangeWins"),
                    rangeAverageR = stats.optDouble("rangeAverageR", Double.NaN).takeIf { it.isFinite() },
                    breakoutTrades = stats.optInt("breakoutTrades"), breakoutWins = stats.optInt("breakoutWins"),
                    breakoutAverageR = stats.optDouble("breakoutAverageR", Double.NaN).takeIf { it.isFinite() },
                    pullbackTrades = stats.optInt("pullbackTrades"), pullbackWins = stats.optInt("pullbackWins"),
                    pullbackAverageR = stats.optDouble("pullbackAverageR", Double.NaN).takeIf { it.isFinite() },
                    reversalTrades = stats.optInt("reversalTrades"), reversalWins = stats.optInt("reversalWins"),
                    reversalAverageR = stats.optDouble("reversalAverageR", Double.NaN).takeIf { it.isFinite() }
                ),
                volatilityPct = x.optDouble("volatilityPct", 0.0),
                strategyChecks = x.optJSONArray("strategyChecks")?.let { checks ->
                    (0 until checks.length()).mapNotNull { index ->
                        checks.optJSONObject(index)?.let { item ->
                            val passed: Boolean? = if (!item.has("passed") || item.isNull("passed")) null else item.optBoolean("passed")
                            item.optString("label").takeIf { it.isNotBlank() }?.let { it to passed }
                        }
                    }
                } ?: emptyList(),
                riskCodes = if (x.has("riskCodes")) {
                    x.optJSONArray("riskCodes")?.let { codes ->
                        (0 until codes.length()).mapNotNull { index ->
                            runCatching { TradeIdeaRiskCode.valueOf(codes.optString(index)) }.getOrNull()
                        }.toSet()
                    } ?: emptySet()
                } else if (x.optString("warning").startsWith("BLOCK:", ignoreCase = true)) {
                    setOf(TradeIdeaRiskCode.LEGACY_BLOCKING_WARNING)
                } else emptySet()
            )
        }
        if (timestamp <= 0L) null else timestamp to ideas
    }.getOrNull()

    fun save(context: android.content.Context, updatedAt: Long, ideas: List<TradeIdea>) {
        runCatching {
            val rows = JSONArray()
            ideas.forEach { idea ->
                val reasons = JSONArray().apply { idea.reasons.forEach { put(it) } }
                val stats = JSONObject().put("trades", idea.backtest.trades)
                    .put("wins", idea.backtest.wins).put("averageR", idea.backtest.averageR)
                    .put("profitFactor", idea.backtest.profitFactor ?: JSONObject.NULL)
                    .put("maxDrawdownR", idea.backtest.maxDrawdownR)
                    .put("longTrades", idea.backtest.longTrades).put("longWins", idea.backtest.longWins)
                    .put("longAverageR", idea.backtest.longAverageR ?: JSONObject.NULL)
                    .put("shortTrades", idea.backtest.shortTrades).put("shortWins", idea.backtest.shortWins)
                    .put("shortAverageR", idea.backtest.shortAverageR ?: JSONObject.NULL)
                    .put("trendTrades", idea.backtest.trendTrades).put("trendWins", idea.backtest.trendWins)
                    .put("trendAverageR", idea.backtest.trendAverageR ?: JSONObject.NULL)
                    .put("rangeTrades", idea.backtest.rangeTrades).put("rangeWins", idea.backtest.rangeWins)
                    .put("rangeAverageR", idea.backtest.rangeAverageR ?: JSONObject.NULL)
                    .put("breakoutTrades", idea.backtest.breakoutTrades).put("breakoutWins", idea.backtest.breakoutWins)
                    .put("breakoutAverageR", idea.backtest.breakoutAverageR ?: JSONObject.NULL)
                    .put("pullbackTrades", idea.backtest.pullbackTrades).put("pullbackWins", idea.backtest.pullbackWins)
                    .put("pullbackAverageR", idea.backtest.pullbackAverageR ?: JSONObject.NULL)
                    .put("reversalTrades", idea.backtest.reversalTrades).put("reversalWins", idea.backtest.reversalWins)
                    .put("reversalAverageR", idea.backtest.reversalAverageR ?: JSONObject.NULL)
                val strategyChecks = JSONArray().apply {
                    idea.strategyChecks.forEach { (label, passed) ->
                        put(JSONObject().put("label", label).put("passed", passed ?: JSONObject.NULL))
                    }
                }
                val riskCodes = JSONArray().apply { idea.riskCodes.forEach { put(it.name) } }
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
                    .put("bidDepthUsd", idea.bidDepthUsd ?: JSONObject.NULL)
                    .put("askDepthUsd", idea.askDepthUsd ?: JSONObject.NULL)
                    .put("fundingRate", idea.fundingRate ?: JSONObject.NULL)
                    .put("oiChangePct", idea.oiChangePct ?: JSONObject.NULL)
                    .put("backtest", stats).put("volatilityPct", idea.volatilityPct).put("strategyChecks", strategyChecks)
                    .put("riskCodes", riskCodes))
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

/** Owns scans independently of the Compose destination lifecycle. */
internal data class TradeIdeaScanUiState(
    val initialized: Boolean = false, val ideas: List<TradeIdea> = emptyList(),
    val loading: Boolean = false, val status: String = "Preparing market scanner…",
    val error: String? = null, val updatedAt: Long? = null,
    val partial: Boolean = false, val showingCached: Boolean = false
)

internal object TradeIdeaScanManager {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TradeIdeaScanUiState())
    val state: kotlinx.coroutines.flow.StateFlow<TradeIdeaScanUiState> = mutableState
    private var scanJob: Job? = null

    fun initialize(context: android.content.Context) {
        val appContext = context.applicationContext
        if (mutableState.value.initialized) return
        BinanceMarketScanner.initialize(appContext)
        val cached = TradeIdeaCache.load(appContext)
        mutableState.value = mutableState.value.copy(
            initialized = true, ideas = cached?.second ?: emptyList(), updatedAt = cached?.first,
            showingCached = cached != null,
            status = if (cached != null) "Showing saved scan while fresh Binance data loads…"
                else "Refresh to scan live Binance USDT-M perpetual markets."
        )
        val age = cached?.first?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        if (age > 10 * 60_000L) refresh(appContext)
    }

    fun refresh(context: android.content.Context) {
        val appContext = context.applicationContext
        if (scanJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(
            loading = true, error = null, partial = false,
            status = if (mutableState.value.ideas.isEmpty()) "Connecting to Binance public market data…"
                else "Refreshing market data · saved ideas remain visible…"
        )
        TradeIdeaScanHealthStore.started(appContext)
        scanJob = managerScope.launch {
            try {
                val freshIdeas = BinanceMarketScanner.scan(
                    progress = { message -> mutableState.value = mutableState.value.copy(status = message) },
                    onPartialResults = { partial ->
                        mutableState.value = mutableState.value.copy(
                            ideas = partial, updatedAt = System.currentTimeMillis(), partial = true,
                            showingCached = false,
                            status = "Partial scan · " + partial.size + " ideas received; scan is still running…"
                        )
                    }
                )
                val completedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) { TradeIdeaCache.save(appContext, completedAt, freshIdeas) }
                TradeIdeaScanHealthStore.succeeded(appContext, at = completedAt)
                mutableState.value = mutableState.value.copy(
                    ideas = freshIdeas, updatedAt = completedAt, partial = false, showingCached = false,
                    error = null, loading = false,
                    status = if (freshIdeas.isEmpty()) "Scan complete · no setups passed the current filters."
                        else "Scan complete · " + freshIdeas.size + " trade ideas from eligible markets."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Market scan failed. Check your connection and retry."
                TradeIdeaScanHealthStore.failed(appContext, message)
                mutableState.value = mutableState.value.copy(
                    error = message, loading = false,
                    status = if (mutableState.value.partial) "Scan incomplete · showing timestamped partial results; no completed snapshot was saved."
                        else "Scan could not be completed."
                )
            } finally { mutableState.value = mutableState.value.copy(loading = false) }
        }
    }
}

internal object BinanceMarketScanner {
    private const val BASE = "https://fapi.binance.com"
    private val scanMutex = Mutex()
    @Volatile private var restBlockedUntilMs = 0L
    @Volatile private var restLimitPrefs: android.content.SharedPreferences? = null
    private data class CachedCandles(val fetchedAt: Long, val rows: List<ScanCandle>)
    private val candleCache = ConcurrentHashMap<String, CachedCandles>()
    private val candleFetchSlots = Semaphore(4)
    private val candleRequestPacer = Mutex()
    @Volatile private var nextCandleRequestAtMs = 0L

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
        var lastConnectionFailure: java.io.IOException? = null
        for (attempt in 0..2) {
            ensureRestAllowed()
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 16000
            c.requestMethod = "GET"
            try {
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
                return body
            } catch (e: BinanceRateLimitException) {
                throw e
            } catch (e: java.io.IOException) {
                lastConnectionFailure = e
                if (attempt == 2) throw e
                try {
                    // GET requests are safe to retry after transient app-resume socket failures.
                    Thread.sleep(500L * (1L shl attempt))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("Binance request retry interrupted", interrupted)
                }
            } finally {
                c.disconnect()
            }
        }
        throw lastConnectionFailure ?: java.io.IOException("Binance request failed without a response")
    }

    private fun candles(symbol: String, interval: String, limit: Int): List<ScanCandle> {
        val raw = JSONArray(get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit))
        val now = System.currentTimeMillis()
        return (0 until raw.length()).mapNotNull { i ->
            val a = raw.optJSONArray(i) ?: return@mapNotNull null
            runCatching { ScanCandle(a.getString(2).toDouble(), a.getString(3).toDouble(),
                a.getString(4).toDouble(), a.getString(5).toDouble(), a.getLong(6), a.getString(1).toDouble()) }.getOrNull()
        }.filter { it.closeTime < now && it.close > 0.0 }
    }

    private suspend fun cachedCandles(symbol: String, interval: String, limit: Int): List<ScanCandle> =
        candleFetchSlots.withPermit {
            val key = "$symbol|$interval|$limit"
            val ttlMs = when (interval) {
                "5m" -> 90_000L
                "15m" -> 4 * 60_000L
                "1h" -> 20 * 60_000L
                "4h" -> 90 * 60_000L
                else -> 60_000L
            }
            val now = System.currentTimeMillis()
            candleCache[key]?.takeIf { now - it.fetchedAt in 0..ttlMs && it.rows.isNotEmpty() }?.let {
                return@withPermit it.rows
            }
            candleRequestPacer.withLock {
                val waitMs = nextCandleRequestAtMs - System.currentTimeMillis()
                if (waitMs > 0L) delay(waitMs)
                nextCandleRequestAtMs = System.currentTimeMillis() + 250L
            }
            val rows = withContext(Dispatchers.IO) { candles(symbol, interval, limit) }
            if (rows.isNotEmpty()) candleCache[key] = CachedCandles(System.currentTimeMillis(), rows)
            rows
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

    private suspend fun openInterestChangePct(symbol: String): Double? {
        return try {
            // Pace the optional per-symbol historical OI request through the same REST limiter.
            candleRequestPacer.withLock {
                val waitMs = nextCandleRequestAtMs - System.currentTimeMillis()
                if (waitMs > 0L) delay(waitMs)
                nextCandleRequestAtMs = System.currentTimeMillis() + 250L
            }
            val rows = withContext(Dispatchers.IO) {
                JSONArray(get("/futures/data/openInterestHist?symbol=$symbol&period=15m&limit=2"))
            }
            if (rows.length() < 2) return null
            val previous = rows.optJSONObject(0)?.optString("sumOpenInterestValue")?.toDoubleOrNull() ?: return null
            val latest = rows.optJSONObject(1)?.optString("sumOpenInterestValue")?.toDoubleOrNull() ?: return null
            if (previous <= 0.0 || !previous.isFinite() || !latest.isFinite()) null
            else ((latest - previous) / previous * 100.0).takeIf { it.isFinite() }
        } catch (e: BinanceRateLimitException) {
            throw e
        } catch (_: Exception) {
            null
        }
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

    suspend fun scan(
        progress: (String) -> Unit,
        onPartialResults: (List<TradeIdea>) -> Unit = {},
        ): List<TradeIdea> = scanMutex.withLock { withContext(Dispatchers.IO) {
        suspend fun report(message: String) = withContext(Dispatchers.Main.immediate) { progress(message) }
        report("Loading active USDT perpetual contracts (one REST bootstrap)…")
        val info = JSONObject(get("/fapi/v1/exchangeInfo")).optJSONArray("symbols") ?: JSONArray()
        val allowed = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter {
            it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" && it.optString("quoteAsset") == "USDT"
        }.map { it.optString("symbol") }.toSet()
        report("Found ${allowed.size} active USDT-M perpetual pairs. Loading 24h market statistics…")
        val all = JSONArray(get("/fapi/v1/ticker/24hr"))
        val prefiltered = (0 until all.length()).mapNotNull { i ->
            val x = all.optJSONObject(i) ?: return@mapNotNull null
            val symbol = x.optString("symbol")
            val quote = x.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            val last = x.optString("lastPrice").toDoubleOrNull() ?: 0.0
            if (symbol !in allowed || !isTradeIdeaVolumeEligible(quote) || last <= 0.0) null
            else {
                val change = x.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                val high = x.optString("highPrice").toDoubleOrNull() ?: last
                val low = x.optString("lowPrice").toDoubleOrNull() ?: last
                val rangePct = ((high - low) / last * 100.0).coerceAtLeast(0.0)
                ScanTicker(symbol, quote, change, last, rangePct)
            }
        }.sortedByDescending { it.volatilityPct + abs(it.changePct) * 0.5 }
        report("24h filter: ${prefiltered.size}/${allowed.size} active pairs meet the $2M quote-volume floor; spread and two-sided depth remain hard gates.")
        if (prefiltered.isEmpty()) throw IllegalStateException("No liquid USDT perpetual pairs were returned.")

        // Keep every volume-eligible contract in the scan. Use only bulk 24h ticker
        // fields to prioritize evaluation; expensive candle history is fetched only
        // when a symbol reaches the bounded deep-validation queue.
        val candidates = prefiltered.sortedByDescending {
            quickScanMarketPriority(it.volatilityPct, it.changePct, it.quoteVolume)
        }
        report("Quick filter complete: ${candidates.size}/${allowed.size} active USDT perpetuals queued for full confirmation. Loading BTC context…")

        // BTC trend is a broad-market risk filter; neutral conditions do not impose a directional bias.
        val btcH4Job = async { cachedCandles("BTCUSDT", "4h", 260) }
        val btcH1Job = async { cachedCandles("BTCUSDT", "1h", 260) }
        val btcH4 = btcH4Job.await()
        val btcH1 = btcH1Job.await()
        val btcBias = btcRegime(btcH4, btcH1)

        // Aggregate ticker/book feeds cover all contracts. Subscribe to order-book
        // depth in supported batches, then evaluate each batch with bounded parallelism.
        val depthBatchSize = QUICK_SCAN_DEPTH_BATCH_SIZE
        val deepScanConcurrency = QUICK_SCAN_DEEP_SCAN_CONCURRENCY
        report("Full confirmation starting: 0/${candidates.size} pairs · BTC regime: $btcBias…")
        val results = mutableListOf<TradeIdea>()
        var marketsWithPlans = 0
        var marketsWithoutPlans = 0
        var blockedPlans = 0
        var gateDiagnostics = ScanGateDiagnostics()
        var marketDataFailures = 0
        var lastMarketFailure: String? = null
        var completedPairs = 0
        data class DeepScanOutcome(val symbol: String, val ideas: List<TradeIdea>, val failure: String? = null)

        for (depthBatchTickers in candidates.chunked(depthBatchSize)) {
            coroutineContext.ensureActive()
            val depthSymbols = depthBatchTickers.map { it.symbol }
            BinanceFuturesStream.start(depthSymbols)
            val depthReady = BinanceFuturesStream.awaitDepth(depthSymbols, timeoutMs = 5_000L)
            report("Order-book depth ready: ${depthReady.size}/${depthSymbols.size} pairs · validating batch…")

            for (workBatch in depthBatchTickers.chunked(deepScanConcurrency)) {
                coroutineContext.ensureActive()
                val outcomes = coroutineScope {
                    workBatch.map { ticker ->
                        async {
                            try {
                                // Fetch each symbol's intervals concurrently. The shared semaphore
                                // and pacer bound aggregate REST load across all four workers.
                                val candleSets = supervisorScope {
                                    val h4Job = async { cachedCandles(ticker.symbol, "4h", 260) }
                                    val h1Job = async { cachedCandles(ticker.symbol, "1h", 260) }
                                    val d1Job = async { cachedCandles(ticker.symbol, "1d", 260) }
                                    val m15Job = async { cachedCandles(ticker.symbol, "15m", 260) }
                                    val m5Job = async { cachedCandles(ticker.symbol, "5m", 220) }
                                    val m1Job = async { cachedCandles(ticker.symbol, "1m", 120) }
                                    listOf(
                                        h4Job.await(), h1Job.await(), d1Job.await(),
                                        m15Job.await(), m5Job.await(), m1Job.await()
                                    )
                                }
                                val h4 = candleSets[0]
                                val h1 = candleSets[1]
                                val d1 = candleSets[2]
                                val m15 = candleSets[3]
                                val m5 = candleSets[4]
                                val m1 = candleSets[5]
                                // OI is supplementary context, not a Confluence hard gate.
                                // Fetch it only after a technical setup exists, not for every market.
                                val micro = BinanceFuturesStream.microstructure(ticker.symbol)
                                val liveTicker = BinanceFuturesStream.latestTicker(ticker.symbol)
                                val live = ticker.copy(
                                    last = liveTicker?.first ?: ticker.last,
                                    quoteVolume = liveTicker?.second ?: ticker.quoteVolume,
                                    changePct = liveTicker?.third ?: ticker.changePct
                                )
                                val initialIdeas = evaluateModes(live, d1, h4, h1, m15, m5, m1, micro, btcBias, btcH4)
                                val oiChange = if (initialIdeas.isNotEmpty()) openInterestChangePct(ticker.symbol) else null
                                val ideas = if (oiChange == null) initialIdeas else initialIdeas.map { idea ->
                                    idea.copy(
                                        oiChangePct = oiChange,
                                        reasons = idea.reasons + ("Open interest changed " +
                                            "%+.2f".format(Locale.US, oiChange) + "% over the latest 15M samples")
                                    )
                                }
                                DeepScanOutcome(ticker.symbol, ideas)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: BinanceRateLimitException) {
                                throw e
                            } catch (e: Exception) {
                                DeepScanOutcome(ticker.symbol, emptyList(),
                                    "${ticker.symbol}: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }.awaitAll()
                }

                outcomes.forEach { outcome ->
                    if (outcome.failure != null) {
                        marketDataFailures++
                        lastMarketFailure = outcome.failure
                    } else if (outcome.ideas.isEmpty()) {
                        marketsWithoutPlans++
                    } else {
                        marketsWithPlans++
                        gateDiagnostics = gateDiagnostics.record(outcome.ideas)
                        blockedPlans = gateDiagnostics.blockedPlans
                        results.addAll(outcome.ideas)
                    }
                }
                completedPairs += workBatch.size
                if (results.isNotEmpty()) {
                    val snapshot = results.toList()
                    withContext(Dispatchers.Main.immediate) { onPartialResults(snapshot) }
                }
                report("Scanned ${completedPairs}/${candidates.size} pairs · ${results.size} setups · ${blockedPlans} blocked · ${marketsWithoutPlans} no setup · ${marketDataFailures} data errors · gates: ${gateDiagnostics.topGateSummary()}…")
            }
        }
        if (results.isEmpty()) {
            if (isCompletedEmptyTradeIdeaScan(candidates.size, marketDataFailures)) {
                // A clean scan with no qualifying setups is a valid market outcome, not a
                // transport failure. Persist the empty snapshot so stale ideas cannot linger.
                report("Scan complete · no setups passed the current filters across ${candidates.size} evaluated pairs. No trade is a valid result.")
                return@withContext emptyList()
            }
            val detail = lastMarketFailure?.let { " Last market error: $it" }
                ?: " Candle history was fetched, but every strategy rejected the available series."
            throw IllegalStateException(
                "Scan found no technical setups in ${candidates.size} evaluated pairs (${prefiltered.size} passed the 24h liquidity filter). " +
                    "Pairs with setups: $marketsWithPlans; no setup: $marketsWithoutPlans; data errors: $marketDataFailures; " +
                    "candidate universe: all active USDT perpetuals passing the $2M quote-volume prefilter.$detail"
            )
        }
        report("Scan complete · ${results.size} plans · ${gateDiagnostics.blockedPlans} blocked · ${marketsWithoutPlans} no setup · top gate hits: ${gateDiagnostics.topGateSummary()}. Counts are per returned mode plan, not unique symbols.")
        results.sortedWith(compareByDescending<TradeIdea> { it.score }.thenByDescending { it.volatilityPct })
    } }

    private fun evaluateModes(
        t: ScanTicker, d1: List<ScanCandle>, h4: List<ScanCandle>, h1: List<ScanCandle>,
        m15: List<ScanCandle>, m5: List<ScanCandle>, m1: List<ScanCandle>,
        micro: MarketMicrostructure, btcBias: String, btcH4: List<ScanCandle>
    ): List<TradeIdea> {
        fun candles(rows: List<ScanCandle>) = rows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
        val market = V2Microstructure(micro.spreadBps, micro.bookImbalance, micro.fundingRate, micro.oiChangePct, micro.bidDepthUsd, micro.askDepthUsd)
        val scalp = ConfluenceScannerV4.evaluate(candles(h1), candles(m15), candles(m5), candles(m1),
            market, t.quoteVolume, btcBias, "Scalp")?.let {
            buildIdea(t, it, m5, m1, h1, m15, micro, swingMode = false,
                trendRows = h1, vwapBars = 288, setupInterval = "5M", executionInterval = "1M", trendInterval = "1H", runBacktest = true)
        }
        val intraday = ConfluenceScannerV4.evaluate(candles(h4), candles(h1), candles(m15), candles(m5),
            market, t.quoteVolume, btcBias, "Intraday")?.let {
            buildIdea(t, it, m15, m5, h4, h1, micro, swingMode = false,
                trendRows = h1, vwapBars = 96, setupInterval = "15M", executionInterval = "5M", trendInterval = "1H", runBacktest = true)
        }
        val swing = ConfluenceScannerV4.evaluate(candles(d1), candles(h4), candles(h1), candles(m15),
            market, t.quoteVolume, btcBias, "Swing")?.let {
            buildIdea(t, it, h1, m15, d1, h4, micro, swingMode = true,
                trendRows = d1, vwapBars = 24, setupInterval = "1H", executionInterval = "15M", trendInterval = "1D", runBacktest = true)
        }
        return listOfNotNull(scalp, intraday, swing)
    }

    private fun buildIdea(t: ScanTicker, plan: V2TradePlan, setupRows: List<ScanCandle>, executionRows: List<ScanCandle>,
                          h4: List<ScanCandle>, h1: List<ScanCandle>, micro: MarketMicrostructure, swingMode: Boolean, runBacktest: Boolean = true,
                          trendRows: List<ScanCandle> = h1, vwapBars: Int = 96, setupInterval: String = "15M",
                          executionInterval: String = "5M", trendInterval: String = "1H"): TradeIdea {
        val rsiValue = rsi(setupRows.map { it.close }) ?: 50.0
        val priorVolume = setupRows.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (priorVolume > 0.0) setupRows.last().volume / priorVolume else 0.0
        val bias4 = plan.bias4h
        val bias1 = plan.bias1h
        val bias5 = plan.bias5m
        val reasons = plan.reasons.toMutableList()
        micro.oiChangePct?.let { reasons.add("Open interest changed " + "%+.2f".format(Locale.US, it) + "% over the latest 15M samples") }
        // Historical replay is supplementary; a replay edge case must never discard a live setup.
        val replay = if (!runBacktest) TradeIdeaBacktestStats() else runCatching {
            TradeIdeasV2.backtest(
                h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                setupRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                executionRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                maxHoldingBars = when { plan.horizon.startsWith("Scalp", true) -> 48; plan.horizon.startsWith("Swing", true) -> 72; else -> 96 },
                setupBarMinutes = when { plan.horizon.startsWith("Scalp", true) -> 5; plan.horizon.startsWith("Swing", true) -> 60; else -> 15 },
                executionBarMinutes = when { plan.horizon.startsWith("Scalp", true) -> 1; plan.horizon.startsWith("Swing", true) -> 15; else -> 5 }
            )
        }.getOrDefault(TradeIdeaBacktestStats())
        val strategyChecks = buildStrategyChecks(plan, setupRows, executionRows, trendRows, vwapBars, setupInterval, executionInterval, trendInterval)
        return TradeIdea(
            t.symbol, plan.side, plan.score, t.quoteVolume, t.changePct, t.last,
            plan.entry, plan.stop, plan.target1, plan.target2, plan.entryTriggered,
            rsiValue, volumeRatio, bias4, bias1, plan.setup, reasons, plan.warning,
            plan.horizon, bias5, micro.spreadBps, micro.bookImbalance, micro.fundingRate,
            micro.oiChangePct, replay, t.volatilityPct,
            strategyChecks, plan.riskCodes, micro.bidDepthUsd, micro.askDepthUsd
        )
    }

    private fun buildStrategyChecks(
        plan: V2TradePlan,
        setupRows: List<ScanCandle>,
        executionRows: List<ScanCandle>,
        trendRows: List<ScanCandle>,
        vwapBars: Int,
        setupInterval: String,
        executionInterval: String,
        trendInterval: String
    ): List<Pair<String, Boolean?>> {
        val isLong = plan.side == "LONG"
        if (!isLong && plan.side != "SHORT") return emptyList()
        val trendAligned = if (isLong) hasHigherHighsAndLows(trendRows) else hasLowerHighsAndLows(trendRows)
        val closes = setupRows.map { it.close }
        val ema20 = ema(closes, 20)
        val lastSetup = setupRows.lastOrNull()
        val typicalVolume = setupRows.takeLast(vwapBars).sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume }
        val totalVolume = setupRows.takeLast(vwapBars).sumOf { it.volume }
        val vwap = if (totalVolume > 0.0) typicalVolume / totalVolume else null
        val vwapAligned = lastSetup?.let { if (isLong) vwap != null && it.close > vwap else vwap != null && it.close < vwap }
        val emaAligned = lastSetup?.let { if (isLong) ema20 != null && it.close > ema20 else ema20 != null && it.close < ema20 }
        val liquiditySweep = hasScanLiquiditySweep(setupRows, isLong)
        val structureAligned = plan.riskCodes.none { it == TradeIdeaRiskCode.STRUCTURE_NOT_CONFIRMED }
        val orderBookValid = plan.riskCodes.none { it == TradeIdeaRiskCode.LOW_LIQUIDITY }
        val candle = executionRows.lastOrNull()
        val priorAverageVolume = executionRows.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (priorAverageVolume > 0.0) (candle?.volume ?: 0.0) / priorAverageVolume else 0.0
        val candleAligned = candle?.let {
            val directionOk = if (isLong) it.close > it.open else it.close < it.open
            val requiredVolume = if (plan.setup.contains("breakout", ignoreCase = true)) confluenceExecutionVolumeThreshold(plan.setup) else 0.0
            directionOk && volumeRatio >= requiredVolume
        }
        val recent = executionRows.dropLast(1).takeLast(9)
        val swingLow = recent.minOfOrNull { it.low }
        val swingHigh = recent.maxOfOrNull { it.high }
        val stopAligned = if (isLong) swingLow != null && plan.stop < swingLow
            else swingHigh != null && plan.stop > swingHigh
        val risk = abs(plan.entry - plan.stop)
        val grossR = if (risk > 0.0) abs(plan.target1 - plan.entry) / risk else 0.0
        val netR = if (risk > 0.0) tradeIdeaNetReturnR(grossR, plan.entry, risk, heldBars = 8, setupBarMinutes = when { plan.horizon.startsWith("Scalp", true) -> 5; plan.horizon.startsWith("Swing", true) -> 60; else -> 15 }) else Double.NaN
        return listOf(
            ("$trendInterval trend structure: " + (if (isLong) "higher highs + higher lows" else "lower highs + lower lows")) to trendAligned,
            "1H swing structure aligned and 4H context not opposing" to structureAligned,
            "Live spread, 5-level depth and directional book imbalance" to orderBookValid,
            "$setupInterval liquidity sweep + reclaim (supporting evidence)" to liquiditySweep,
            ("$setupInterval VWAP: price " + (if (isLong) "above" else "below") + " rolling 24H VWAP") to vwapAligned,
            ("$setupInterval EMA20: price " + (if (isLong) "above" else "below") + " EMA20") to emaAligned,
            "$executionInterval directional confirmation candle (breakout volume >= 1.5x)" to candleAligned,
            ("Stop-loss beyond recent $executionInterval swing " + (if (isLong) "low" else "high")) to stopAligned,
            ("Target 1 minimum 1:2.0 estimated net risk/reward (" + "%.2f".format(Locale.US, netR) + "R net)") to (netR.isFinite() && netR >= 2.0)
        )
    }

    private fun hasScanLiquiditySweep(rows: List<ScanCandle>, isLong: Boolean, lookback: Int = 20): Boolean {
        if (rows.size <= lookback || lookback < 2) return false
        val current = rows.last()
        val prior = rows.subList(rows.size - lookback - 1, rows.lastIndex)
        if (prior.isEmpty()) return false
        val body = abs(current.close - current.open)
        return if (isLong) {
            val sweptLow = prior.minOf { it.low }
            val lowerWick = minOf(current.open, current.close) - current.low
            current.low < sweptLow && current.close > sweptLow && lowerWick > 0.0 && lowerWick >= body * 0.5
        } else {
            val sweptHigh = prior.maxOf { it.high }
            val upperWick = current.high - maxOf(current.open, current.close)
            current.high > sweptHigh && current.close < sweptHigh && upperWick > 0.0 && upperWick >= body * 0.5
        }
    }

    private fun hasHigherHighsAndLows(rows: List<ScanCandle>): Boolean =
        hasOrderedSwingPoints(rows, higher = true)

    private fun hasLowerHighsAndLows(rows: List<ScanCandle>): Boolean =
        hasOrderedSwingPoints(rows, higher = false)

    private fun hasOrderedSwingPoints(rows: List<ScanCandle>, higher: Boolean): Boolean {
        if (rows.size < 12) return false
        val window = rows.takeLast(60)
        val highs = mutableListOf<Double>()
        val lows = mutableListOf<Double>()
        for (i in 2 until window.size - 2) {
            val current = window[i]
            if ((i - 2 until i).all { window[it].high < current.high } &&
                (i + 1..i + 2).all { window[it].high <= current.high }) highs.add(current.high)
            if ((i - 2 until i).all { window[it].low > current.low } &&
                (i + 1..i + 2).all { window[it].low >= current.low }) lows.add(current.low)
        }
        if (highs.size < 2 || lows.size < 2) return false
        val lastHighs = highs.takeLast(2)
        val lastLows = lows.takeLast(2)
        return if (higher) lastHighs[1] > lastHighs[0] && lastLows[1] > lastLows[0]
            else lastHighs[1] < lastHighs[0] && lastLows[1] < lastLows[0]
    }
}

@Composable
fun DailyTradeIdeasScreen() {
    val context = LocalContext.current
    val scanUi by TradeIdeaScanManager.state.collectAsState()
    val ideas = scanUi.ideas
    val loading = scanUi.loading
    val status = scanUi.status
    val error = scanUi.error
    val updatedAt = scanUi.updatedAt
    val partialScanVisible = scanUi.partial
    val showingCached = scanUi.showingCached
    val trackingPrefs = remember { context.getSharedPreferences("trade_idea_tracking_v1", android.content.Context.MODE_PRIVATE) }
    fun readTrackedPlanKeys(rows: List<TradeIdea>): Set<String> {
        val all = trackingPrefs.all
        val tracked = all.keys.filter { key -> key.startsWith("plan_") && (all[key] as? Float ?: 0f) > 0f }.toMutableSet()
        rows.groupBy { it.symbol }.forEach { (symbol, plans) ->
            val legacyKey = "entry_" + symbol
            val legacyEntry = all[legacyKey] as? Float
            if (plans.isNotEmpty() && legacyEntry != null && legacyEntry > 0f) {
                // Old symbol-wide state represented one tracked entry. Migrate once, not to every horizon.
                val chosen = plans.firstOrNull { it.horizon.startsWith("Intraday", ignoreCase = true) } ?: plans.first()
                val planKey = "plan_" + tradeIdeaTrackingId(chosen)
                trackingPrefs.edit().putFloat(planKey, legacyEntry).remove(legacyKey).apply()
                tracked.add(planKey)
            }
        }
        return tracked
    }
    var scanHealth by remember { mutableStateOf(TradeIdeaScanHealthStore.load(context)) }
    var filter by remember { mutableStateOf("All") }
    var horizonFilter by remember { mutableStateOf("All") }
    var riskSettings by remember { mutableStateOf(EntryRiskSettingsStore.load(context)) }
    val density = "Compact"
    var scanExpanded by remember { mutableStateOf(false) }
    var trackedPlanKeys by remember { mutableStateOf(readTrackedPlanKeys(ideas)) }
    LaunchedEffect(ideas) { trackedPlanKeys = readTrackedPlanKeys(ideas) }
    var livePrices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var paperRiskStatus by remember { mutableStateOf(TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = riskSettings.riskPercent)) }
    fun updateRiskSettings(next: EntryRiskSettings) {
        riskSettings = next
        EntryRiskSettingsStore.save(context, next)
        paperRiskStatus = TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = next.riskPercent)
    }
    var readyAlertsEnabled by remember { mutableStateOf(TradeIdeaNotifications.isReadyEnabled(context)) }
    var entryAlertsEnabled by remember { mutableStateOf(TradeIdeaNotifications.isEntryEnabled(context)) }
    fun enableReadyAlerts() {
        TradeIdeaNotifications.setReadyEnabled(context, true)
        TradeIdeaNotifications.schedule(context)
        readyAlertsEnabled = true
    }
    fun enableEntryAlerts() {
        TradeIdeaNotifications.setEntryEnabled(context, true)
        TradeIdeaNotifications.schedule(context)
        entryAlertsEnabled = true
    }
    val readyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableReadyAlerts()
    }
    val entryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableEntryAlerts()
    }
    fun refresh() = TradeIdeaScanManager.refresh(context)
    LaunchedEffect(Unit) {
        TradeIdeaScanManager.initialize(context)
        // Keep the shared market-scan worker scheduled even when notification alerts are disabled.
        // The worker always refreshes the cached Daily Trade Ideas snapshot; notification permission
        // and alert opt-ins gate only notifications, never market-data refreshes.
        TradeIdeaNotifications.schedule(context)
        // Start all-market public streams first, so cached ideas still receive live prices if REST is banned.
        BinanceFuturesStream.start(emptyList())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            scanHealth = TradeIdeaScanHealthStore.load(context)
            paperRiskStatus = TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = riskSettings.riskPercent)
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
    val liveIdeas = ideas.map { idea ->
        val micro = BinanceFuturesStream.microstructure(idea.symbol)
        idea.copy(
            last = livePrices[idea.symbol] ?: idea.last,
            spreadBps = micro.spreadBps,
            bookImbalance = micro.bookImbalance,
            fundingRate = micro.fundingRate,
            oiChangePct = micro.oiChangePct
        )
    }
    val scanSnapshotAgeMs = updatedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
    fun isPlanTracked(idea: TradeIdea): Boolean = "plan_" + tradeIdeaTrackingId(idea) in trackedPlanKeys
    // Show every scanner-produced setup, including WAIT and AVOID candidates.
    // Shortlisting belongs in a separate view; it must never hide scanner diagnostics.
    val horizonIdeas = liveIdeas.filter { horizonFilter == "All" || it.horizon.startsWith(horizonFilter, ignoreCase = true) }
    val modeIdeas = horizonIdeas
    val rankedIdeas = modeIdeas.sortedWith(
        compareBy<TradeIdea> {
            when (tradeIdeaStage(it, isPlanTracked(it), scanSnapshotAgeMs)) {
                TradeIdeaStage.ENTRY_CONFIRMED -> 0
                TradeIdeaStage.TRACKING, TradeIdeaStage.TRACKING_STOP_HIT, TradeIdeaStage.TRACKING_TARGET_HIT, TradeIdeaStage.TRACKING_EXPIRED, TradeIdeaStage.TRACKING_INVALID -> 1
                TradeIdeaStage.MONITOR -> 2
                TradeIdeaStage.WAIT_CONFIRMATION -> 3
                TradeIdeaStage.AVOID_CHASING -> 4
                TradeIdeaStage.DO_NOT_ENTER -> 5
            }
        }.thenByDescending { it.score }
    )
    val visible = rankedIdeas.filter { idea ->
        val stage = tradeIdeaStage(idea, isPlanTracked(idea), scanSnapshotAgeMs)
        val nearSetup = isWithinSetupWatchRange(idea)
        when (filter) {
            "Ready" -> stage == TradeIdeaStage.ENTRY_CONFIRMED
            "Wait" -> (stage == TradeIdeaStage.MONITOR || stage == TradeIdeaStage.WAIT_CONFIRMATION) && nearSetup
            "Tracking" -> isPlanTracked(idea)
            "Avoid" -> stage == TradeIdeaStage.DO_NOT_ENTER || stage == TradeIdeaStage.AVOID_CHASING
            else -> true
        }
    }
    val trackedVisible = visible.filter { isPlanTracked(it) }
    val opportunityVisible = visible.filterNot { isPlanTracked(it) }
    val readyCount = modeIdeas.count { tradeIdeaStage(it, isPlanTracked(it), scanSnapshotAgeMs) == TradeIdeaStage.ENTRY_CONFIRMED }
    val monitorCount = modeIdeas.count { idea ->
        tradeIdeaStage(idea, isPlanTracked(idea), scanSnapshotAgeMs) in setOf(TradeIdeaStage.MONITOR, TradeIdeaStage.WAIT_CONFIRMATION) &&
            isWithinSetupWatchRange(idea)
    }
    val blockedCount = modeIdeas.count { tradeIdeaStage(it, isPlanTracked(it), scanSnapshotAgeMs) in setOf(TradeIdeaStage.DO_NOT_ENTER, TradeIdeaStage.AVOID_CHASING) }
    val longCount = modeIdeas.count { it.side == "LONG" }
    val shortCount = modeIdeas.count { it.side == "SHORT" }
    val waitCount = modeIdeas.count { it.side == "WAIT" }
    val timeLabel = updatedAt?.let {
        (if (partialScanVisible) "Partial · " else "") + SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(it)) + " IST"
    } ?: "Not scanned yet"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                val healthTime = scanHealth.lastSuccessAt
                val healthLabel = if (healthTime > 0L) {
                    val ageMinutes = ((System.currentTimeMillis() - healthTime).coerceAtLeast(0L) / 60_000L)
                    val stamp = SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(healthTime))
                    "Last successful market scan: $stamp IST · ${ageMinutes}m ago"
                } else "No successful market scan recorded yet"
                val healthAge = if (healthTime > 0L) System.currentTimeMillis() - healthTime else Long.MAX_VALUE
                Text((if (healthAge <= 90 * 60_000L) "FRESH · " else "STALE · ") + healthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (healthAge <= 90 * 60_000L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("Price alerts are not real-time guarantees.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!scanHealth.lastError.isNullOrBlank()) Text("Last scan warning: ${scanHealth.lastError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (showingCached) Text("Showing the last saved scan while fresh market data is unavailable.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Find trade ideas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        Text(if (loading) "Quick scanning all USDT perpetuals…" else "Quick Scan · All USDT Pairs")
                    }
                    if (scanExpanded) {
                        Text("Source · Binance Futures public market data. The scanner discovers all active USDT perpetuals, filters by 24h quote volume, then fully validates every eligible pair in bounded parallel batches. BTC 4H/1H trend alignment, setup volume, breakout validity and liquidity gates apply.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScannerStat("LONG", longCount.toString(), Modifier.weight(1f))
                            ScannerStat("SHORT", shortCount.toString(), Modifier.weight(1f))
                            ScannerStat("No direction", waitCount.toString(), Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("READY alerts", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("A trade has passed its entry confirmation checks.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = readyAlertsEnabled, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    TradeIdeaNotifications.setReadyEnabled(context, false)
                                    readyAlertsEnabled = false
                                } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    readyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else enableReadyAlerts()
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ENTRY TRIGGERED alerts", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("Entry conditions confirmed; exit alerts for tracked paper plans.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = entryAlertsEnabled, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    TradeIdeaNotifications.setEntryEnabled(context, false)
                                    entryAlertsEnabled = false
                                } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    entryPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else enableEntryAlerts()
                            })
                        }
                        Text("Background market scans run about every 15 minutes when Android allows. Alerts only · no orders are placed.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Best opportunities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(readyCount.toString() + " ready · " + monitorCount + " waiting · " + blockedCount + " avoid",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Scalp", "Intraday", "Swing").forEach { option ->
                        FilterChip(selected = horizonFilter == option, onClick = { horizonFilter = option }, label = { Text(option) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Ready", "Wait", "Avoid", "Tracking").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                    }
                }
                Text(
                    "${visible.size} shown · ranked by entry state, then scanner score",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                TradeIdeaCard(idea, density, scanSnapshotAgeMs, paperRiskStatus, trackedPlanKeys.size, riskSettings) { planKey, isTracked -> trackedPlanKeys = if (isTracked) trackedPlanKeys + planKey else trackedPlanKeys - planKey }
            }
        }
        if (opportunityVisible.isNotEmpty()) {
            items(opportunityVisible, key = { "idea_" + it.symbol + "_" + it.horizon }) { idea ->
                TradeIdeaCard(idea, density, scanSnapshotAgeMs, paperRiskStatus, trackedPlanKeys.size) { planKey, isTracked -> trackedPlanKeys = if (isTracked) trackedPlanKeys + planKey else trackedPlanKeys - planKey }
            }
        } else if (trackedVisible.isEmpty()) {
            item {
                RiskCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            when {
                                loading && ideas.isEmpty() -> "Scanning Binance markets…"
                                error != null && ideas.isEmpty() -> "Could not complete the market scan"
                                ideas.isEmpty() -> "No scan results yet"
                                filter == "Avoid" -> "No excluded markets to show"
                                filter == "Ready" -> "No READY setups right now"
                                filter == "Wait" -> "No setups near an entry right now"
                                else -> "No trade setups found right now"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when {
                                loading && ideas.isEmpty() -> "The scanner is checking eligible Binance USDT perpetual markets. Results will appear when valid setups are found."
                                error != null && ideas.isEmpty() -> "Check your connection or Binance availability, then refresh the scan."
                                ideas.isEmpty() -> "Refresh the market scan to look for new setups."
                                filter == "Avoid" -> "No markets currently meet the excluded status criteria."
                                filter == "Ready" -> "No setup has a confirmed entry trigger and passed all risk checks. Keep waiting; don't force a trade."
                                filter == "Wait" -> "No valid setup is close enough to its planned entry zone to monitor. The scanner continues checking markets."
                                else -> "No valid setup is currently ready or close enough to its planned entry. The scanner keeps analysing markets; try again after the next scan."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun SnapshotMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
    MONITOR("WAIT", "Setup is getting close to the entry zone. Wait for confirmation."), WAIT_CONFIRMATION("WAIT", "Entry confirmation is missing. Do not enter yet."), ENTRY_CONFIRMED("READY TO ENTER", "Entry trigger confirmed and price remains inside the valid plan. Recheck the live price before acting."), AVOID_CHASING("AVOID", "Price moved too far from the planned entry. Do not chase; wait for a fresh setup."), TRACKING("TRACKING", "Live market price and progress are being monitored."), TRACKING_STOP_HIT("TRACKING · STOP HIT", "Tracked plan has reached its stop. Review the paper tracking record."), TRACKING_TARGET_HIT("TRACKING · TARGET HIT", "Tracked plan has reached target 1. Review the paper tracking record."), TRACKING_EXPIRED("TRACKING · EXPIRED", "The tracked signal has expired. Do not treat it as a fresh entry."), TRACKING_INVALID("TRACKING · INVALID", "The tracked plan no longer passes its risk validation."), DO_NOT_ENTER("AVOID", "The setup is invalid or its risk checks failed. Do not enter.")
}

private fun tradeIdeaRiskValid(idea: TradeIdea): Boolean =
    isTradeIdeaRiskValid(
        side = idea.side,
        entry = idea.entry,
        stop = idea.stop,
        target1 = idea.target1,
        target2 = idea.target2,
        riskCodes = idea.riskCodes
    )

private data class EntryGuidance(
    val score: Int,
    val grade: String,
    val action: String,
    val trigger: String,
    val invalidation: String,
    val zoneLow: Double,
    val zoneHigh: Double,
    val checks: List<Pair<String, Boolean?>>,
    val hardGateBlocked: Boolean
)

private fun biasSupportsSide(bias: String, side: String): Boolean {
    val value = bias.lowercase(Locale.US)
    val tokens = if (side == "LONG") listOf("bull", "up", "long", "positive")
        else listOf("bear", "down", "short", "negative")
    return tokens.any { value.contains(it) }
}

/** Advisory entry timing layer. It never overrides scanner qualification or risk gates. */
private fun buildEntryGuidance(idea: TradeIdea, snapshotAgeMs: Long = 0L): EntryGuidance {
    val longSide = idea.side == "LONG"
    val isScalp = idea.horizon.startsWith("Scalp", ignoreCase = true)
    val isSwing = idea.horizon.startsWith("Swing", ignoreCase = true)
    val trendLabels = when {
        isScalp -> listOf("1H", "15M", "1M")
        isSwing -> listOf("1D", "4H", "15M")
        else -> listOf("1H", "4H context", "5M")
    }
    val setupInterval = if (isScalp) "5M" else if (isSwing) "1H" else "15M"
    val executionInterval = if (isScalp) "1M" else if (isSwing) "15M" else "5M"
    val trend4h = biasSupportsSide(idea.bias4h, idea.side)
    val trend1h = biasSupportsSide(idea.bias1h, idea.side)
    val trend5m = biasSupportsSide(idea.bias5m, idea.side)
    val rsiOk = if (longSide) idea.rsi in 42.0..68.0 else idea.rsi in 32.0..58.0
    val volumeThreshold = confluenceExecutionVolumeThreshold(idea.setup)
    val volumeOk = idea.volumeRatio.isFinite() && idea.volumeRatio >= volumeThreshold
    val spreadOk = idea.spreadBps?.let { it.isFinite() && it in 0.0..MAX_TRADE_SPREAD_BPS }
    // Use the scanner's exact liquidity policy, including both five-level depth sides.
    val bookOk = isTradeIdeaLiquidityAcceptable(
        idea.side, idea.spreadBps, idea.bookImbalance, idea.bidDepthUsd, idea.askDepthUsd
    )
    val riskDistance = abs(idea.entry - idea.stop)
    val grossR = if (riskDistance > 0.0) abs(idea.target1 - idea.entry) / riskDistance else 0.0
    val netR = if (riskDistance > 0.0) tradeIdeaNetReturnR(grossR, idea.entry, riskDistance, heldBars = 8, setupBarMinutes = when { idea.horizon.startsWith("Scalp", true) -> 5; idea.horizon.startsWith("Swing", true) -> 60; else -> 15 }) else Double.NaN
    val rewardOk = netR.isFinite() && netR >= 2.0
    val hardGateBlocked = !volumeOk || spreadOk != true || !bookOk || !rewardOk
    val score = (if (trend1h) 20 else 0) + (if (trend4h) 5 else 0) +
        (if (trend5m) 10 else 0) + (if (rsiOk) 15 else 0) +
        (if (volumeOk) 15 else 0) + (if (spreadOk == true) 5 else 0) +
        (if (bookOk == true) 5 else 0) + (if (rewardOk) 25 else 0)
    val grade = when {
        score >= 80 -> "Strong checklist"
        score >= 65 -> "Good checklist · verify"
        score >= 45 -> "Mixed checklist"
        else -> "Weak checklist"
    }
    val halfWidth = maxOf(idea.entry * 0.00075, riskDistance * 0.10)
    val zoneLow = idea.entry - halfWidth
    val zoneHigh = idea.entry + halfWidth
    val invalidated = livePriceInvalidatesPlan(idea)
    // Share the main card's final state; the coach score is informational only.
    val sharedStage = tradeIdeaStage(idea, tracked = false, snapshotAgeMs = snapshotAgeMs)
    val action = if (hardGateBlocked) {
        "AVOID: one or more hard checklist gates failed. Relative volume, spread, both order-book depth sides, directional imbalance and Target 1 net reward/risk must all pass."
    } else when (sharedStage) {
        TradeIdeaStage.ENTRY_CONFIRMED -> "READY TO ENTER: scanner trigger and live-price safety checks pass. Recheck the live quote, spread and stop before acting."
        TradeIdeaStage.MONITOR -> "WAIT: price is approaching the planned entry zone. Wait for the scanner's confirmation trigger."
        TradeIdeaStage.WAIT_CONFIRMATION -> "WAIT: entry confirmation is missing. Do not enter until the scanner confirms the trigger."
        TradeIdeaStage.AVOID_CHASING -> "AVOID: price has moved beyond the permitted entry distance. Do not chase; wait for a fresh setup and rescan."
        TradeIdeaStage.DO_NOT_ENTER -> when {
            snapshotAgeMs > 0L && snapshotAgeMs > ideaMaxAgeMs(idea) -> "AVOID: this signal snapshot has expired. Run a fresh market scan before considering an entry."
            invalidated -> "AVOID: live price has reached or crossed the stop; this plan is invalidated."
            else -> "AVOID: scanner risk validation failed. Do not enter this setup."
        }
        TradeIdeaStage.TRACKING -> "TRACKING: this idea is already being tracked; this is not a new-entry approval."
        TradeIdeaStage.TRACKING_STOP_HIT -> "STOP HIT: the tracked plan has reached its stop."
        TradeIdeaStage.TRACKING_TARGET_HIT -> "TARGET HIT: target 1 has been reached."
        TradeIdeaStage.TRACKING_EXPIRED -> "EXPIRED: the tracked plan is stale; do not use it as a fresh entry."
        TradeIdeaStage.TRACKING_INVALID -> "INVALID: the tracked plan no longer passes risk validation."
    }
    val trigger = if (longSide)
        "LONG: wait for a fresh, fully closed $executionInterval candle confirming EMA9 above EMA21. The live/forming candle never qualifies. Then recheck the $setupInterval setup and liquidity gates before entry."
    else
        "SHORT: wait for a fresh, fully closed $executionInterval candle confirming EMA9 below EMA21. The live/forming candle never qualifies. Then recheck the $setupInterval setup and liquidity gates before entry."
    val invalidation = if (longSide)
        "Cancel the plan if price reaches the stop or closes below retest support before entry."
    else
        "Cancel the plan if price reaches the stop or closes above retest resistance before entry."
    val combinedChecks = listOf(
        ("${trendLabels[0]} trend aligned") to trend1h,
        ("${trendLabels[1]} context aligned") to trend4h,
        ("${trendLabels[2]} execution trend aligned") to trend5m,
        ("RSI in preferred entry band (" + "%.1f".format(Locale.US, idea.rsi) + ")") to rsiOk,
        ("Relative volume at least " + "%.1f".format(Locale.US, volumeThreshold) + "× (" + "%.2f".format(Locale.US, idea.volumeRatio) + "×)") to volumeOk,
        "Spread at or below 10 bps" to spreadOk,
        "Directional order-book depth available and acceptable" to bookOk,
        ("Target 1 offers at least 2.0R estimated net (" + "%.2f".format(Locale.US, netR) + "R)") to rewardOk
    ) + idea.strategyChecks
    return EntryGuidance(score, grade, action, trigger, invalidation, zoneLow, zoneHigh, combinedChecks, hardGateBlocked)
}

@Composable
private fun EntryGuidancePanel(
    idea: TradeIdea,
    snapshotAgeMs: Long = Long.MAX_VALUE,
    paperRiskStatus: PaperRiskStatus = PaperRiskStatus(0.0, 0, 0),
    trackedPositionCount: Int = 0,
    isTracked: Boolean = false,
    riskSettings: EntryRiskSettings = EntryRiskSettings()
) {
    val guidance = remember(idea, snapshotAgeMs) { buildEntryGuidance(idea, snapshotAgeMs) }
    RiskCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (idea.side == "LONG") "BUY STRATEGY CHECKLIST" else "SELL STRATEGY CHECKLIST", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (guidance.hardGateBlocked) "HARD GATE · BLOCKED"
                    else guidance.checks.count { it.second == true }.toString() + "/" + guidance.checks.count { it.second != null } + " checks passed",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (guidance.hardGateBlocked) MaterialTheme.colorScheme.error
                        else if (guidance.score >= 65) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("Planned entry zone: " + guidance.zoneLow.usd(6) + " – " + guidance.zoneHigh.usd(6),
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(guidance.action, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (guidance.action.startsWith("READY TO ENTER")) {
                Text("Next step: the scanner trigger is already confirmed. Recheck the current price and spread; the coach score is not additional approval.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (guidance.action.startsWith("WAIT")) {
                Text("Confirmation: " + guidance.trigger, style = MaterialTheme.typography.bodySmall)
            }
            Text("Invalidation: " + guidance.invalidation, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            val scannerStage = tradeIdeaStage(idea, tracked = false, snapshotAgeMs = snapshotAgeMs)
            if (scannerStage == TradeIdeaStage.DO_NOT_ENTER) {
                Text("HARD GATE: BLOCKED", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text(idea.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Entry checklist (not trade approval)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            guidance.checks.forEach { (label, passed) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    val hardGate = label.startsWith("Relative volume", true) ||
                        label.startsWith("Spread at or below", true) ||
                        label.startsWith("Directional order-book", true) ||
                        label.startsWith("Target 1 offers", true)
                    val blocked = hardGate && passed != true
                    Text(if (passed == true) "✓" else if (blocked) "✕" else "○",
                        color = if (passed == true) MaterialTheme.colorScheme.primary
                            else if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    Text(label + when {
                        passed == true -> " · pass"
                        blocked && passed == null -> " · unavailable — BLOCKED"
                        blocked -> " · BLOCKED"
                        passed == null -> " · unavailable"
                        else -> " · review"
                    }, style = MaterialTheme.typography.labelSmall,
                        color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Checklist points measure setup confluence only; they are not confidence, win probability or trade approval. Relative volume, spread, both 5-level depth sides, directional imbalance and ≥2.0R estimated net Target 1 are hard gates. Missing depth is BLOCKED, not Review. The READY / WAIT / AVOID decision above always takes priority.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryCoachSummary(idea: TradeIdea, snapshotAgeMs: Long, isTracked: Boolean) {
    if (idea.side !in setOf("LONG", "SHORT")) return
    val guidance = remember(idea, snapshotAgeMs) { buildEntryGuidance(idea, snapshotAgeMs) }
    val stage = tradeIdeaStage(idea, tracked = isTracked, snapshotAgeMs = snapshotAgeMs)
    val accent = when (stage) {
        TradeIdeaStage.ENTRY_CONFIRMED, TradeIdeaStage.TRACKING -> MaterialTheme.colorScheme.primary
        TradeIdeaStage.TRACKING_STOP_HIT, TradeIdeaStage.TRACKING_INVALID, TradeIdeaStage.TRACKING_EXPIRED, TradeIdeaStage.DO_NOT_ENTER, TradeIdeaStage.AVOID_CHASING -> MaterialTheme.colorScheme.error
        TradeIdeaStage.TRACKING_TARGET_HIT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val distancePct = if (idea.entry > 0.0) abs(idea.last - idea.entry) / idea.entry * 100.0 else 0.0
    val priceRelation = when {
        abs(idea.last - idea.entry) < 0.0000001 -> "at planned entry"
        idea.last > idea.entry -> "%.2f%% above planned entry".format(Locale.US, distancePct)
        else -> "%.2f%% below planned entry".format(Locale.US, distancePct)
    }
    val nextStep = when (stage) {
        TradeIdeaStage.ENTRY_CONFIRMED -> "The scanner trigger is confirmed. Recheck live price, spread, depth and net reward/risk before acting."
        TradeIdeaStage.MONITOR, TradeIdeaStage.WAIT_CONFIRMATION -> guidance.trigger
        TradeIdeaStage.AVOID_CHASING -> "Do not chase this move. Wait for a fresh pullback and a new scanner confirmation."
        TradeIdeaStage.DO_NOT_ENTER -> "Do not enter. " + idea.warning
        TradeIdeaStage.TRACKING -> "This idea is already being tracked; this is not a new-entry signal."
        TradeIdeaStage.TRACKING_STOP_HIT -> "Tracked plan stop has been reached. Review the paper position."
        TradeIdeaStage.TRACKING_TARGET_HIT -> "Tracked plan target 1 has been reached. Review the paper position."
        TradeIdeaStage.TRACKING_EXPIRED -> "Tracked signal expired. Do not use it as a new entry."
        TradeIdeaStage.TRACKING_INVALID -> "Tracked plan failed risk validation."
    }
    RiskCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("IDEAL ENTRY COACH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Surface(color = accent.copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
                    Text(stage.title, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
                }
            }
            Text("Planned zone  " + guidance.zoneLow.usd(6) + " – " + guidance.zoneHigh.usd(6),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text("Live price is " + priceRelation, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("NEXT ACTION", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
            Text(nextStep, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (stage == TradeIdeaStage.MONITOR || stage == TradeIdeaStage.WAIT_CONFIRMATION) {
                Text("Invalidation: " + guidance.invalidation, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Text(guidance.checks.count { it.second == true }.toString() + "/" +
                guidance.checks.count { it.second != null } + " available checklist checks passed · checklist is not trade approval",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A confirmed candle is not permission to chase an already-extended move.
 * Keep the live quote close to the planned entry; use the same conservative
 * distance on both sides so a stale trigger cannot become a late READY signal.
 */
private fun entryTooFarFromPlan(idea: TradeIdea): Boolean {
    val riskDistance = abs(idea.entry - idea.stop)
    if (riskDistance <= 0.0 || idea.entry <= 0.0 || !idea.last.isFinite()) return true
    val entryTolerance = minOf(idea.entry * 0.001, riskDistance * 0.20)
    return abs(idea.last - idea.entry) > entryTolerance
}

private fun livePriceInvalidatesPlan(idea: TradeIdea): Boolean =
    if (idea.side == "LONG") idea.last <= idea.stop else idea.last >= idea.stop

/** Keep the WAIT list focused on plans close enough to matter, not every valid distant setup. */
private fun isWithinSetupWatchRange(idea: TradeIdea): Boolean {
    if (idea.entry <= 0.0 || !idea.last.isFinite() || idea.last <= 0.0) return false
    val riskDistance = abs(idea.entry - idea.stop)
    if (riskDistance <= 0.0) return false
    val watchDistance = maxOf(idea.entry * 0.003, riskDistance * 0.50)
    return abs(idea.last - idea.entry) <= watchDistance
}

private fun ideaMaxAgeMs(idea: TradeIdea): Long = when {
    idea.horizon.contains("scalp", ignoreCase = true) -> 15 * 60_000L
    idea.horizon.contains("swing", ignoreCase = true) -> 4 * 60 * 60_000L
    else -> 60 * 60_000L
}

private fun tradeIdeaStage(idea: TradeIdea, tracked: Boolean, snapshotAgeMs: Long = 0L): TradeIdeaStage {
    val expired = snapshotAgeMs > 0L && snapshotAgeMs > ideaMaxAgeMs(idea)
    val invalidRisk = idea.side == "WAIT" || !tradeIdeaRiskValid(idea)
    val stopHit = idea.side in listOf("LONG", "SHORT") && livePriceInvalidatesPlan(idea)
    if (tracked) return when {
        stopHit -> TradeIdeaStage.TRACKING_STOP_HIT
        (idea.side == "LONG" && idea.last >= idea.target1) || (idea.side == "SHORT" && idea.last <= idea.target1) -> TradeIdeaStage.TRACKING_TARGET_HIT
        expired -> TradeIdeaStage.TRACKING_EXPIRED
        invalidRisk -> TradeIdeaStage.TRACKING_INVALID
        else -> TradeIdeaStage.TRACKING
    }
    if (expired || invalidRisk || stopHit) return TradeIdeaStage.DO_NOT_ENTER
    // Legacy cache entries without this strategy data are not eligible for READY.
    val requiredChecks = idea.strategyChecks.filter { (label, _) ->
        label.contains("confirmation candle", ignoreCase = true) ||
            label.startsWith("Stop-loss", ignoreCase = true) ||
            label.startsWith("Target 1", ignoreCase = true)
    }
    if (requiredChecks.isEmpty() || requiredChecks.any { it.second != true }) {
        val riskDistance = abs(idea.entry - idea.stop)
        val entryTolerance = minOf(idea.entry * 0.001, riskDistance * 0.20)
        val favorableMove = if (idea.side == "LONG") idea.last - idea.entry else idea.entry - idea.last
        return if (idea.entryTriggered && entryTooFarFromPlan(idea) && favorableMove > entryTolerance)
            TradeIdeaStage.AVOID_CHASING else TradeIdeaStage.WAIT_CONFIRMATION
    }
    if (idea.entryTriggered) {
        if (!entryTooFarFromPlan(idea)) return TradeIdeaStage.ENTRY_CONFIRMED

        // If price has already moved in the trade's favour, the entry was missed:
        // never label that late quote READY. If price has not reached the planned
        // entry yet, keep it in WAIT/MONITOR rather than treating the candle flag
        // alone as an executable entry.
        val favorableMove = if (idea.side == "LONG") idea.last - idea.entry else idea.entry - idea.last
        val riskDistance = abs(idea.entry - idea.stop)
        val entryTolerance = minOf(idea.entry * 0.001, riskDistance * 0.20)
        return if (favorableMove > entryTolerance) {
            TradeIdeaStage.AVOID_CHASING
        } else {
            val watchDistance = maxOf(idea.entry * 0.003, riskDistance * 0.50)
            if (abs(idea.last - idea.entry) <= watchDistance) TradeIdeaStage.MONITOR
            else TradeIdeaStage.WAIT_CONFIRMATION
        }
    }
    val riskDistance = abs(idea.entry - idea.stop)
    val approachBand = maxOf(idea.entry * 0.0015, riskDistance * 0.25)
    val approaching = if (idea.side == "LONG") {
        idea.last < idea.entry && idea.entry - idea.last <= approachBand
    } else {
        idea.last > idea.entry && idea.last - idea.entry <= approachBand
    }
    return if (approaching) TradeIdeaStage.MONITOR else TradeIdeaStage.WAIT_CONFIRMATION
}


private fun displayIdeaHorizon(horizon: String): String = horizon

private fun tradeIdeaTrackingId(idea: TradeIdea): String = idea.symbol + "_" + idea.horizon.replace(Regex("[^A-Za-z0-9]"), "") + "_" + idea.setup.replace(Regex("[^A-Za-z0-9]"), "") + "_" + idea.side + "_" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(idea.entry)) + "_" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(idea.stop))

@Composable
private fun TradeIdeaCard(
    idea: TradeIdea,
    density: String,
    snapshotAgeMs: Long = Long.MAX_VALUE,
    paperRiskStatus: PaperRiskStatus = PaperRiskStatus(0.0, 0, 0),
    trackedPositionCount: Int = 0,
    riskSettings: EntryRiskSettings = EntryRiskSettings(),
    onTrackingChanged: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val trackingPrefs = remember { context.getSharedPreferences("trade_idea_tracking_v1", android.content.Context.MODE_PRIVATE) }
    val trackedPlanId = tradeIdeaTrackingId(idea)
    val trackedEntryKey = "plan_" + trackedPlanId
    var trackedEntry by remember(trackedPlanId) { mutableStateOf(trackingPrefs.getFloat(trackedEntryKey, 0f).toDouble().takeIf { it > 0.0 }) }
    var livePrice by remember(trackedPlanId) { mutableStateOf(idea.last) }
    val trackingScope = rememberCoroutineScope()
    var expanded by remember(trackedPlanId, density) { mutableStateOf(false) }
    var levelsExpanded by remember(trackedPlanId) { mutableStateOf(false) }
    LaunchedEffect(idea.symbol) {
        // Poll only the in-memory WebSocket cache; this loop never makes a REST request.
        while (true) {
            val quote = withContext(Dispatchers.IO) { BinanceMarketScanner.latestPrice(idea.symbol) }
            if (quote != null) livePrice = quote
            delay(2_000)
        }
    }
    val liveIdea = idea.copy(last = livePrice)
    val guidance = remember(liveIdea, snapshotAgeMs) { buildEntryGuidance(liveIdea, snapshotAgeMs) }
    val stage = tradeIdeaStage(liveIdea, trackedEntry != null, snapshotAgeMs)
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
            if (!expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(idea.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(displayIdeaHorizon(idea.horizon) + " · " + idea.setup, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Score " + idea.score + "/100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Text(if (idea.side == "WAIT") "NO DIRECTION" else idea.side, style = MaterialTheme.typography.labelMedium, color = sideTint, fontWeight = FontWeight.Bold)
                        Text(stage.title, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (idea.side == "LONG" || idea.side == "SHORT") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SummaryLevel("Entry zone", zoneLow.usd(6) + "–" + zoneHigh.usd(6), Modifier.weight(1.25f))
                        SummaryLevel("Stop", idea.stop.usd(6), Modifier.weight(1f))
                        SummaryLevel("Target · 2R", idea.target1.usd(6), Modifier.weight(1f))
                    }
                    Text("Live price  " + livePrice.usd(6), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 } else {
                    Text("No directional setup currently qualifies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("Setup details")
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
            if (!expanded) EntryCoachSummary(liveIdea, snapshotAgeMs, trackedEntry != null)
            if (idea.side == "LONG" || idea.side == "SHORT") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryLevel("Entry zone", zoneLow.usd(6) + "–" + zoneHigh.usd(6), Modifier.weight(1.25f))
                    SummaryLevel("Stop", idea.stop.usd(6), Modifier.weight(1f))
                    SummaryLevel("TP2 · 2R", idea.target1.usd(6), Modifier.weight(1f))
                }
                if (density != "Compact") TargetLadder(idea)
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

            if (stage == TradeIdeaStage.ENTRY_CONFIRMED || trackedEntry != null) {
                HorizontalDivider()
                TextButton(onClick = { levelsExpanded = !levelsExpanded }, modifier = Modifier.align(Alignment.End)) { Text(if (levelsExpanded) "Hide levels" else "Show all levels") }
                if (levelsExpanded) {
                Text("Entry & risk plan · retest confirmation", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                IdeaLevel("Entry zone low", zoneLow)
                IdeaLevel("Entry trigger", idea.entry)
                IdeaLevel("Entry zone high", zoneHigh)
                IdeaLevel("Stop-loss", idea.stop)
                Text("MULTI-TARGET EXIT PLAN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                TargetLadder(idea)
                ScannerMetric("Scanner qualification", "Existing V4 gate: gross target 1 ≥ 2R")
                ScannerMetric("Allocation", "25% · 25% · 25% · 25%")
                Text("Illustrative paper plan only. Targets are calculated from initial entry risk; no exchange orders or partial fills are managed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EntryRiskPlanDetails(idea, riskSettings)
                }
            }
            if (trackedEntry != null) {
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
                    onTrackingChanged(trackedEntryKey, false)
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop tracking") }
            } else if (stage == TradeIdeaStage.ENTRY_CONFIRMED) {
                Text("Risk checks passed.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    trackingScope.launch {
                        val entry = withContext(Dispatchers.IO) { BinanceMarketScanner.latestPrice(idea.symbol) }
                            ?: livePrice.takeIf { it > 0.0 } ?: idea.last
                        val startedAt = System.currentTimeMillis()
                        trackingPrefs.edit()
                            .putFloat(trackedEntryKey, entry.toFloat())
                            .apply()
                        livePrice = entry
                        trackedEntry = entry
                        onTrackingChanged(trackedEntryKey, true)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Track this idea") }
            } else if (stage == TradeIdeaStage.MONITOR) {
                Text("Entry zone: " + zoneLow.usd(6) + " – " + zoneHigh.usd(6), style = MaterialTheme.typography.bodySmall)
            } else if (stage == TradeIdeaStage.WAIT_CONFIRMATION) {
                Text("Entry trigger: " + idea.entry.usd(6) + " · confirmation is still missing.", style = MaterialTheme.typography.bodySmall)
            } else {
                val expired = snapshotAgeMs > 0L && snapshotAgeMs > ideaMaxAgeMs(idea)
                Text(if (expired) "This signal has expired. Refresh the market scan before considering it."
                    else if (idea.side == "WAIT") "Trend, momentum and liquidity filters are not aligned. No entry is permitted."
                    else "The risk plan failed validation or has a blocking warning: " + idea.warning,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) { Text(if (expanded) "Hide setup details" else "Setup details") }
            if (expanded) {
                HorizontalDivider()
                HistoricalOutcomePanel(idea)
                Text("WHY THIS SETUP?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    idea.setup + " · " + idea.side + " · " + idea.score + "/100 confluence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (idea.side == "LONG" || idea.side == "SHORT") {
                    val signedDriftPct = (if (idea.side == "LONG") 1.0 else -1.0) *
                        (livePrice - idea.entry) / idea.entry * 100.0
                    ScannerMetric("Live price vs planned entry", "%+.2f%%".format(Locale.US, signedDriftPct))
                    if (entryTooFarFromPlan(liveIdea)) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "PRICE DRIFT · DO NOT CHASE. Live price is outside the permitted entry distance. Wait for a fresh pullback and a new scan confirmation.",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            "Price remains within the scanner's entry-distance limit. Recheck the live quote and confirmation before acting.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    EntryGuidancePanel(liveIdea, snapshotAgeMs)
                }
                val trendLabels = if (idea.horizon.startsWith("Scalp", ignoreCase = true)) listOf("1H", "15M", "1M")
                    else if (idea.horizon.startsWith("Swing", ignoreCase = true)) listOf("1D", "4H", "15M")
                    else listOf("4H", "1H", "5M")
                val setupInterval = if (idea.horizon.startsWith("Scalp", ignoreCase = true)) "5M"
                    else if (idea.horizon.startsWith("Swing", ignoreCase = true)) "1H" else "15M"
                ScannerMetric("Trend alignment", trendLabels[0] + " " + idea.bias4h + " · " + trendLabels[1] + " " + idea.bias1h + " · " + trendLabels[2] + " " + idea.bias5m)
                ScannerMetric(setupInterval + " RSI", "%.1f".format(Locale.US, idea.rsi))
                ScannerMetric("Relative volume", "%.2f× recent average".format(Locale.US, idea.volumeRatio))
                ScannerMetric("Volatility", "%.2f%% ATR/range".format(Locale.US, idea.volatilityPct))
                ScannerMetric("Order-book spread", idea.spreadBps?.let { "%.1f bps".format(Locale.US, it) } ?: "Unavailable")
                ScannerMetric("Depth imbalance", idea.bookImbalance?.let { "%+.2f".format(Locale.US, it) } ?: "Unavailable")
                ScannerMetric("Funding rate", idea.fundingRate?.let { "%+.4f%%".format(Locale.US, it * 100.0) } ?: "Unavailable")
                ScannerMetric("Open-interest change", idea.oiChangePct?.let { "%+.2f%%".format(Locale.US, it) } ?: "Unavailable")
                if (idea.strategyChecks.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Scanner checks", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    idea.strategyChecks.forEach { (label, passed) ->
                        val status = when (passed) { true -> "PASS"; false -> "WAIT"; null -> "N/A" }
                        val statusColor = when (passed) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (idea.reasons.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Signal reasons", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    idea.reasons.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
                Text("Historical replay · " + idea.backtest.trades + " closed samples (30 required for estimates)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text(if (!idea.backtest.hasUsableEstimate) "Win/loss estimate is hidden until at least 30 completed historical examples are available. The replay currently has " + idea.backtest.trades + "."
                    else "Observed historical win rate " + "%.1f".format(Locale.US, idea.backtest.winRatePercent) + "% · average " +
                        "%+.2f".format(Locale.US, idea.backtest.averageR) + "R.",
                    style = MaterialTheme.typography.bodySmall)
                Text(idea.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }
}
@Composable
private fun TargetLadder(idea: TradeIdea) {
    val risk = abs(idea.entry - idea.stop)
    if (risk <= 0.0 || !risk.isFinite() || idea.side !in setOf("LONG", "SHORT")) return
    val direction = if (idea.side == "LONG") 1.0 else -1.0
    val targets = listOf(
        Triple("TP1", 1.0, "25% · bank first profit"),
        Triple("TP2", 2.0, "25% · reduce downside"),
        Triple("TP3", 3.0, "25% · secure extension"),
        Triple("TP4", 4.0, "25% · runner / trail")
    )
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TARGET LADDER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("25% at each level", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        targets.forEach { (label, multiple, note) ->
            val price = idea.entry + direction * risk * multiple
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("%.0fR".format(Locale.US, multiple), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(price.usd(6), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Move stop to breakeven only after TP2; trail the final runner after TP3. Adjust for fees, funding and slippage.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(
                    "Profit factor " + (stats.profitFactor?.let { "%.2f".format(Locale.US, it) } ?: "n/a") +
                        " · max drawdown " + "%.2f".format(Locale.US, stats.maxDrawdownR) + "R",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "LONG " + stats.longTrades + " trades · " +
                        (stats.longAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "no samples") +
                        "  |  SHORT " + stats.shortTrades + " trades · " +
                        (stats.shortAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "no samples"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "TREND " + stats.trendTrades + " trades · " +
                        (stats.trendAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "no samples") +
                        "  |  RANGE " + stats.rangeTrades + " trades · " +
                        (stats.rangeAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "no samples"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "BREAKOUT " + stats.breakoutTrades + " trades · " +
                        (setupFamilyWinRateLabel(stats.breakoutWins, stats.breakoutTrades)) +
                        " · " + (stats.breakoutAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "n/a"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "PULLBACK " + stats.pullbackTrades + " trades · " +
                        (setupFamilyWinRateLabel(stats.pullbackWins, stats.pullbackTrades)) +
                        " · " + (stats.pullbackAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "n/a"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "REVERSAL " + stats.reversalTrades + " trades · " +
                        (setupFamilyWinRateLabel(stats.reversalWins, stats.reversalTrades)) +
                        " · " + (stats.reversalAverageR?.let { "%+.2fR avg".format(Locale.US, it) } ?: "n/a"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val lower = stats.winRateLower95 ?: 0.0
                val upper = stats.winRateUpper95 ?: 100.0
                Text("Approx. 95% uncertainty range for win rate: %.1f–%.1f%%".format(Locale.US, lower, upper),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Win/loss estimate hidden — requires at least 30 completed replay samples. Current: " + stats.trades + ".",
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
