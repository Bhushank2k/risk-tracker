package com.risktracker.app

import androidx.compose.runtime.Composable

/**
 * Unified scanner destination.
 *
 * All strategy engines now feed the existing Daily Trade Ideas scan and its shared
 * progress, cached results, ranking, filters and trade-plan cards. Keep this route as
 * a compatibility entry point for the Explore menu.
 */
@Composable
fun CombinedSMCIdeasScreen(onOpenTradeIdeas: () -> Unit = {}) {
    DailyTradeIdeasScreen()
}
