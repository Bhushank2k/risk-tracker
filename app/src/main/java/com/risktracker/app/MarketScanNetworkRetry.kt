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
    require(maxAttempts >= 1)
    var attempt = 0
    while (true) {
        attempt++
        try {
            return operation()
        } catch (error: Exception) {
            if (!error.isTransientMarketDataFailure() || attempt >= maxAttempts) {
                if (error.isDnsResolutionFailure()) {
                    throw IllegalStateException(
                        "Binance market data is temporarily unreachable (DNS lookup failed for fapi.binance.com). " +
                            "Check your internet connection or DNS, then retry. Your last successful SMC signals are preserved.",
                        error
                    )
                }
                throw error
            }
            delay(1_000L * attempt)
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
