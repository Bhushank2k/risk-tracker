package com.risktracker.cloud

import com.risktracker.app.ConfluenceScannerV4
import com.risktracker.app.TradeIdeaRiskCode
import com.risktracker.app.V2Candle
import com.risktracker.app.V2TradePlan
import com.risktracker.app.isTradeIdeaRiskValid
import com.risktracker.app.isTradeIdeaVolumeEligible
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

private const val BINANCE = "https://fapi.binance.com"
private data class Candidate(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double, val volatilityPct: Double)
private data class BestBook(val bid: Double, val ask: Double)
private data class ScanResult(val alert: JSONObject? = null, val error: String? = null)

private object BinanceRest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val paceLock = Any()
    private var nextRequestAt = 0L

    fun get(path: String): String {
        for (attempt in 0..2) {
            pace()
            val request = HttpRequest.newBuilder(URI.create(BINANCE + path))
                .timeout(Duration.ofSeconds(18)).header("User-Agent", "RiskTrackerCloudScanner/1.0").GET().build()
            val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) {
                if (attempt == 2) throw IllegalStateException("Binance request failed: ${e.message}", e)
                Thread.sleep(500L * (1L shl attempt)); continue
            }
            if (response.statusCode() in 200..299) return response.body()
            val code = response.statusCode()
            if (code == 418) throw IllegalStateException("Binance returned HTTP 418 (IP ban/rate limit); scanner stopped fail-closed")
            if (code == 429 || code >= 500) {
                if (attempt == 2) throw IllegalStateException("Binance HTTP $code: ${response.body().take(240)}")
                val wait = response.headers().firstValue("Retry-After").orElse("2").toLongOrNull()?.coerceIn(1, 60) ?: 2L
                Thread.sleep(wait * 1_000L); continue
            }
            throw IllegalStateException("Binance HTTP $code: ${response.body().take(240)}")
        }
        error("Binance retry loop exhausted")
    }

    private fun pace() = synchronized(paceLock) {
        val now = System.currentTimeMillis()
        val wait = nextRequestAt - now
        if (wait > 0) Thread.sleep(wait)
        nextRequestAt = System.currentTimeMillis() + 120L
    }

    fun candles(symbol: String, interval: String, limit: Int): List<V2Candle> {
        val rows = JSONArray(get("/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=$limit"))
        val now = System.currentTimeMillis()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            runCatching {
                V2Candle(
                    high = row.getString(2).toDouble(), low = row.getString(3).toDouble(),
                    close = row.getString(4).toDouble(), volume = row.getString(5).toDouble(),
                    closeTime = row.getLong(6), open = row.getString(1).toDouble()
                )
            }.getOrNull()
        }.filter { it.closeTime < now && it.close > 0.0 }
    }
}

private fun ema(values: List<Double>, period: Int): Double? {
    if (values.size < period) return null
    val alpha = 2.0 / (period + 1.0)
    var result = values.take(period).average()
    values.drop(period).forEach { result = it * alpha + result * (1.0 - alpha) }
    return result.takeIf { it.isFinite() && it > 0.0 }
}

