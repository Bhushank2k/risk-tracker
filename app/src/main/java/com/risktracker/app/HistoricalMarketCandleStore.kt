package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Completed Binance futures OHLCV candles retained for offline signal replay. */
internal data class HistoricalMarketCandle(
    val symbol: String,
    val interval: String,
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
) {
    fun isValid(): Boolean = symbol.matches(Regex("[A-Z0-9]{2,30}")) &&
        interval in SUPPORTED_INTERVALS && openTime > 0L && closeTime >= openTime &&
        listOf(open, high, low, close, volume).all { it.isFinite() } &&
        open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 && volume >= 0.0 &&
        high >= maxOf(open, close, low) && low <= minOf(open, close, high)

    companion object {
        val SUPPORTED_INTERVALS = setOf("1m", "5m", "15m", "1h", "4h", "1d")
        const val MAX_CANDLES_PER_SERIES = 1_000
        const val MAX_SERIES = 300

        fun from(symbol: String, interval: String, candle: ScanCandle, openTime: Long): HistoricalMarketCandle =
            HistoricalMarketCandle(symbol, interval, openTime, candle.closeTime, candle.open,
                candle.high, candle.low, candle.close, candle.volume)

        fun merge(existing: List<HistoricalMarketCandle>, incoming: List<HistoricalMarketCandle>): List<HistoricalMarketCandle> {
            val byOpenTime = LinkedHashMap<Long, HistoricalMarketCandle>()
            existing.filter { it.isValid() }.forEach { byOpenTime[it.openTime] = it }
            incoming.filter { it.isValid() }.forEach { byOpenTime[it.openTime] = it }
            return byOpenTime.values.sortedBy { it.openTime }.takeLast(MAX_CANDLES_PER_SERIES)
        }
    }
}

/**
 * Bounded app-private history. Only symbols associated with emitted signals (plus BTCUSDT)
 * are retained, avoiding the storage explosion of persisting every scanned market.
 */
internal object HistoricalMarketCandleStore {
    private const val INDEX_PREFS = "historical_market_candle_index_v1"
    private const val INDEX_KEY = "series"
    private const val UPDATED_KEY = "updatedAt"

    @Synchronized
    fun append(context: Context, candles: List<HistoricalMarketCandle>) {
        val valid = candles.filter { it.isValid() }
        if (valid.isEmpty()) return
        val app = context.applicationContext
        val grouped = valid.groupBy { seriesId(it.symbol, it.interval) }
        val indexPrefs = app.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE)
        val registry = decodeIndex(indexPrefs.getString(INDEX_KEY, "[]"))
            .associateBy { it.first }.toMutableMap()
        grouped.forEach { (id, rows) ->
            val prefs = app.getSharedPreferences("replay_candles_$id", Context.MODE_PRIVATE)
            val old = decodeRows(prefs.getString("candles", "[]"), rows.first().symbol, rows.first().interval)
            val merged = HistoricalMarketCandle.merge(old, rows)
            prefs.edit().putString("candles", encodeRows(merged).toString())
                .putLong(UPDATED_KEY, System.currentTimeMillis()).apply()
            registry[id] = id to System.currentTimeMillis()
        }
        val retained = registry.values.sortedByDescending { it.second }.take(HistoricalMarketCandle.MAX_SERIES)
        val keep = retained.map { it.first }.toSet()
        registry.keys.filter { it !in keep }.forEach { app.getSharedPreferences("replay_candles_$it", Context.MODE_PRIVATE).edit().clear().apply() }
        indexPrefs.edit().putString(INDEX_KEY, JSONArray().also { a ->
            retained.forEach { (id, updatedAt) -> a.put(JSONObject().put("id", id).put("updatedAt", updatedAt)) }
        }.toString()).apply()
    }

    fun load(context: Context, symbol: String, interval: String, limit: Int = HistoricalMarketCandle.MAX_CANDLES_PER_SERIES): List<HistoricalMarketCandle> {
        if (interval !in HistoricalMarketCandle.SUPPORTED_INTERVALS) return emptyList()
        val safeLimit = limit.coerceIn(0, HistoricalMarketCandle.MAX_CANDLES_PER_SERIES)
        if (safeLimit == 0) return emptyList()
        val id = seriesId(symbol, interval)
        val prefs = context.applicationContext.getSharedPreferences("replay_candles_$id", Context.MODE_PRIVATE)
        return decodeRows(prefs.getString("candles", "[]"), symbol, interval).takeLast(safeLimit)
    }

    fun seriesCount(context: Context): Int = decodeIndex(context.applicationContext
        .getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).getString(INDEX_KEY, "[]")).size

    private fun seriesId(symbol: String, interval: String) = "${symbol.uppercase()}_${interval}"

    private fun encodeRows(rows: List<HistoricalMarketCandle>) = JSONArray().also { a ->
        rows.forEach { c -> a.put(JSONObject().put("openTime", c.openTime).put("closeTime", c.closeTime)
            .put("open", c.open).put("high", c.high).put("low", c.low).put("close", c.close).put("volume", c.volume)) }
    }

    private fun decodeRows(raw: String?, symbol: String, interval: String): List<HistoricalMarketCandle> {
        val a = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val x = a.getJSONObject(i)
            HistoricalMarketCandle(symbol, interval, x.getLong("openTime"), x.getLong("closeTime"),
                x.getDouble("open"), x.getDouble("high"), x.getDouble("low"), x.getDouble("close"), x.getDouble("volume"))
                .takeIf { it.isValid() }
        }.getOrNull() }
    }

    private fun decodeIndex(raw: String?): List<Pair<String, Long>> {
        val a = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val x = a.getJSONObject(i); x.getString("id") to x.optLong("updatedAt", 0L)
        }.getOrNull() }
    }
}
