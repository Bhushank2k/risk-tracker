package com.risktracker.app

import android.content.Context

/** Persisted health for foreground and WorkManager market scans. */
internal data class TradeIdeaScanHealth(
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastScalpSuccessAt: Long,
    val lastError: String?
)

internal object TradeIdeaScanHealthStore {
    private const val PREFS = "trade_idea_scan_health_v1"

    fun load(context: Context): TradeIdeaScanHealth {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TradeIdeaScanHealth(
            p.getLong("last_attempt", 0L),
            p.getLong("last_success", 0L),
            p.getLong("scalp_success", 0L),
            p.getString("last_error", null)
        )
    }

    fun started(context: Context, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_attempt", at).apply()
    }

    fun succeeded(context: Context, scalp: Boolean, at: Long = System.currentTimeMillis(), warning: String? = null) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_success", at)
            .putString("last_error", warning)
        if (scalp) edit.putLong("scalp_success", at)
        edit.apply()
    }

    fun failed(context: Context, message: String, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_attempt", at)
            .putString("last_error", message.take(240)).apply()
    }
}
