package com.risktracker.app

import android.content.Context

/**
 * First-run defaults selected in Quick Setup. Stored separately so applying the profile
 * never clears or rewrites journal, exchange-import, backup, or scanner-history data.
 */
internal data class QuickSetupProfile(
    val tradingStyle: String = "all",
    val markets: String = "top",
    val riskPercent: Double = 1.0,
    val confirmation: String = "balanced",
    val alertsEnabled: Boolean = true
)

internal object QuickSetupProfileStore {
    private const val PREFS = "quick_setup_profile_v1"
    private const val APPLIED = "initial_profile_applied"

    fun load(context: Context): QuickSetupProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return QuickSetupProfile(
            tradingStyle = p.getString("trading_style", "all") ?: "all",
            markets = p.getString("markets", "top") ?: "top",
            riskPercent = p.getFloat("risk_percent", 1.0f).toDouble().coerceIn(0.5, 2.0),
            confirmation = p.getString("confirmation", "balanced") ?: "balanced",
            alertsEnabled = p.getBoolean("alerts_enabled", true)
        )
    }

    fun save(context: Context, profile: QuickSetupProfile) {
        require(profile.tradingStyle in setOf("all", "scalp", "intraday", "swing"))
        require(profile.markets in setOf("top", "all"))
        require(profile.confirmation in setOf("strict", "balanced", "early"))
        require(profile.riskPercent.isFinite() && profile.riskPercent in 0.5..2.0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("trading_style", profile.tradingStyle)
            .putString("markets", profile.markets)
            .putFloat("risk_percent", profile.riskPercent.toFloat())
            .putString("confirmation", profile.confirmation)
            .putBoolean("alerts_enabled", profile.alertsEnabled)
            .apply()
    }

    /** Applies the selected first-run profile once; later launches preserve user changes. */
    fun applyInitial(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(APPLIED, false)) return
        val profile = load(context)
        prefs.edit().putBoolean(APPLIED, true).apply()
        apply(context, profile)
    }

    fun apply(context: Context, profile: QuickSetupProfile) {
        save(context, profile)
        val existingRisk = EntryRiskSettingsStore.load(context)
        EntryRiskSettingsStore.save(context, existingRisk.copy(riskPercent = profile.riskPercent))
        val appStore = Store(context)
        appStore.saveInput(appStore.loadInput().copy(riskPercent = profile.riskPercent))
        if (profile.alertsEnabled) {
            TradeIdeaNotifications.setReadyEnabled(context, true)
            TradeIdeaNotifications.setEntryEnabled(context, true)
            TradeIdeaNotifications.schedule(context)
        } else {
            TradeIdeaNotifications.setReadyEnabled(context, false)
            TradeIdeaNotifications.setEntryEnabled(context, false)
        }
        BinanceMarketScanner.initialize(context)
    }
}
