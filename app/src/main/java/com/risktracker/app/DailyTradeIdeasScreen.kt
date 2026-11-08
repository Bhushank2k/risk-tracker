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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    val strategyChecks: List<Pair<String, Boolean?>> = emptyList()
)
internal data class MarketMicrostructure(
    val spreadBps: Double?, val bookImbalance: Double?, val fundingRate: Double?, val oiChangePct: Double?
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
                backtest = TradeIdeaBacktestStats(stats.optInt("trades"), stats.optInt("wins"), stats.optDouble("averageR")),
                volatilityPct = x.optDouble("volatilityPct", 0.0),
                strategyChecks = x.optJSONArray("strategyChecks")?.let { checks ->
                    (0 until checks.length()).mapNotNull { index ->
                        checks.optJSONObject(index)?.let { item ->
                            val passed: Boolean? = if (!item.has("passed") || item.isNull("passed")) null else item.optBoolean("passed")
                            item.optString("label").takeIf { it.isNotBlank() }?.let { it to passed }
                        }
                    }
                } ?: emptyList()
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
                val strategyChecks = JSONArray().apply {
                    idea.strategyChecks.forEach { (label, passed) ->
                        put(JSONObject().put("label", label).put("passed", passed ?: JSONObject.NULL))
                    }
                }
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
                    .put("backtest", stats).put("volatilityPct", idea.volatilityPct).put("strategyChecks", strategyChecks))
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
        onPartialResults: (List<TradeIdea>) -> Unit = {}
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
            if (symbol !in allowed || quote < 5_000_000.0 || last <= 0.0) null
            else {
                val change = x.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                val high = x.optString("highPrice").toDoubleOrNull() ?: last
                val low = x.optString("lowPrice").toDoubleOrNull() ?: last
                val rangePct = ((high - low) / last * 100.0).coerceAtLeast(0.0)
                ScanTicker(symbol, quote, change, last, rangePct)
            }
        }.sortedByDescending { it.volatilityPct + abs(it.changePct) * 0.5 }
        report("24h filter: ${prefiltered.size}/${allowed.size} active pairs meet the $5M quote-volume requirement.")
        if (prefiltered.isEmpty()) throw IllegalStateException("No liquid USDT perpetual pairs were returned.")

        // Measure every eligible active USDT perpetual; ranking changes evaluation order, never market coverage.
        report("Loading hourly history for 0/${prefiltered.size} eligible pairs…")
        val hourlyCache = ConcurrentHashMap<String, List<ScanCandle>>()
        val hourlyCompleted = AtomicInteger(0)
        val hourlyHistoryFailures = AtomicInteger(0)
        val ranked = prefiltered.map { ticker ->
            async {
                coroutineContext.ensureActive()
                try {
                    val hourly = cachedCandles(ticker.symbol, "1h", 260)
                    hourlyCache[ticker.symbol] = hourly
                    ticker.copy(atrPct = atrPercent(hourly), relativeVolume = relativeVolume(hourly))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BinanceRateLimitException) {
                    throw e
                } catch (_: Exception) {
                    // Incomplete hourly history is not eligible for ATR-based ranking.
                    hourlyHistoryFailures.incrementAndGet()
                    null
                } finally {
                    val completed = hourlyCompleted.incrementAndGet()
                    if (completed % 5 == 0 || completed == prefiltered.size) {
                        report("Hourly history: ${completed}/${prefiltered.size} pairs · ${hourlyHistoryFailures.get()} unavailable…")
                    }
                }
            }
        }.awaitAll().filterNotNull()
        val candidates = ranked.sortedByDescending {
            // Blend tradable movement with liquidity so quieter but liquid setups are not crowded out.
            // Keep every eligible market; this score only prioritizes the order of deep evaluation.
            it.atrPct * (1.0 + 0.12 * it.relativeVolume.coerceIn(0.0, 3.0)) +
                it.volatilityPct * 0.05 + kotlin.math.ln1p(it.quoteVolume / 5_000_000.0) * 0.15
        }
        if (candidates.isEmpty()) throw IllegalStateException("Hourly ATR and relative-volume data were unavailable for all ${prefiltered.size} liquid pairs.")
        report("Hourly history ready: ${candidates.size}/${prefiltered.size} pairs. Loading BTC market context…")

        // BTC trend is a broad-market risk filter; neutral conditions do not impose a directional bias.
        val btcH4Job = async { cachedCandles("BTCUSDT", "4h", 260) }
        val btcH1Job = async { cachedCandles("BTCUSDT", "1h", 260) }
        val btcH4 = btcH4Job.await()
        val btcH1 = btcH1Job.await()
        val btcBias = btcRegime(btcH4, btcH1)

        // The aggregate ticker/book streams cover the whole market. Depth streams are
        // intentionally subscribed in bounded batches, then awaited before each batch is evaluated.
        val depthBatchSize = 150
        report("Deep scan starting: 0/${candidates.size} pairs · BTC regime: $btcBias…")
        val results = mutableListOf<TradeIdea>()
        var marketsWithPlans = 0
        var marketsWithoutPlans = 0
        var blockedPlans = 0
        var marketDataFailures = 0
        var lastMarketFailure: String? = null
        for ((index, ticker) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            if (index % depthBatchSize == 0) {
                val depthBatch = candidates.drop(index).take(depthBatchSize).map { it.symbol }
                BinanceFuturesStream.start(depthBatch)
                val depthReady = BinanceFuturesStream.awaitDepth(depthBatch, timeoutMs = 5_000L)
                report("Order-book depth ready: ${depthReady.size}/${depthBatch.size} pairs · scanning ${index + 1}/${candidates.size}…")
            }
            try {
                // Fetch each symbol's remaining intervals concurrently; the shared limiter
                // caps total REST concurrency and spaces request starts to protect Binance.
                val h4Job = async { cachedCandles(ticker.symbol, "4h", 260) }
                val m15Job = async { cachedCandles(ticker.symbol, "15m", 500) }
                val m5Job = async { cachedCandles(ticker.symbol, "5m", 1500) }
                val h4 = h4Job.await()
                val h1 = hourlyCache[ticker.symbol] ?: cachedCandles(ticker.symbol, "1h", 260)
                val m15 = m15Job.await()
                val m5 = m5Job.await()
                val micro = BinanceFuturesStream.microstructure(ticker.symbol).copy(
                    oiChangePct = openInterestChangePct(ticker.symbol)
                )
                val liveTicker = BinanceFuturesStream.latestTicker(ticker.symbol)
                val live = ticker.copy(
                    last = liveTicker?.first ?: ticker.last,
                    quoteVolume = liveTicker?.second ?: ticker.quoteVolume,
                    changePct = liveTicker?.third ?: ticker.changePct
                )
                val marketIdeas = evaluateModes(live, h4, h1, m15, m5, micro, btcBias, btcH4)
                if (marketIdeas.isEmpty()) {
                    marketsWithoutPlans++
                } else {
                    marketsWithPlans++
                    blockedPlans += marketIdeas.count { it.warning.startsWith("BLOCK:", ignoreCase = true) }
                }
                results.addAll(marketIdeas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BinanceRateLimitException) {
                throw e
            } catch (e: Exception) {
                marketDataFailures++
                lastMarketFailure = "${ticker.symbol}: ${e.message ?: e.javaClass.simpleName}"
            }
            if (results.isNotEmpty()) {
                val snapshot = results.toList()
                withContext(Dispatchers.Main.immediate) { onPartialResults(snapshot) }
            }
            report("Scanned ${index + 1}/${candidates.size} pairs · ${results.size} setups · ${blockedPlans} blocked · ${marketsWithoutPlans} no setup · ${marketDataFailures} data errors…")
        }
        if (results.isEmpty()) {
            val detail = lastMarketFailure?.let { " Last market error: $it" }
                ?: " Candle history was fetched, but every strategy rejected the available series."
            throw IllegalStateException(
                "Scan found no technical setups in ${candidates.size} evaluated pairs (${prefiltered.size} passed the 24h liquidity filter). " +
                    "Pairs with setups: $marketsWithPlans; no setup: $marketsWithoutPlans; data errors: $marketDataFailures; " +
                    "hourly history unavailable: ${hourlyHistoryFailures.get()}.$detail"
            )
        }
        results.sortedWith(compareByDescending<TradeIdea> { it.score }.thenByDescending { it.volatilityPct })
    } }

    private fun evaluateModes(
        t: ScanTicker, h4: List<ScanCandle>, h1: List<ScanCandle>, m15: List<ScanCandle>,
        m5: List<ScanCandle>, micro: MarketMicrostructure, btcBias: String, btcH4: List<ScanCandle>
    ): List<TradeIdea> {
        val four = h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
        val one = h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
        val fifteen = m15.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
        val five = m5.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
        val market = V2Microstructure(micro.spreadBps, micro.bookImbalance, micro.fundingRate, micro.oiChangePct)
        val plan = ConfluenceScannerV4.evaluate(four, one, fifteen, five, market, t.quoteVolume, btcBias)
            ?: return emptyList()
        return listOf(buildIdea(t, plan, m15, m5, h4, h1, micro, swingMode = false, runBacktest = false))
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
        // Historical replay is supplementary; a replay edge case must never discard a live setup.
        val replay = if (swingMode || !runBacktest || plan.setup != "Breakout retest") TradeIdeaBacktestStats() else runCatching {
            TradeIdeasV2.backtest(
                h4.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                h1.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                setupRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) },
                executionRows.map { V2Candle(it.high, it.low, it.close, it.volume, it.closeTime, it.open) }
            )
        }.getOrDefault(TradeIdeaBacktestStats())
        val strategyChecks = buildStrategyChecks(plan, setupRows, executionRows, h1)
        return TradeIdea(
            t.symbol, plan.side, plan.score, t.quoteVolume, t.changePct, t.last,
            plan.entry, plan.stop, plan.target1, plan.target2, plan.entryTriggered,
            rsiValue, volumeRatio, bias4, bias1, plan.setup, reasons, plan.warning,
            plan.horizon, bias5, micro.spreadBps, micro.bookImbalance, micro.fundingRate,
            micro.oiChangePct, if (swingMode) TradeIdeaBacktestStats() else replay, t.volatilityPct,
            strategyChecks
        )
    }

    private fun buildStrategyChecks(
        plan: V2TradePlan,
        setupRows: List<ScanCandle>,
        executionRows: List<ScanCandle>,
        trendRows: List<ScanCandle>
    ): List<Pair<String, Boolean?>> {
        val isLong = plan.side == "LONG"
        if (!isLong && plan.side != "SHORT") return emptyList()
        val trendAligned = if (isLong) hasHigherHighsAndLows(trendRows) else hasLowerHighsAndLows(trendRows)
        val closes = setupRows.map { it.close }
        val ema20 = ema(closes, 20)
        val lastSetup = setupRows.lastOrNull()
        val typicalVolume = setupRows.takeLast(96).sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume }
        val totalVolume = setupRows.takeLast(96).sumOf { it.volume }
        val vwap = if (totalVolume > 0.0) typicalVolume / totalVolume else null
        val vwapAligned = lastSetup?.let { if (isLong) vwap != null && it.close > vwap else vwap != null && it.close < vwap }
        val emaAligned = lastSetup?.let { if (isLong) ema20 != null && it.close > ema20 else ema20 != null && it.close < ema20 }
        val retest = plan.setup.contains("breakout", ignoreCase = true) &&
            plan.setup.contains("retest", ignoreCase = true)
        val candle = executionRows.lastOrNull()
        val priorAverageVolume = executionRows.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (priorAverageVolume > 0.0) (candle?.volume ?: 0.0) / priorAverageVolume else 0.0
        val candleAligned = candle?.let {
            val directionOk = if (isLong) it.close > it.open else it.close < it.open
            val requiredVolume = if (retest) 1.5 else 1.2
            directionOk && volumeRatio >= requiredVolume
        }
        val recent = executionRows.dropLast(1).takeLast(20)
        val swingLow = recent.minOfOrNull { it.low }
        val swingHigh = recent.maxOfOrNull { it.high }
        val stopAligned = if (isLong) swingLow != null && plan.stop < swingLow
            else swingHigh != null && plan.stop > swingHigh
        val risk = abs(plan.entry - plan.stop)
        val rr = if (risk > 0.0) abs(plan.target1 - plan.entry) / risk else 0.0
        return listOf(
            ("1H market structure: " + (if (isLong) "higher highs + higher lows" else "lower highs + lower lows")) to trendAligned,
            ("15M VWAP: price " + (if (isLong) "above" else "below") + " rolling 24H VWAP") to vwapAligned,
            ("15M EMA20: price " + (if (isLong) "above" else "below") + " EMA20") to emaAligned,
            "Entry: breakout / breakdown followed by retest" to retest,
            "5M confirmation candle with volume (>= 1.2x; breakout >= 1.5x)" to candleAligned,
            ("Stop-loss beyond recent 5M swing " + (if (isLong) "low" else "high")) to stopAligned,
            ("Target 1 minimum 1:2 risk/reward (" + "%.2f".format(Locale.US, rr) + "R)") to (rr >= 2.0)
        )
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
    var scanHealth by remember { mutableStateOf(TradeIdeaScanHealthStore.load(context)) }
    var showingCached by remember { mutableStateOf(cachedScan != null) }
    var filter by remember { mutableStateOf("All") }
    var riskSettings by remember { mutableStateOf(EntryRiskSettingsStore.load(context)) }
    val density = "Compact"
    var scanExpanded by remember { mutableStateOf(false) }
    var trackedSymbols by remember { mutableStateOf(readTrackedSymbols()) }
    var livePrices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var paperRiskStatus by remember { mutableStateOf(TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = riskSettings.riskPercent)) }
    fun updateRiskSettings(next: EntryRiskSettings) {
        riskSettings = next
        EntryRiskSettingsStore.save(context, next)
        paperRiskStatus = TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = next.riskPercent)
    }
    var readyAlertsEnabled by remember { mutableStateOf(TradeIdeaNotifications.isReadyEnabled(context)) }
    var entryAlertsEnabled by remember { mutableStateOf(TradeIdeaNotifications.isEntryEnabled(context)) }
    val scope = rememberCoroutineScope()
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
    fun refresh() {
        if (loading) return
        loading = true
        error = null
        status = if (ideas.isEmpty()) "Connecting to Binance public market data…" else "Refreshing market data · saved ideas remain visible…"
        TradeIdeaScanHealthStore.started(context)
        scanHealth = TradeIdeaScanHealthStore.load(context)
        scope.launch {
            try {
                val freshIdeas = BinanceMarketScanner.scan(
                    progress = { status = it },
                    onPartialResults = { partial ->
                        ideas = partial
                        showingCached = false
                    }
                )
                val scanTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) { TradeIdeaCache.save(context, scanTime, freshIdeas) }
                TradeIdeaScanHealthStore.succeeded(context, at = scanTime)
                scanHealth = TradeIdeaScanHealthStore.load(context)
                ideas = freshIdeas
                updatedAt = scanTime
                showingCached = false
                trackedSymbols = readTrackedSymbols()
                paperRiskStatus = TradeIdeaPaperJournal.riskStatus(context, riskPerTradePercent = riskSettings.riskPercent)
                status = "Scan complete · " + freshIdeas.size + " trade ideas from eligible markets."
                if (freshIdeas.isEmpty()) status = "Scan complete · no setups passed the current filters."
            } catch (e: CancellationException) {
                throw e
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
        // Keep the shared market-scan worker scheduled even when notification alerts are disabled.
        // The worker always refreshes the cached Daily Trade Ideas snapshot; notification permission
        // and alert opt-ins gate only notifications, never market-data refreshes.
        TradeIdeaNotifications.schedule(context)
        // Start all-market public streams first, so cached ideas still receive live prices if REST is banned.
        BinanceFuturesStream.start(emptyList())
        val cacheAge = cachedScan?.first?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        if (cacheAge > 10 * 60_000L) refresh()
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
    // Show every scanner-produced setup, including WAIT and AVOID candidates.
    // Shortlisting belongs in a separate view; it must never hide scanner diagnostics.
    val modeIdeas = liveIdeas
    val rankedIdeas = modeIdeas.sortedWith(
        compareBy<TradeIdea> {
            when (tradeIdeaStage(it, it.symbol in trackedSymbols, scanSnapshotAgeMs)) {
                TradeIdeaStage.ENTRY_CONFIRMED -> 0
                TradeIdeaStage.TRACKING -> 1
                TradeIdeaStage.MONITOR -> 2
                TradeIdeaStage.WAIT_CONFIRMATION -> 3
                TradeIdeaStage.AVOID_CHASING -> 4
                TradeIdeaStage.DO_NOT_ENTER -> 5
            }
        }.thenByDescending { it.score }
    )
    val visible = rankedIdeas.filter { idea ->
        val stage = tradeIdeaStage(idea, idea.symbol in trackedSymbols, scanSnapshotAgeMs)
        val nearSetup = isWithinSetupWatchRange(idea)
        when (filter) {
            "Ready" -> stage == TradeIdeaStage.ENTRY_CONFIRMED
            "Wait" -> (stage == TradeIdeaStage.MONITOR || stage == TradeIdeaStage.WAIT_CONFIRMATION) && nearSetup
            "Tracking" -> stage == TradeIdeaStage.TRACKING
            "Avoid" -> stage == TradeIdeaStage.DO_NOT_ENTER || stage == TradeIdeaStage.AVOID_CHASING
            else -> true
        }
    }
    val trackedVisible = visible.filter { it.symbol in trackedSymbols }
    val opportunityVisible = visible.filterNot { it.symbol in trackedSymbols }
    val readyCount = modeIdeas.count { tradeIdeaStage(it, it.symbol in trackedSymbols, scanSnapshotAgeMs) == TradeIdeaStage.ENTRY_CONFIRMED }
    val monitorCount = modeIdeas.count { idea ->
        tradeIdeaStage(idea, idea.symbol in trackedSymbols, scanSnapshotAgeMs) in setOf(TradeIdeaStage.MONITOR, TradeIdeaStage.WAIT_CONFIRMATION) &&
            isWithinSetupWatchRange(idea)
    }
    val blockedCount = modeIdeas.count { tradeIdeaStage(it, it.symbol in trackedSymbols, scanSnapshotAgeMs) in setOf(TradeIdeaStage.DO_NOT_ENTER, TradeIdeaStage.AVOID_CHASING) }
    val longCount = modeIdeas.count { it.side == "LONG" }
    val shortCount = modeIdeas.count { it.side == "SHORT" }
    val waitCount = modeIdeas.count { it.side == "WAIT" }
    val timeLabel = updatedAt?.let {
        SimpleDateFormat("dd MMM · HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(it)) + " IST"
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
                TradeIdeaCard(idea, density, scanSnapshotAgeMs, paperRiskStatus, trackedSymbols.size, riskSettings) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
            }
        }
        if (opportunityVisible.isNotEmpty()) {
            items(opportunityVisible, key = { "idea_" + it.symbol + "_" + it.horizon }) { idea ->
                TradeIdeaCard(idea, density, scanSnapshotAgeMs, paperRiskStatus, trackedSymbols.size) { symbol, isTracked -> trackedSymbols = if (isTracked) trackedSymbols + symbol else trackedSymbols - symbol }
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
    MONITOR("WAIT", "Setup is getting close to the entry zone. Wait for confirmation."),
    WAIT_CONFIRMATION("WAIT", "Entry confirmation is missing. Do not enter yet."),
    ENTRY_CONFIRMED("READY TO ENTER", "Entry trigger confirmed and price remains inside the valid plan. Recheck the live price before acting."),
    AVOID_CHASING("AVOID", "Price moved too far from the planned entry. Do not chase; wait for a fresh setup."),
    TRACKING("Trade being tracked", "Live market price and progress are being monitored."),
    DO_NOT_ENTER("AVOID", "The setup is invalid or its risk checks failed. Do not enter.")
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
    val riskDistance = abs(idea.entry - idea.stop)
    val rewardRisk = if (riskDistance > 0.0) abs(idea.target1 - idea.entry) / riskDistance else 0.0
    return geometryValid && rewardRisk >= 2.0 && !warningBlocksEntry
}

private data class EntryGuidance(
    val score: Int,
    val grade: String,
    val action: String,
    val trigger: String,
    val invalidation: String,
    val zoneLow: Double,
    val zoneHigh: Double,
    val checks: List<Pair<String, Boolean?>>
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
    val trend4h = biasSupportsSide(idea.bias4h, idea.side)
    val trend1h = biasSupportsSide(idea.bias1h, idea.side)
    val trend5m = biasSupportsSide(idea.bias5m, idea.side)
    val rsiOk = if (longSide) idea.rsi in 42.0..68.0 else idea.rsi in 32.0..58.0
    val volumeThreshold = if (idea.setup.contains("breakout", ignoreCase = true)) 1.5 else 1.2
    val volumeOk = idea.volumeRatio >= volumeThreshold
    val spreadOk = idea.spreadBps?.let { it.isFinite() && it in 0.0..10.0 }
    // Match the scanner's hard liquidity gate: spread alone is insufficient.
    val bookOk = idea.bookImbalance?.let {
        it.isFinite() && it in -1.0..1.0 && (if (longSide) it >= -0.15 else it <= 0.15)
    }
    val riskDistance = abs(idea.entry - idea.stop)
    val rr = if (riskDistance > 0.0) abs(idea.target1 - idea.entry) / riskDistance else 0.0
    val rewardOk = rr >= 2.0
    val score = (if (trend4h) 20 else 0) + (if (trend1h) 15 else 0) +
        (if (trend5m) 10 else 0) + (if (rsiOk) 15 else 0) +
        (if (volumeOk) 15 else 0) + (if (spreadOk == true) 5 else 0) +
        (if (bookOk == true) 5 else 0) + (if (rewardOk) 15 else 0)
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
    val action = when (sharedStage) {
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
    }
    val trigger = if (longSide)
        "LONG: wait for a completed 5M candle with EMA9 above EMA21. Recheck that the 15M setup and liquidity gates still hold; do not enter on an unclosed candle."
    else
        "SHORT: wait for a completed 5M candle with EMA9 below EMA21. Recheck that the 15M setup and liquidity gates still hold; do not enter on an unclosed candle."
    val invalidation = if (longSide)
        "Cancel the plan if price reaches the stop or closes below retest support before entry."
    else
        "Cancel the plan if price reaches the stop or closes above retest resistance before entry."
    val combinedChecks = listOf(
        "4H trend aligned" to trend4h,
        "1H trend aligned" to trend1h,
        "5M execution trend aligned" to trend5m,
        ("RSI in preferred entry band (" + "%.1f".format(Locale.US, idea.rsi) + ")") to rsiOk,
        ("Relative volume at least " + "%.1f".format(Locale.US, volumeThreshold) + "× (" + "%.2f".format(Locale.US, idea.volumeRatio) + "×)") to volumeOk,
        "Spread at or below 10 bps" to spreadOk,
        "Directional order-book depth available and acceptable" to bookOk,
        ("Target 1 offers at least 2.0R (" + "%.2f".format(Locale.US, rr) + "R)") to rewardOk
    ) + idea.strategyChecks
    return EntryGuidance(score, grade, action, trigger, invalidation, zoneLow, zoneHigh, combinedChecks)
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
                Text(guidance.checks.count { it.second == true }.toString() + "/" + guidance.checks.count { it.second != null } + " checks passed", style = MaterialTheme.typography.labelSmall,
                    color = if (guidance.score >= 65) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold)
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
                    Text(if (passed == true) "✓" else "○",
                        color = if (passed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    Text(label + if (passed == null) " · unavailable" else if (passed) " · pass" else " · review",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Checklist points measure setup confluence only; they are not confidence, win probability or trade approval. Missing order-book depth fails the scanner's hard liquidity gate even when spread passes. The READY / WAIT / AVOID decision above always takes priority. Confluence V4 scoring and its existing hard risk gates determine scanner qualification.",
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
        TradeIdeaStage.DO_NOT_ENTER, TradeIdeaStage.AVOID_CHASING -> MaterialTheme.colorScheme.error
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
    if (tracked) return TradeIdeaStage.TRACKING
    if (snapshotAgeMs > 0L && snapshotAgeMs > ideaMaxAgeMs(idea)) return TradeIdeaStage.DO_NOT_ENTER
    if (idea.side == "WAIT" || !tradeIdeaRiskValid(idea) || livePriceInvalidatesPlan(idea)) return TradeIdeaStage.DO_NOT_ENTER
    // Legacy cache entries without this strategy data are not eligible for READY.
    if (idea.strategyChecks.isEmpty() || idea.strategyChecks.any { it.second != true }) {
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
                if (idea.side == "LONG" || idea.side == "SHORT") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SummaryLevel("Entry zone", zoneLow.usd(6) + "–" + zoneHigh.usd(6), Modifier.weight(1.25f))
                        SummaryLevel("Stop", idea.stop.usd(6), Modifier.weight(1f))
                        SummaryLevel("Target 1", idea.target1.usd(6), Modifier.weight(1f))
                        SummaryLevel("R:R", if (tradeIdeaRiskValid(idea)) "%.2fR".format(Locale.US, rewardRisk) else "—", Modifier.weight(0.8f))
                    }
                    Text("Live price  " + livePrice.usd(6), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EntryCoachSummary(liveIdea, snapshotAgeMs, trackedEntry != null)
                    Text("EXIT PLAN  ·  1R / 2R / 3R / 4R+  ·  25% each", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("No directional setup currently qualifies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (!expanded) EntryCoachSummary(liveIdea, snapshotAgeMs, trackedEntry != null)
            if (idea.side == "LONG" || idea.side == "SHORT") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryLevel("Entry zone", zoneLow.usd(6) + "–" + zoneHigh.usd(6), Modifier.weight(1.25f))
                    SummaryLevel("Stop", idea.stop.usd(6), Modifier.weight(1f))
                    SummaryLevel("TP2 · 2R", idea.target1.usd(6), Modifier.weight(1f))
                    SummaryLevel("R:R", if (tradeIdeaRiskValid(idea)) "%.2fR".format(Locale.US, rewardRisk) else "—", Modifier.weight(0.8f))
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

            if (stage == TradeIdeaStage.ENTRY_CONFIRMED || stage == TradeIdeaStage.TRACKING) {
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
                Text("Risk checks passed.", style = MaterialTheme.typography.bodySmall)
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
            } else if (stage == TradeIdeaStage.WAIT_CONFIRMATION) {
                Text("Entry trigger: " + idea.entry.usd(6) + " · confirmation is still missing.", style = MaterialTheme.typography.bodySmall)
            } else {
                val expired = snapshotAgeMs > 0L && snapshotAgeMs > ideaMaxAgeMs(idea)
                Text(if (expired) "This signal has expired. Refresh the market scan before considering it."
                    else if (idea.side == "WAIT") "Trend, momentum and liquidity filters are not aligned. No entry is permitted."
                    else "The risk plan failed validation or has a blocking warning: " + idea.warning,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (density == "Expanded") HistoricalOutcomePanel(idea) else Text(historyOneLine(idea), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) { Text(if (expanded && density == "Compact") "Show less" else if (expanded) "Hide analysis" else "Why this result?") }
            if (expanded) {
                HorizontalDivider()
                if (idea.side == "LONG" || idea.side == "SHORT") EntryGuidancePanel(liveIdea, snapshotAgeMs)
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
