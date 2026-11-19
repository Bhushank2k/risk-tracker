package com.risktracker.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ExplosionUniverse(val symbols: List<String>, val excluded: Int)
data class ExplosionScanResult(val signals: List<ExplosionSignal>, val scanned: Int, val failed: List<String>, val updatedAt: Long)

object CoinExplosionRadarDataSource {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MarketCandle>>>()

    private fun get(path: String): String {
        val request = Request.Builder().url("https://fapi.binance.com$path").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance HTTP ${response.code} for $path")
            return response.body?.string() ?: error("Empty Binance response")
        }
    }

    suspend fun scan(config: ExplosionConfig = ExplosionConfig()): ExplosionScanResult = withContext(Dispatchers.IO) {
        val exchange = JSONObject(get("/fapi/v1/exchangeInfo"))
        val tradable = exchange.optJSONArray("symbols") ?: JSONArray()
        val universe = (0 until tradable.length()).mapNotNull { i ->
            val s = tradable.optJSONObject(i) ?: return@mapNotNull null
            val symbol = s.optString("symbol")
            if (s.optString("status") == "TRADING" && s.optString("contractType") == "PERPETUAL" &&
                s.optString("quoteAsset") == "USDT" && symbol.matches(Regex("[A-Z0-9]{5,20}"))) symbol else null
        }.distinct()
        val tickers = JSONArray(get("/fapi/v1/ticker/24hr"))
        val tickerMap = (0 until tickers.length()).mapNotNull { i ->
            val o = tickers.optJSONObject(i) ?: return@mapNotNull null
            val symbol = o.optString("symbol")
            val qv = o.optString("quoteVolume").toDoubleOrNull()
            val change = o.optString("priceChangePercent").toDoubleOrNull()
            if (symbol in universe && qv != null && change != null && qv.isFinite() && change.isFinite()) symbol to (qv to change) else null
        }.toMap()
        val premium = JSONArray(get("/fapi/v1/premiumIndex"))
        val funding = (0 until premium.length()).mapNotNull { i ->
            val o = premium.optJSONObject(i) ?: return@mapNotNull null
            o.optString("symbol").takeIf { it in universe }?.let { it to o.optString("lastFundingRate").toDoubleOrNull() }
        }.toMap()
        val book = JSONArray(get("/fapi/v1/ticker/bookTicker"))
        val spreads = (0 until book.length()).mapNotNull { i ->
            val o = book.optJSONObject(i) ?: return@mapNotNull null
            val symbol = o.optString("symbol")
            val bid = o.optString("bidPrice").toDoubleOrNull()
            val ask = o.optString("askPrice").toDoubleOrNull()
            if (symbol in universe && bid != null && ask != null && bid > 0.0 && ask >= bid) {
                symbol to ((ask - bid) / ((ask + bid) / 2.0) * 10_000.0)
            } else null
        }.toMap()
        val btcChange = tickerMap["BTCUSDT"]?.second
        val semaphore = Semaphore(8)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val signals = coroutineScope {
            universe.map { symbol ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val rows = candles(symbol)
                            val qv = tickerMap[symbol]?.first ?: 0.0
                            val oiChange = if (qv >= 10_000_000.0) runCatching { openInterestChange(symbol) }.getOrNull() else null
                            val context = ExplosionMarketContext(qv, spreads[symbol], funding[symbol], btcChange,
                                oiChange, oiChange != null)
                            CoinExplosionRadarEngine.analyze(symbol, rows, context, config)
                        }.onFailure { failures += symbol + ": " + (it.message ?: "scan failed") }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        ExplosionScanResult(signals.sortedWith(compareByDescending<ExplosionSignal> { it.score }.thenByDescending { it.quoteVolume24h }),
            universe.size, failures.toList(), System.currentTimeMillis())
    }

    suspend fun replay(symbol: String, config: ExplosionConfig = ExplosionConfig()): ExplosionReplayStats =\n        CoinExplosionRadarEngine.replay(candles(symbol), config)\n\n    private suspend fun candles(symbol: String): List<MarketCandle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[symbol]?.takeIf { now - it.first < 60_000L }?.let { return@withContext it.second }
        val rows = JSONArray(get("/fapi/v1/klines?symbol=$symbol&interval=1h&limit=120"))
        val candles = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONArray(i) ?: return@mapNotNull null
            runCatching { MarketCandle(r.getLong(0), r.getString(1).toDouble(), r.getString(2).toDouble(),
                r.getString(3).toDouble(), r.getString(4).toDouble(), r.getString(5).toDouble(),
                r.getLong(6), r.getString(7).toDouble(), r.getString(9).toDouble(), r.getString(10).toDouble()) }.getOrNull()
        }.filter { it.closeTime < now }
        cache[symbol] = now to candles
        candles
    }

    private suspend fun openInterestChange(symbol: String): Double? = withContext(Dispatchers.IO) {
        val rows = JSONArray(get("/futures/data/openInterestHist?symbol=$symbol&period=1h&limit=5"))
        if (rows.length() < 2) return@withContext null
        val first = rows.optJSONObject(0)?.optString("sumOpenInterestValue")?.toDoubleOrNull() ?: return@withContext null
        val last = rows.optJSONObject(rows.length() - 1)?.optString("sumOpenInterestValue")?.toDoubleOrNull() ?: return@withContext null
        if (first <= 0.0) null else (last / first - 1.0) * 100.0
    }
}
