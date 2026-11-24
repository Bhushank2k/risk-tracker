package com.risktracker.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MarketActivityCheckpoint(
    val interval: MarketInterval,
    val selectedSymbol: String,
    val savedAt: Long,
    val candles: Map<String, List<MarketCandle>>
)

/**
 * Durable scanner recovery point. Stored in an app-private file rather than
 * SharedPreferences because 40 symbols x 240 candles can exceed preference limits.
 */
object MarketActivityCheckpointStore {
    private const val FILE_NAME = "market_activity_checkpoint_v1.json"
    private const val MAX_CANDLES_PER_SYMBOL = 240

    suspend fun save(context: Context, checkpoint: MarketActivityCheckpoint) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("version", 1)
            .put("interval", checkpoint.interval.name)
            .put("selectedSymbol", checkpoint.selectedSymbol)
            .put("savedAt", checkpoint.savedAt)
        val symbols = JSONObject()
        checkpoint.candles.forEach { (key, values) ->
            val array = JSONArray()
            values.takeLast(MAX_CANDLES_PER_SYMBOL).forEach { candle ->
                array.put(JSONArray()
                    .put(candle.openTime).put(candle.open).put(candle.high).put(candle.low)
                    .put(candle.close).put(candle.volume).put(candle.closeTime).put(candle.quoteVolume)
                    .put(candle.takerBuyBaseVolume).put(candle.takerBuyQuoteVolume))
            }
            symbols.put(key, array)
        }
        root.put("candles", symbols)

        val target = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
    }

    suspend fun load(context: Context): MarketActivityCheckpoint? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            require(root.optInt("version") == 1)
            val interval = MarketInterval.valueOf(root.getString("interval"))
            val savedAt = root.getLong("savedAt")
            val selected = root.optString("selectedSymbol").takeIf { it.matches(Regex("[A-Z0-9]{5,20}")) }
                ?: "BTCUSDT"
            val stored = root.optJSONObject("candles") ?: JSONObject()
            val history = linkedMapOf<String, List<MarketCandle>>()
            val keys = stored.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = stored.optJSONArray(key) ?: continue
                val values = (0 until array.length()).mapNotNull { index ->
                    runCatching {
                        val row = array.getJSONArray(index)
                        require(row.length() >= 8)
                        MarketCandle(
                            row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3),
                            row.getDouble(4), row.getDouble(5), row.getLong(6), row.getDouble(7),
                            row.optDouble(8, 0.0), row.optDouble(9, 0.0)
                        ).takeIf { candle ->
                            candle.openTime > 0 && candle.closeTime >= candle.openTime &&
                                listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } &&
                                candle.open > 0 && candle.close > 0 && candle.high >= maxOf(candle.open, candle.close) &&
                                candle.low <= minOf(candle.open, candle.close) && candle.volume >= 0
                        }
                    }.getOrNull()
                }.distinctBy { it.openTime }.sortedBy { it.openTime }.takeLast(MAX_CANDLES_PER_SYMBOL)
                if (values.isNotEmpty()) history[key] = values
            }
            MarketActivityCheckpoint(interval, selected, savedAt, history)
        }.getOrNull()
    }
}
