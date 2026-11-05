package com.risktracker.app

import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ExecutionException

/**
 * Reads a Binance response body. HttpURLConnection returns a null stream when a response
 * has no body (for example some 4xx/5xx replies), so a missing stream reads as empty
 * instead of throwing a message-less NullPointerException.
 */
internal fun readBinanceBody(stream: InputStream?): String =
    stream?.bufferedReader()?.use { it.readText() }.orEmpty()

/**
 * The error message Binance returned, or null when the body has none. Prefers the JSON
 * "msg" field; falls back to short plain-text bodies, but never to an HTML page.
 */
internal fun binanceMessage(body: String): String? {
    val fromJson = runCatching { JSONObject(body).optString("msg") }.getOrNull()
    if (!fromJson.isNullOrBlank()) return fromJson.trim()
    val text = body.trim()
    if (text.isEmpty() || text.startsWith("<")) return null
    return text.take(200)
}

/**
 * A readable reason for a failed sync. Unwraps executor wrappers and never returns a blank
 * or "null" string, so the UI does not have to guess a cause.
 */
internal fun describeBinanceError(error: Throwable): String {
    val root = (error as? ExecutionException)?.cause ?: error
    val detail = root.message?.takeIf { it.isNotBlank() }
    return detail ?: "${root::class.java.simpleName} with no details"
}

/** True when Binance rejected a signed request because the device clock is too far off. */
internal fun isBinanceTimestampError(message: String?): Boolean {
    if (message == null) return false
    return message.contains("recvWindow", ignoreCase = true) ||
        message.contains("ahead of the server", ignoreCase = true) ||
        message.contains("behind the server", ignoreCase = true)
}