/** Same BTC H4/H1 EMA20/50/200 regime classifier as the Android scanner. */
private fun btcRegime(h4: List<V2Candle>, h1: List<V2Candle>): String {
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

private fun levels(rows: JSONArray): List<Pair<Double, Double>> = (0 until rows.length()).mapNotNull { i ->
    val row = rows.optJSONArray(i) ?: return@mapNotNull null
    val price = row.optString(0).toDoubleOrNull() ?: return@mapNotNull null
    val qty = row.optString(1).toDoubleOrNull() ?: return@mapNotNull null
    price to qty
}

private fun gateResults(plan: V2TradePlan): JSONObject {
    val codes = plan.riskCodes
    val gates = linkedMapOf(
        "liquidity" to (TradeIdeaRiskCode.LOW_LIQUIDITY !in codes),
        "stop_geometry" to (TradeIdeaRiskCode.INVALID_STOP !in codes),
        "net_reward_risk" to (TradeIdeaRiskCode.INSUFFICIENT_REWARD_RISK !in codes),
        "funding" to (TradeIdeaRiskCode.FUNDING_DATA_UNAVAILABLE !in codes && TradeIdeaRiskCode.ADVERSE_FUNDING !in codes),
        "volatility" to (TradeIdeaRiskCode.VOLATILITY_OUT_OF_RANGE !in codes),
        "entry_extension" to (TradeIdeaRiskCode.EXTENDED_ENTRY !in codes),
        "relative_volume" to (TradeIdeaRiskCode.LOW_RELATIVE_VOLUME !in codes),
        "entry_confirmation" to (TradeIdeaRiskCode.WAITING_FOR_ENTRY_CONFIRMATION !in codes),
        "structure" to (TradeIdeaRiskCode.STRUCTURE_NOT_CONFIRMED !in codes),
        "supported_timeframe" to (plan.horizon.startsWith("Intraday", ignoreCase = true) && TradeIdeaRiskCode.UNSUPPORTED_TIMEFRAME !in codes),
        "ready_score" to (plan.score >= 75)
    )
    return JSONObject().also { out -> gates.forEach { (key, value) -> out.put(key, value) } }
}

private fun buildAlert(candidate: Candidate, plan: V2TradePlan, setupCloseTime: Long): JSONObject? {
    if (!plan.entryTriggered || !plan.horizon.startsWith("Intraday", ignoreCase = true) || plan.score < 75) return null
    if (!isTradeIdeaRiskValid(plan.side, plan.entry, plan.stop, plan.target1, plan.target2, plan.riskCodes)) return null
    val gates = gateResults(plan)
    if (gates.keys().asSequence().any { !gates.optBoolean(it, false) }) return null
    val id = "${candidate.symbol}-${plan.side}-${plan.setup.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${setupCloseTime}"
    return JSONObject()
        .put("alert_id", id)
        .put("symbol", candidate.symbol)
        .put("side", plan.side)
        .put("setup", plan.setup)
        .put("horizon", "Intraday")
        .put("status", "READY")
        .put("created_at", System.currentTimeMillis() / 1000.0)
        .put("entry", plan.entry)
        .put("stop", plan.stop)
        .put("target1", plan.target1)
        .put("target2", plan.target2)
        .put("score", plan.score)
        .put("risk_gates", gates)
}

private fun postAlert(alert: JSONObject) {
    val base = System.getenv("ALERT_RELAY_URL")?.trim()?.trimEnd('/')
        ?: error("ALERT_RELAY_URL is required")
    val url = if (base.endsWith("/alerts")) base else "$base/alerts"
    val token = System.getenv("ALERT_API_TOKEN")?.takeIf { it.isNotBlank() }
        ?: error("ALERT_API_TOKEN is required")
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(alert.toString())).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) throw IllegalStateException("Alert relay HTTP ${response.statusCode()}: ${response.body().take(200)}")
}

private fun scanCandidate(
    candidate: Candidate, books: Map<String, BestBook>, funding: Map<String, Double>, btcBias: String
): ScanResult = try {
    val h4 = BinanceRest.candles(candidate.symbol, "4h", 260)
    val h1 = BinanceRest.candles(candidate.symbol, "1h", 260)
    val m15 = BinanceRest.candles(candidate.symbol, "15m", 260)
    val m5 = BinanceRest.candles(candidate.symbol, "5m", 220)
    val depth = JSONObject(BinanceRest.get("/fapi/v1/depth?symbol=${candidate.symbol}&limit=5"))
    val book = books[candidate.symbol]
    val micro = buildMarketMicrostructure(
        book?.bid, book?.ask, levels(depth.optJSONArray("bids") ?: JSONArray()),
        levels(depth.optJSONArray("asks") ?: JSONArray()), funding[candidate.symbol]
    )
    val plan = ConfluenceScannerV4.evaluate(h4, h1, m15, m5, micro, candidate.quoteVolume, btcBias, "Intraday")
        ?: return ScanResult()
    ScanResult(buildAlert(candidate, plan, m15.last().closeTime))
} catch (e: Exception) {
    ScanResult(error = "${candidate.symbol}: ${e.message ?: e.javaClass.simpleName}")
}

