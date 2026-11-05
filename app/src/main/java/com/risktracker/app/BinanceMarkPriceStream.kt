package com.risktracker.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Cached Futures positions plus public mark-price WebSocket updates. */
class BinanceLivePositionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_live_positions_v1", Context.MODE_PRIVATE)

    fun load(): List<BinanceLiveTrade> = runCatching {
        val rows = JSONArray(prefs.getString("positions", "[]"))
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            BinanceLiveTrade(
                id = row.optString("id"), symbol = row.optString("symbol"),
                market = row.optString("market", "USDⓈ-M Futures"), side = row.optString("side"),
                quantity = row.optDouble("quantity"), entryPrice = row.optDouble("entryPrice"),
                markPrice = row.optDouble("markPrice"), unrealizedPnl = row.optDouble("unrealizedPnl"),
                leverage = row.optInt("leverage", 1), liquidationPrice = row.optDouble("liquidationPrice"),
                status = row.optString("status", "OPEN")
            )
        }
    }.getOrDefault(emptyList())

    fun save(positions: List<BinanceLiveTrade>) {
        val rows = JSONArray()
        positions.forEach { p ->
            rows.put(JSONObject().put("id", p.id).put("symbol", p.symbol).put("market", p.market)
                .put("side", p.side).put("quantity", p.quantity).put("entryPrice", p.entryPrice)
                .put("markPrice", p.markPrice).put("unrealizedPnl", p.unrealizedPnl)
                .put("leverage", p.leverage).put("liquidationPrice", p.liquidationPrice).put("status", p.status))
        }
        prefs.edit().putString("positions", rows.toString()).apply()
    }

    fun restBlockedUntil(): Long = prefs.getLong("rest_blocked_until", 0L)
    fun blockRestUntil(timestamp: Long) { prefs.edit().putLong("rest_blocked_until", timestamp).apply() }
}

/**
 * Public USDⓈ-M Futures mark-price streams for currently open symbols.
 * Reconnects use WebSocket only; this class never polls REST.
 */
class BinanceMarkPriceStream(
    symbols: List<String>,
    private val onMarkPrice: (String, Double) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val streams = symbols.map { it.lowercase() }.distinct().take(100)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "binance-mark-price-ws").apply { isDaemon = true }
    }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var retryDelayMs = 5_000L
    @Volatile private var lastMessageAtMs = 0L
    @Volatile private var staleReported = false

    fun start() {
        if (streams.isEmpty()) { onStatus("No open positions to subscribe to"); return }
        scheduler.scheduleAtFixedRate({
            val lastMessage = lastMessageAtMs
            if (!stopped && lastMessage > 0L && System.currentTimeMillis() - lastMessage > 15_000L && !staleReported) {
                staleReported = true
                onStatus("Stale mark prices · no update for 15 seconds")
            }
        }, 5L, 5L, TimeUnit.SECONDS)
        connect()
    }

    private fun connect() {
        if (stopped) return
        val url = "wss://fstream.binance.com/stream?streams=" + streams.joinToString("/") { "${it}@markPrice@1s" }
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryDelayMs = 5_000L
                lastMessageAtMs = 0L
                staleReported = false
                onStatus("Connected · waiting for mark-price updates")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    if (data.optString("e") != "markPriceUpdate") return
                    val symbol = data.optString("s")
                    val price = data.optString("p").toDoubleOrNull() ?: return
                    if (symbol.isNotBlank() && price > 0.0 && price.isFinite()) {
                        val wasWaitingOrStale = lastMessageAtMs == 0L || staleReported
                        lastMessageAtMs = System.currentTimeMillis()
                        staleReported = false
                        onMarkPrice(symbol, price)
                        if (wasWaitingOrStale) onStatus("Live mark prices updating")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                if (!stopped) { onStatus("Mark-price stream disconnected; reconnecting"); scheduleReconnect() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (!stopped) { onStatus("Mark-price stream closed; reconnecting"); scheduleReconnect() }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        scheduler.schedule({ connect() }, wait, TimeUnit.MILLISECONDS)
    }

    fun close() {
        stopped = true
        socket?.close(1000, "Overview screen closed")
        socket = null
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** Revalues a linear USDT-margined position using its streamed mark price. */
fun BinanceLiveTrade.withMarkPrice(markPrice: Double): BinanceLiveTrade {
    if (quantity <= 0.0 || entryPrice <= 0.0 || markPrice <= 0.0) return this
    val pnl = if (side.equals("LONG", ignoreCase = true)) (markPrice - entryPrice) * quantity
        else (entryPrice - markPrice) * quantity
    return copy(markPrice = markPrice, unrealizedPnl = if (pnl.isFinite()) pnl else unrealizedPnl)
}

/** Extract Binance's IP ban expiry timestamp from its HTTP 418 message. */
fun binanceBanExpiry(message: String): Long? =
    Regex("banned until\\s+(\\d+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()


/** Applies one symbol update to the latest full position list without replacing other symbols' prices. */
fun List<BinanceLiveTrade>.withStreamedMarkPrice(symbol: String, price: Double): List<BinanceLiveTrade> =
    map { position ->
        if (position.market == "USDⓈ-M Futures" && position.symbol.equals(symbol, ignoreCase = true)) {
            position.withMarkPrice(price)
        } else position
    }
