package com.risktracker.app

import java.util.concurrent.TimeUnit

/** Process-wide Binance REST circuit breaker and request pacer. */
internal object BinanceRestGuard {
    private const val MIN_REQUEST_GAP_MS = 250L
    @Volatile private var nextAllowedAt = 0L
    @Volatile private var blockedUntil = 0L

    @Synchronized
    fun beforeRequest(now: Long = System.currentTimeMillis()) {
        if (now < blockedUntil) {
            throw IllegalStateException("Binance REST is paused after a rate-limit ban until $blockedUntil. Live WebSocket prices can continue.")
        }
        val wait = (nextAllowedAt - now).coerceAtLeast(0L)
        if (wait > 0L) {
            try { TimeUnit.MILLISECONDS.sleep(wait) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Binance request interrupted while rate limiting.", e)
            }
        }
        nextAllowedAt = System.currentTimeMillis() + MIN_REQUEST_GAP_MS
    }

    @Synchronized
    fun recordResponse(status: Int, body: String, retryAfterSeconds: String? = null, now: Long = System.currentTimeMillis()) {
        if (status == 418 || status == 429) {
            val expiry = Regex("banned until\\s+(\\d+)", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val retryMs = retryAfterSeconds?.toLongOrNull()?.times(1_000L)
            val fallback = if (status == 418) 3_600_000L else 60_000L
            blockedUntil = maxOf(blockedUntil, expiry ?: (now + (retryMs ?: fallback)))
        }
    }

    fun blockedUntilMillis(): Long = blockedUntil

    internal fun resetForTests() {
        blockedUntil = 0L
        nextAllowedAt = 0L
    }
}
