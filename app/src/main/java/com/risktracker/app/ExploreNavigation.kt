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
            "Best Opportunities" to 34,
            "Unified SMC Signals" to 33,
            "Coin Explosion Radar" to 32,
            "Market Activity Lab" to 35,
            "Stored Signal Replay" to 39
        ),
        "BUSINESS & TAX" to listOf(
            "India Business Tax Center" to 38,
            "Crypto tax report" to 9
        ),
        "PERFORMANCE & REPORTS" to listOf(
            "Automatic daily target & risk" to 37,
            "Trading Intelligence" to 36,
            "P&L Calendar" to 23
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
