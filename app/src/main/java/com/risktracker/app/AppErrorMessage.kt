package com.risktracker.app

import android.util.Log

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Keeps implementation details out of user-facing UI while retaining the original
 * exception for Logcat/debug reporting at the call site.
 */
internal object AppErrorMessage {
    fun from(error: Throwable, fallback: String): String {
        Log.w("RiskTracker", fallback, error)
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val types = chain.map { it.javaClass.simpleName.lowercase() }

        return when {
            "banned until" in text || "http 418" in text ->
                "Binance has temporarily blocked requests. Please wait before retrying."
            "429" in text || "too many requests" in text || "rate limit" in text ->
                "Too many requests. Please wait a moment and try again."
            chain.any { it is UnknownHostException } ->
                "No internet connection or DNS unavailable. Check your connection and retry."
            chain.any { it is SocketTimeoutException || it is TimeoutException } ->
                "The request timed out. Please try again."
            "http 401" in text || "http 403" in text || "unauthorized" in text || "api-key" in text ->
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
