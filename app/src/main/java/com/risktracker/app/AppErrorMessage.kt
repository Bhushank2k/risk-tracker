package com.risktracker.app

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Keeps implementation details and raw server payloads out of user-facing UI.
 */
internal object AppErrorMessage {
    fun from(error: Throwable, fallback: String): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val types = chain.map { it.javaClass.simpleName.lowercase() }

        return when {
            "banned until" in text || Regex("""\b418\b""").containsMatchIn(text) ->
                "Binance has temporarily blocked requests. Please wait before retrying."
            Regex("""\b429\b""").containsMatchIn(text) || "too many requests" in text || "rate limit" in text ->
                "Too many requests. Please wait a moment and try again."
            chain.any { it is UnknownHostException } ->
                "No internet connection or DNS unavailable. Check your connection and retry."
            chain.any { it is SocketTimeoutException || it is TimeoutException } ->
                "The request timed out. Please try again."
            Regex("""\b(401|403)\b""").containsMatchIn(text) || "unauthorized" in text || "api-key" in text ->
                "Access was denied. Check your read-only API credentials and permissions."
            Regex("""http 5\d\d""").containsMatchIn(text) ||
                "server error" in text || "service unavailable" in text ->
                "The service is temporarily unavailable. Please try again shortly."
            chain.any { it is IOException } || types.any { "connect" in it } ->
                "Connection problem. Check your internet and try again."
            else -> fallback
        }
    }
}
