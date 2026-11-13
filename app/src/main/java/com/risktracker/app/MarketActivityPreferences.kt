package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ActivityThresholds(
    val rangeCompressionPercent: Double = 2.5,
    val relativeVolumeWatch: Double = 1.2,
    val relativeVolumeBreakout: Double = 1.2
)

data class MarketLabConfig(
    val symbols: List<String> = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT", "ADAUSDT", "LINKUSDT"),
    val thresholds: ActivityThresholds = ActivityThresholds(),
    val minimumScore: Int = 0
)

data class MarketActivityEvent(
    val id: String,
    val symbol: String,
    val interval: MarketInterval,
    val state: ActivityState,
    val direction: ActivityDirection,
    val price: Double,
    val createdAt: Long,
    val message: String
)

object MarketActivityPreferences {
    private const val PREFS = "market_activity_lab_v1"
    private const val CONFIG = "scanner_config_v2"
    private const val SETUPS = "setup_lifecycle_v1"
    private const val EVENTS = "alert_history_v1"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): MarketLabConfig {
        val json = runCatching { JSONObject(prefs(context).getString(CONFIG, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val defaults = MarketLabConfig()
        val symbols = (0 until json.optJSONArray("symbols").let { it?.length() ?: 0 })
            .mapNotNull { json.optJSONArray("symbols")?.optString(it) }
            .filter { it.matches(Regex("[A-Z0-9]{5,20}")) }
            .distinct()
            .take(40)
            .ifEmpty { defaults.symbols }
        val thresholds = json.optJSONObject("thresholds") ?: JSONObject()
        return MarketLabConfig(
            symbols = symbols,
            thresholds = ActivityThresholds(
                rangeCompressionPercent = thresholds.optDouble("rangeCompressionPercent", defaults.thresholds.rangeCompressionPercent).coerceIn(0.5, 8.0),
                relativeVolumeWatch = thresholds.optDouble("relativeVolumeWatch", defaults.thresholds.relativeVolumeWatch).coerceIn(1.0, 5.0),
                relativeVolumeBreakout = thresholds.optDouble("relativeVolumeBreakout", defaults.thresholds.relativeVolumeBreakout).coerceIn(1.0, 5.0)
            ),
            minimumScore = json.optInt("minimumScore", defaults.minimumScore).coerceIn(0, 100)
        )
    }

    fun parseSymbols(raw: String): List<String> {
        val values = raw.split(",", "\n", ";").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        require(values.isNotEmpty()) { "Add at least one trading pair." }
        require(values.size <= 40) { "Use no more than 40 pairs to keep API requests manageable." }
        require(values.all { it.matches(Regex("[A-Z0-9]{5,20}")) }) { "Use comma-separated Binance symbols such as BTCUSDT or ETHUSDT." }
        require(values.distinct().size == values.size) { "Remove duplicate symbols." }
        return values
    }

    fun saveConfig(context: Context, config: MarketLabConfig) {
        require(config.symbols.isNotEmpty() && config.symbols.size <= 40)
        val thresholds = JSONObject()
            .put("rangeCompressionPercent", config.thresholds.rangeCompressionPercent.coerceIn(0.5, 8.0))
            .put("relativeVolumeWatch", config.thresholds.relativeVolumeWatch.coerceIn(1.0, 5.0))
            .put("relativeVolumeBreakout", config.thresholds.relativeVolumeBreakout.coerceIn(1.0, 5.0))
        val json = JSONObject()
            .put("symbols", JSONArray(config.symbols))
            .put("thresholds", thresholds)
            .put("minimumScore", config.minimumScore.coerceIn(0, 100))
        prefs(context).edit().putString(CONFIG, json.toString()).apply()
    }

    fun loadEvents(context: Context): List<MarketActivityEvent> {
        val array = runCatching { JSONArray(prefs(context).getString(EVENTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                MarketActivityEvent(
                    id = item.getString("id"),
                    symbol = item.getString("symbol"),
                    interval = MarketInterval.valueOf(item.getString("interval")),
                    state = ActivityState.valueOf(item.getString("state")),
                    direction = ActivityDirection.valueOf(item.getString("direction")),
                    price = item.getDouble("price"),
                    createdAt = item.getLong("createdAt"),
                    message = item.getString("message")
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    /** Records state transitions once per symbol/timeframe and invalidates broken setups. */
    @Synchronized
    fun recordAnalysis(context: Context, analysis: ActivityAnalysis): MarketActivityEvent? {
        val store = prefs(context)
        val key = "${analysis.symbol}:${analysis.interval.name}"
        val setups = runCatching { JSONObject(store.getString(SETUPS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val previous = setups.optJSONObject(key)
        val previousState = previous?.optString("state")?.let { runCatching { ActivityState.valueOf(it) }.getOrNull() }
        val previousDirection = previous?.optString("direction")?.let { runCatching { ActivityDirection.valueOf(it) }.getOrNull() }
        val close = analysis.last.close
        val invalidated = when (previousState) {
            ActivityState.WATCH -> when (previousDirection) {
                ActivityDirection.BULLISH -> previous?.optDouble("rangeLow")?.let { close < it } ?: false
                ActivityDirection.BEARISH -> previous?.optDouble("rangeHigh")?.let { close > it } ?: false
                else -> false
            }
            ActivityState.CONFIRMED_HISTORICAL_BREAKOUT -> when (previousDirection) {
                ActivityDirection.BULLISH -> previous?.optDouble("rangeHigh")?.let { close <= it } ?: false
                ActivityDirection.BEARISH -> previous?.optDouble("rangeLow")?.let { close >= it } ?: false
                else -> false
            }
            else -> false
        }
        val nextState = if (invalidated) ActivityState.INVALIDATED else analysis.state
        val isActive = nextState == ActivityState.WATCH || nextState == ActivityState.CONFIRMED_HISTORICAL_BREAKOUT
        val previousWasActive = previousState == ActivityState.WATCH || previousState == ActivityState.CONFIRMED_HISTORICAL_BREAKOUT
        val shouldEmit = nextState != ActivityState.NO_SETUP &&
            (nextState != previousState || (previousWasActive && nextState == ActivityState.INVALIDATED))
        val event = if (shouldEmit) {
            val description = when (nextState) {
                ActivityState.WATCH -> "WATCH setup detected"
                ActivityState.CONFIRMED_HISTORICAL_BREAKOUT -> "Confirmed breakout on completed candle"
                ActivityState.INVALIDATED -> "Setup invalidated by a close back through its invalidation level"
                else -> "Setup cleared"
            }
            MarketActivityEvent(UUID.randomUUID().toString(), analysis.symbol, analysis.interval,
                nextState, analysis.direction, close, System.currentTimeMillis(), description)
        } else null

        if (isActive) {
            setups.put(key, JSONObject()
                .put("state", nextState.name)
                .put("direction", analysis.direction.name)
                .put("rangeHigh", analysis.rangeHigh)
                .put("rangeLow", analysis.rangeLow)
                .put("lastPrice", close)
                .put("updatedAt", System.currentTimeMillis()))
        } else {
            setups.remove(key)
        }
        val editor = store.edit().putString(SETUPS, setups.toString())
        if (event != null) {
            val history = runCatching { JSONArray(store.getString(EVENTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val item = JSONObject()
                .put("id", event.id).put("symbol", event.symbol).put("interval", event.interval.name)
                .put("state", event.state.name).put("direction", event.direction.name)
                .put("price", event.price).put("createdAt", event.createdAt).put("message", event.message)
            val updated = JSONArray().put(item)
            for (i in 0 until minOf(history.length(), 99)) updated.put(history.getJSONObject(i))
            editor.putString(EVENTS, updated.toString())
        }
        editor.apply()
        return event
    }

    fun clearEvents(context: Context) {
        prefs(context).edit().remove(EVENTS).apply()
    }
}
