package com.risktracker.app

/**
 * Single source of truth for secondary destinations shown in Explore.
 *
 * Destination IDs intentionally match the existing MainActivity screen router.
 * Keep these IDs stable: persisted navigation, callbacks and screen ownership
 * are tied to them.
 */
internal fun exploreMenuGroups(): List<Pair<String, List<Pair<String, Int>>>> =
    listOf(
        "TRADING & TOOLS" to listOf(
            "Live positions & orders" to 16,
            "Position & leverage calculator" to 2
        ),
        "MARKET INTELLIGENCE" to listOf(
            "Unified SMC Signals" to 33,
            "Coin Explosion Radar" to 32
        ),
        "PERFORMANCE & REPORTS" to listOf(
            "P&L Calendar" to 23,
            "Crypto tax report" to 9
        ),
        "EXCHANGE & ACCOUNT" to listOf(
            "Binance Sync & imports" to 6,
            "Sync health" to 18,
            "Account activity & statements" to 17
        ),
        "APP" to listOf(
            "Settings" to 7,
            "About Risk Tracker" to 5
        )
    ).map { (group, destinations) ->
        group to destinations.filterNot { isPrimaryNavigationDestination(it.second) }
    }.filter { (_, destinations) -> destinations.isNotEmpty() }
