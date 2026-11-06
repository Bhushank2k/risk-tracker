package com.risktracker.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public-only Binance USD-M Futures market stream.
 *
 * REST is intentionally not used for live polling. A small, paced REST bootstrap
 * loads historical candles; WebSockets carry subsequent ticker, candle, depth and
 * mark-price/funding updates. No API key or user-data stream is required.
 */
internal object BinanceFuturesStream {
    private const val MARKET_URL = "wss://fstream.binance.com/market/stream?streams="
    private const val PUBLIC_URL = "wss://fstream.binance.com/public/stream?streams="
    private const val MAX_AGE_MS = 15_000L

    private data class Book(
        val bid: Double,
        val ask: Double,
        val bidNotional: Double,
        val askNotional: Double,
        val updatedAt: Long
    )
    private data class Ticker(
        val last: Double,
        val quoteVolume: Double?,
        val changePct: Double?,
        val updatedAt: Long
    )
    private data class Funding(val rate: Double, val updatedAt: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val books = ConcurrentHashMap<String, Book>()
    private val tickers = ConcurrentHashMap<String, Ticker>()
    private val funding = ConcurrentHashMap<String, Funding>()
    private val candles = ConcurrentHashMap<String, JSONObject>()
    private val marketConnecting = AtomicBoolean(false)
    private val publicConnecting = AtomicBoolean(false)
    @Volatile private var marketSocket: WebSocket? = null
    @Volatile private var publicSocket: WebSocket? = null
    @Volatile private var symbols: List<String> = emptyList()

    @Synchronized
    fun start(requestedSymbols: List<String>) {
        val normalized = requestedSymbols.map { it.lowercase() }.distinct().sorted()
        if (normalized.isEmpty()) return
        if (symbols == normalized && (marketSocket != null || marketConnecting.get())) return
        symbols = normalized
        marketSocket?.close(1000, "refresh subscriptions")
        publicSocket?.close(1000, "refresh subscriptions")
        marketSocket = null
        publicSocket = null
        connectMarket()
        connectPublic()
    }

    private fun connectMarket() {
        if (!marketConnecting.compareAndSet(false, true)) return
        val streamNames = symbols.flatMap { symbol ->
            listOf(
                "$symbol@kline_4h", "$symbol@kline_1h", "$symbol@kline_15m",
                "$symbol@kline_5m", "$symbol@markPrice@1s"
            )
        }
        open(MARKET_URL + streamNames.joinToString("/"), isMarket = true)
    }

    private fun connectPublic() {
        if (!publicConnecting.compareAndSet(false, true)) return
        val streamNames = symbols.flatMap { symbol ->
            listOf("$symbol@bookTicker", "$symbol@depth20@100ms", "$symbol@ticker")
        }
        open(PUBLIC_URL + streamNames.joinToString("/"), isMarket = false)
    }

    private fun open(url: String, isMarket: Boolean) {
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isMarket) {
                    marketSocket = webSocket
                    marketConnecting.set(false)
                } else {
                    publicSocket = webSocket
                    publicConnecting.set(false)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { consume(JSONObject(text)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected(isMarket, webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected(isMarket, webSocket)
            }
        })
    }

    private fun disconnected(isMarket: Boolean, socket: WebSocket) {
        if (isMarket) {
            if (marketSocket === socket) marketSocket = null
            marketConnecting.set(false)
        } else {
            if (publicSocket === socket) publicSocket = null
            publicConnecting.set(false)
        }
        // Bounded reconnect cadence; never tight-loop on network errors.
        scope.launch {
            delay(5_000)
            if (isMarket && marketSocket == null) connectMarket()
            if (!isMarket && publicSocket == null) connectPublic()
        }
    }

    private fun consume(envelope: JSONObject) {
        val data = envelope.optJSONObject("data") ?: envelope
        val streamName = envelope.optString("stream")
        val symbol = data.optString("s").ifBlank { streamName.substringBefore("@", "") }.lowercase()
        if (symbol.isBlank()) return
        val now = System.currentTimeMillis()
        when (data.optString("e")) {
            "bookTicker" -> {
                val bid = data.optString("b").toDoubleOrNull() ?: return
                val ask = data.optString("a").toDoubleOrNull() ?: return
                if (bid > 0.0 && ask > bid) {
                    val old = books[symbol]
                    books[symbol] = Book(bid, ask, old?.bidNotional ?: 0.0, old?.askNotional ?: 0.0, now)
                }
            }
            "24hrTicker" -> {
                val last = data.optString("c").toDoubleOrNull() ?: return
                tickers[symbol] = Ticker(
                    last, data.optString("q").toDoubleOrNull(),
                    data.optString("P").toDoubleOrNull(), now
                )
            }
            "depthUpdate" -> updateDepth(symbol, data, now)
            "markPriceUpdate" -> {
                val rate = data.optString("r").toDoubleOrNull() ?: return
                funding[symbol] = Funding(rate, now)
            }
            "kline" -> {
                val k = data.optJSONObject("k") ?: return
                candles["$symbol:${k.optString("i")}"] = JSONObject(k.toString())
            }
        }

        // Partial-depth streams are identified by their b/a arrays, not by event type.
        if ((data.optJSONArray("b") != null && data.optJSONArray("a") != null) ||
            (data.optJSONArray("bids") != null && data.optJSONArray("asks") != null)) {
            updateDepth(symbol, data, now)
        }
    }

    private fun updateDepth(symbol: String, data: JSONObject, now: Long) {
        val bids = data.optJSONArray("b") ?: data.optJSONArray("bids") ?: JSONArray()
        val asks = data.optJSONArray("a") ?: data.optJSONArray("asks") ?: JSONArray()
        if (bids.length() == 0 || asks.length() == 0) return
        fun notional(rows: JSONArray): Double = (0 until rows.length()).sumOf { index ->
            val row = rows.optJSONArray(index) ?: return@sumOf 0.0
            val price = row.optString(0).toDoubleOrNull() ?: 0.0
            val quantity = row.optString(1).toDoubleOrNull() ?: 0.0
            price * quantity
        }
        val bidRow = bids.optJSONArray(0) ?: return
        val askRow = asks.optJSONArray(0) ?: return
        val bid = bidRow.optString(0).toDoubleOrNull() ?: return
        val ask = askRow.optString(0).toDoubleOrNull() ?: return
        if (bid > 0.0 && ask > bid) books[symbol] = Book(bid, ask, notional(bids), notional(asks), now)
    }

    fun latestTicker(symbol: String): Triple<Double, Double?, Double?>? {
        val key = symbol.lowercase()
        val row = tickers[key]
        if (row != null && System.currentTimeMillis() - row.updatedAt <= MAX_AGE_MS && row.last > 0.0) {
            return Triple(row.last, row.quoteVolume, row.changePct)
        }
        val book = books[key]
        if (book != null && System.currentTimeMillis() - book.updatedAt <= MAX_AGE_MS) {
            return Triple((book.bid + book.ask) / 2.0, null, null)
        }
        return null
    }

    fun microstructure(symbol: String): MarketMicrostructure {
        val key = symbol.lowercase()
        val book = books[key]?.takeIf { System.currentTimeMillis() - it.updatedAt <= MAX_AGE_MS }
            ?: return MarketMicrostructure(null, null, funding[key]?.rate, null)
        val spread = ((book.ask - book.bid) / ((book.ask + book.bid) / 2.0)) * 10_000.0
        val total = book.bidNotional + book.askNotional
        val imbalance = if (total > 0.0) (book.bidNotional - book.askNotional) / total else null
        val rate = funding[key]?.takeIf { System.currentTimeMillis() - it.updatedAt <= 60_000L }?.rate
        return MarketMicrostructure(spread.takeIf { it.isFinite() }, imbalance?.takeIf { it.isFinite() }, rate, null)
    }

    fun connectionStatus(): String = when {
        marketSocket != null && publicSocket != null -> "connected"
        marketSocket != null || publicSocket != null -> "partially connected"
        else -> "connecting"
    }
}
