package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional, on-demand AI explanations. The OpenAI key stays on the backend. */
internal object TradeIdeaAiSettings {
    private const val PREFS = "trade_idea_ai_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_ACCESS_TOKEN = "access_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun endpoint(context: Context): String = prefs(context).getString(KEY_ENDPOINT, "").orEmpty()
    fun accessToken(context: Context): String = prefs(context).getString(KEY_ACCESS_TOKEN, "").orEmpty()

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun saveConnection(context: Context, endpoint: String, accessToken: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint).putString(KEY_ACCESS_TOKEN, accessToken).apply()
    }
}

internal object TradeIdeaAiClient {
    fun analyze(context: Context, endpoint: String, accessToken: String, idea: TradeIdea): String {
        require(TradeIdeaAiSettings.isEnabled(context)) { "Enable optional AI explanations first." }
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Configure an HTTPS AI backend URL first." }
        require(accessToken.isNotBlank()) { "Enter the backend access token first." }

        val reasons = JSONArray().apply { idea.reasons.forEach { put(it.take(240)) } }
        val payload = JSONObject()
            .put("symbol", idea.symbol).put("side", idea.side).put("score", idea.score)
            .put("setup", idea.setup).put("horizon", idea.horizon).put("lastPrice", idea.last)
            .put("entry", idea.entry).put("stop", idea.stop).put("target1", idea.target1).put("target2", idea.target2)
            .put("entryTriggered", idea.entryTriggered).put("rsi15m", idea.rsi).put("volumeRatio", idea.volumeRatio)
            .put("bias4h", idea.bias4h).put("bias1h", idea.bias1h).put("bias5m", idea.bias5m)
            .put("spreadBps", idea.spreadBps ?: JSONObject.NULL).put("bookImbalance", idea.bookImbalance ?: JSONObject.NULL)
            .put("fundingRate", idea.fundingRate ?: JSONObject.NULL).put("openInterestChangePct", idea.oiChangePct ?: JSONObject.NULL)
            .put("warning", idea.warning.take(500)).put("reasons", reasons)
            .put("historicalReplayTrades", idea.backtest.trades).put("historicalReplayWins", idea.backtest.wins)
            .put("historicalReplayAverageR", idea.backtest.averageR)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(responseText).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "AI backend returned HTTP $code." })
            }
            val analysis = JSONObject(responseText).optString("analysis").trim()
            if (analysis.isBlank()) throw IllegalStateException("AI backend returned an empty explanation.")
            analysis.take(3_500)
        } finally {
            connection.disconnect()
        }
    }
}
