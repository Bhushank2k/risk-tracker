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
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.math.abs

private const val BYBIT = "https://api.bybit.com"
private data class Candidate(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double, val volatilityPct: Double)
private data class ScanResult(val alert: JSONObject? = null, val error: String? = null)

/** Public Bybit linear USDT perpetual market data. No API key or trading permission is used. */
private object BybitRest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val paceLock = Any()
    private var nextRequestAt = 0L

    fun get(path: String): JSONObject {
        pace()
        val request = HttpRequest.newBuilder(URI.create(BYBIT + path))
            .timeout(Duration.ofSeconds(20)).header("User-Agent", "RiskTrackerCloudScanner/1.0").GET().build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        catch (e: Exception) { throw IllegalStateException("Bybit request failed: ${e.message}", e) }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Bybit HTTP ${response.statusCode()}: ${response.body().take(240)}")
        }
        val body = JSONObject(response.body())
        if (body.optInt("retCode", -1) != 0) {
            throw IllegalStateException("Bybit API ${body.optInt("retCode")}: ${body.optString("retMsg").take(200)}")
        }
        return body.optJSONObject("result") ?: error("Bybit response did not contain result")
    }

    private fun pace() = synchronized(paceLock) {
        val wait = nextRequestAt - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        nextRequestAt = System.currentTimeMillis() + 120L
    }

    fun candles(symbol: String, interval: String, limit: Int): List<V2Candle> {
        val rows = get("/v5/market/kline?category=linear&symbol=$symbol&interval=$interval&limit=$limit")
            .optJSONArray("list") ?: JSONArray()
        val now = System.currentTimeMillis()
        // Bybit returns newest candle first; the scanner expects chronological order.
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            runCatching {
                V2Candle(
                    open = row.getString(1).toDouble(), high = row.getString(2).toDouble(),
                    low = row.getString(3).toDouble(), close = row.getString(4).toDouble(),
                    volume = row.getString(5).toDouble(), closeTime = row.getString(0).toLong() +
                        when (interval) { "240" -> 240L; "60" -> 60L; "15" -> 15L; "5" -> 5L; else -> error("Unsupported Bybit interval: $interval") } *
                        60L * 1000L
                )
            }.getOrNull()
        }.filter { it.closeTime < now && it.close > 0.0 }.sortedBy { it.closeTime }
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
    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN")?.takeIf { it.isNotBlank() }
    val telegramChatId = System.getenv("TELEGRAM_CHAT_ID")?.takeIf { it.isNotBlank() }
    if (telegramToken != null && telegramChatId != null) {
        fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val message = buildString {
            append("<b>Risk Tracker · READY ${html(alert.getString("horizon"))}</b>\n")
            append("<b>${html(alert.getString("symbol"))} · ${html(alert.getString("side"))}</b>\n")
            append("Setup: ${html(alert.getString("setup"))}\n")
            append("Entry: ${alert.get("entry")} · Stop: ${alert.get("stop")}\n")
            append("Target 1: ${alert.get("target1")}\n")
            append("Alert ID: <code>${html(alert.getString("alert_id"))}</code>\n")
            append("<i>Informational signal only. Verify live market conditions.</i>")
        }
        val payload = JSONObject().put("chat_id", telegramChatId).put("text", message)
            .put("parse_mode", "HTML").put("disable_web_page_preview", true)
        val request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot${telegramToken}/sendMessage"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299 || !JSONObject(response.body()).optBoolean("ok", false)) {
            throw IllegalStateException("Telegram delivery failed: HTTP ${response.statusCode()}")
        }
        return
    }

    val base = System.getenv("ALERT_RELAY_URL")?.trim()?.trimEnd('/')
        ?: error("Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID, or configure ALERT_RELAY_URL and ALERT_API_TOKEN")
    val url = if (base.endsWith("/alerts")) base else "$base/alerts"
    val token = System.getenv("ALERT_API_TOKEN")?.takeIf { it.isNotBlank() }
        ?: error("ALERT_API_TOKEN is required when using ALERT_RELAY_URL")
    val uri = URI.create(url)
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "ALERT_RELAY_URL must be an HTTPS URL"
    }
    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(alert.toString())).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) throw IllegalStateException("Alert relay HTTP ${response.statusCode()}: ${response.body().take(200)}")
}

