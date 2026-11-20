package com.risktracker.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class MarketMoverPhase { NOTABLE_MOVE, STRONG_MOVE, EXTENDED_MOVE }

data class MarketMover(
    val symbol: String,
    val lastPrice: Double,
    val changePercent24h: Double,
    val quoteVolume24h: Double,
    val direction: ActivityDirection,
    val phase: MarketMoverPhase
)

/**
 * Broad-universe discovery, intentionally separate from setup qualification.
 * It ranks every currently trading Binance USD-M USDT perpetual from exchangeInfo
 * and joins the 24h all-symbol ticker snapshot. It never creates READY trade ideas.
 */
object MarketMoversDiscovery {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: List<MarketMover> = emptyList()
    private const val CACHE_MS = 15_000L

    suspend fun discover(forceRefresh: Boolean = false): List<MarketMover> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - cachedAt < CACHE_MS && cached.isNotEmpty()) return@withContext cached

        fun getJson(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Binance discovery HTTP ${response.code}")
            response.body?.string() ?: error("Empty Binance discovery response")
        }

        val exchange = JSONObject(getJson("https://fapi.binance.com/fapi/v1/exchangeInfo"))
        val eligible = exchange.optJSONArray("symbols") ?: JSONArray()
        val perpetuals = buildSet {
            for (i in 0 until eligible.length()) {
                val row = eligible.optJSONObject(i) ?: continue
                if (row.optString("status") == "TRADING" &&
                    row.optString("contractType") == "PERPETUAL" &&
                    row.optString("quoteAsset") == "USDT") {
                    row.optString("symbol").takeIf { it.matches(Regex("[A-Z0-9]{5,24}")) }?.let(::add)
                }
            }
        }

        val tickers = JSONArray(getJson("https://fapi.binance.com/fapi/v1/ticker/24hr"))
        val result = buildList {
            for (i in 0 until tickers.length()) {
                val row = tickers.optJSONObject(i) ?: continue
                val symbol = row.optString("symbol")
                if (symbol !in perpetuals) continue
                val price = row.optString("lastPrice").toDoubleOrNull() ?: continue
                val change = row.optString("priceChangePercent").toDoubleOrNull() ?: continue
                val volume = row.optString("quoteVolume").toDoubleOrNull() ?: continue
                if (!price.isFinite() || price <= 0.0 || !change.isFinite() ||
                    !volume.isFinite()) continue
                val direction = when {
                    change > 0.0 -> ActivityDirection.BULLISH
                    change < 0.0 -> ActivityDirection.BEARISH
                    else -> ActivityDirection.NEUTRAL
                }
                if (direction == ActivityDirection.NEUTRAL) continue
                val phase = when {
                    abs(change) >= 8.0 -> MarketMoverPhase.EXTENDED_MOVE
                    abs(change) >= 3.0 -> MarketMoverPhase.STRONG_MOVE
                    else -> MarketMoverPhase.NOTABLE_MOVE
                }
                add(MarketMover(symbol, price, change, volume, direction, phase))
            }
        }.sortedByDescending { abs(it.changePercent24h) }

        cached = result
        cachedAt = now
        result
    }

    internal fun phaseFor(changePercent: Double): MarketMoverPhase = when {
        abs(changePercent) >= 8.0 -> MarketMoverPhase.EXTENDED_MOVE
        abs(changePercent) >= 3.0 -> MarketMoverPhase.STRONG_MOVE
        else -> MarketMoverPhase.NOTABLE_MOVE
    }
}