fun main() {
    val info = JSONArray(BinanceRest.get("/fapi/v1/exchangeInfo"))
    val allowed = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter {
        it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" && it.optString("quoteAsset") == "USDT"
    }.map { it.optString("symbol") }.toSet()
    val tickers = JSONArray(BinanceRest.get("/fapi/v1/ticker/24hr"))
    val candidates = (0 until tickers.length()).mapNotNull { i ->
        val row = tickers.optJSONObject(i) ?: return@mapNotNull null
        val symbol = row.optString("symbol")
        val quote = row.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        val last = row.optString("lastPrice").toDoubleOrNull() ?: 0.0
        if (symbol !in allowed || !isTradeIdeaVolumeEligible(quote) || last <= 0.0) return@mapNotNull null
        val change = row.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
        val high = row.optString("highPrice").toDoubleOrNull() ?: last
        val low = row.optString("lowPrice").toDoubleOrNull() ?: last
        Candidate(symbol, quote, change, last, ((high - low) / last * 100.0).coerceAtLeast(0.0))
    }.sortedByDescending { it.volatilityPct + abs(it.changePct) * 0.5 }

    val bookRows = JSONArray(BinanceRest.get("/fapi/v1/ticker/bookTicker"))
    val books = (0 until bookRows.length()).mapNotNull { i ->
        val row = bookRows.optJSONObject(i) ?: return@mapNotNull null
        val bid = row.optString("bidPrice").toDoubleOrNull() ?: return@mapNotNull null
        val ask = row.optString("askPrice").toDoubleOrNull() ?: return@mapNotNull null
        row.optString("symbol") to BestBook(bid, ask)
    }.toMap()
    val premiumRows = JSONArray(BinanceRest.get("/fapi/v1/premiumIndex"))
    val funding = (0 until premiumRows.length()).mapNotNull { i ->
        val row = premiumRows.optJSONObject(i) ?: return@mapNotNull null
        val rate = row.optString("lastFundingRate").toDoubleOrNull() ?: return@mapNotNull null
        row.optString("symbol") to rate
    }.toMap()
    val btcH4 = BinanceRest.candles("BTCUSDT", "4h", 260)
    val btcH1 = BinanceRest.candles("BTCUSDT", "1h", 260)
    val btcBias = btcRegime(btcH4, btcH1)
    println("Cloud scan: ${candidates.size} active USDT perpetuals above the \$2M 24h quote-volume floor; BTC regime=$btcBias")

    val pool = Executors.newFixedThreadPool(6)
    try {
        val futures = pool.invokeAll(candidates.map { candidate -> Callable { scanCandidate(candidate, books, funding, btcBias) } })
        var ready = 0; var failures = 0
        val seenThisRun = mutableSetOf<String>()
        futures.forEach { future ->
            val result = future.get()
            if (result.error != null) { failures++; println("Market data error: ${result.error}") }
            val alert = result.alert ?: return@forEach
            val id = alert.getString("alert_id")
            if (!seenThisRun.add(id)) return@forEach
            postAlert(alert)
            ready++
            println("Relayed READY Intraday: ${alert.getString("symbol")} ${alert.getString("side")} ${alert.getString("setup")} score=${alert.getInt("score")}")
        }
        println("Cloud scan complete: ${candidates.size} markets, $ready READY alerts relayed, $failures market-data failures.")
    } finally {
        pool.shutdownNow()
    }
}