private fun scanCandidate(candidate: Candidate, btcBias: String): ScanResult = try {
    val h4 = BybitRest.candles(candidate.symbol, "240", 260)
    val h1 = BybitRest.candles(candidate.symbol, "60", 260)
    val m15 = BybitRest.candles(candidate.symbol, "15", 260)
    val m5 = BybitRest.candles(candidate.symbol, "5", 220)
    // Fetch the best quote, five-level depth and funding close together for this symbol.
    // A single all-market snapshot at scan start could be minutes old for later symbols.
    val ticker = BybitRest.get("/v5/market/tickers?category=linear&symbol=\${candidate.symbol}")
        .optJSONArray("list")?.optJSONObject(0) ?: error("Bybit ticker missing")
    val book = ticker
    val premium = ticker
    val depth = JSONObject(BybitRest.get("/v5/market/orderbook?category=linear&symbol=${candidate.symbol}&limit=5"))
    val micro = buildMarketMicrostructure(
        book.optString("bid1Price").toDoubleOrNull(), book.optString("ask1Price").toDoubleOrNull(),
        levels(depth.optJSONArray("b") ?: JSONArray()), levels(depth.optJSONArray("a") ?: JSONArray()),
        premium.optString("fundingRate").toDoubleOrNull()
    )
    val plan = ConfluenceScannerV4.evaluate(h4, h1, m15, m5, micro, candidate.quoteVolume, btcBias, "Intraday")
        ?: return ScanResult()
    ScanResult(buildAlert(candidate, plan, m15.last().closeTime))
} catch (e: Exception) {
    ScanResult(error = "${candidate.symbol}: ${e.message ?: e.javaClass.simpleName}")
}

fun main() {
    val instruments = BybitRest.get("/v5/market/instruments-info?category=linear&limit=1000")
        .optJSONArray("list") ?: JSONArray()
    val allowed = (0 until instruments.length()).mapNotNull { instruments.optJSONObject(it) }.filter {
        it.optString("status") == "Trading" && it.optString("contractType") == "LinearPerpetual" &&
            it.optString("quoteCoin") == "USDT" && it.optString("settleCoin") == "USDT"
    }.map { it.optString("symbol") }.toSet()
    val tickers = BybitRest.get("/v5/market/tickers?category=linear")
        .optJSONArray("list") ?: JSONArray()
    val candidates = (0 until tickers.length()).mapNotNull { i ->
        val row = tickers.optJSONObject(i) ?: return@mapNotNull null
        val symbol = row.optString("symbol")
        val quote = row.optString("turnover24h").toDoubleOrNull() ?: 0.0
        val last = row.optString("lastPrice").toDoubleOrNull() ?: 0.0
        if (symbol !in allowed || !isTradeIdeaVolumeEligible(quote) || last <= 0.0) return@mapNotNull null
        val change = (row.optString("price24hPcnt").toDoubleOrNull() ?: 0.0) * 100.0
        val high = row.optString("highPrice24h").toDoubleOrNull() ?: last
        val low = row.optString("lowPrice24h").toDoubleOrNull() ?: last
        Candidate(symbol, quote, change, last, ((high - low) / last * 100.0).coerceAtLeast(0.0))
    }.sortedByDescending { it.volatilityPct + abs(it.changePct) * 0.5 }

    val btcH4 = BybitRest.candles("BTCUSDT", "240", 260)
    val btcH1 = BybitRest.candles("BTCUSDT", "60", 260)
    val btcBias = btcRegime(btcH4, btcH1)
    println("Bybit scan: ${candidates.size} active USDT linear perpetuals above the $2M 24h turnover floor; BTC regime=$btcBias")

    val pool = Executors.newFixedThreadPool(6)
    try {
        val completed = ExecutorCompletionService<ScanResult>(pool)
        candidates.forEach { candidate -> completed.submit(Callable { scanCandidate(candidate, btcBias) }) }
        var ready = 0; var failures = 0
        val seenThisRun = mutableSetOf<String>()
        // Relay each result as soon as its symbol finishes; waiting for every market
        // would make early READY alerts stale before the relay's freshness check.
        repeat(candidates.size) {
            val result = completed.take().get()
            if (result.error != null) { failures++; println("Market data error: ${result.error}") }
            val alert = result.alert ?: return@repeat
            val id = alert.getString("alert_id")
            if (!seenThisRun.add(id)) return@repeat
            postAlert(alert)
            ready++
            println("Sent READY Intraday: ${alert.getString("symbol")} ${alert.getString("side")} ${alert.getString("setup")} score=${alert.getInt("score")}")
        }
        println("Bybit scan complete: ${candidates.size} markets, $ready READY alerts relayed, $failures market-data failures.")
    } finally {
        pool.shutdownNow()
    }
}
