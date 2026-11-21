package com.risktracker.app

import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Retries only transient transport failures. A DNS outage must never be converted into
 * an empty signal list or overwrite the last successful SMC snapshot.
 */
internal suspend fun <T> withMarketDataRetry(
    maxAttempts: Int = 3,
    operation: suspend () -> T
): T {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    var attempt = 0
    while (true) {
        attempt++
        try {
            return operation()
        } catch (error: Exception) {
            val transient = error.isTransientMarketDataFailure()
            if (!transient || attempt >= maxAttempts) {
                if (transient) throw error.asMarketDataFailure()
                throw error
            }
            // Bounded exponential backoff: 1s, then 2s by default.
            delay(1_000L shl (attempt - 1).coerceAtMost(4))
        }
    }
}

internal fun Throwable.isDnsResolutionFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is UnknownHostException }

private fun Throwable.isTransientMarketDataFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is UnknownHostException ||
            it is SocketTimeoutException ||
            it is ConnectException ||
            it is SocketException
    }

private fun Throwable.asMarketDataFailure(): IllegalStateException {
    val causes = generateSequence(this) { it.cause }.toList()
    val message = when {
        causes.any { it is UnknownHostException } ->
            "Binance DNS lookup failed for fapi.binance.com after 3 attempts. Check your internet connection or Private DNS, then tap Retry Scan. Saved signals are unchanged."
        causes.any { it is SocketTimeoutException } ->
            "Binance market-data request timed out after retries. Check your connection, then tap Retry Scan. Saved signals are unchanged."
        causes.any { it is ConnectException || it is SocketException } ->
            "Could not connect to Binance market data after retries. Check your network or VPN, then tap Retry Scan. Saved signals are unchanged."
        else ->
            "Binance market data is temporarily unavailable after retries. Please tap Retry Scan. Saved signals are unchanged."
    }
    return IllegalStateException(message, this)
}
