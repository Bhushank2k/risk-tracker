package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryNavigationTest {
    @Test fun primaryTabsRouteToMarketIdeasHistoryAndExplore() {
        assertEquals(0, primaryDestinationForNavItem(0))  // Market overview
        assertEquals(21, primaryDestinationForNavItem(1)) // Daily trade ideas
        assertEquals(1, primaryDestinationForNavItem(2))  // Trade history
        assertEquals(29, primaryDestinationForNavItem(3)) // Explore
    }

    @Test fun removedRiskMonitorIsNotAPrimaryNavigationDestination() {
        org.junit.Assert.assertFalse(isPrimaryNavigationDestination(11))
    }

    @Test fun exploreIsASeparateBottomNavigationDestination() {
        assertEquals(29, primaryDestinationForNavItem(3))
        org.junit.Assert.assertFalse(isPrimaryNavigationDestination(29))
    }

    @Test fun secondaryDestinationsAreNeverDuplicatedInPrimaryNavigation() {
        listOf(0, 1, 21).forEach { destination ->
            org.junit.Assert.assertTrue(isPrimaryNavigationDestination(destination))
        }
        listOf(2, 5, 6, 7, 9, 11, 13, 14, 16, 17, 18, 19, 23, 25, 26, 27, 28).forEach { destination ->
            org.junit.Assert.assertFalse(isPrimaryNavigationDestination(destination))
        }
    }
    @Test fun exploreMenuGroupsHaveUniqueStableDestinations() {
        val groups = exploreMenuGroups()
        val destinations = groups.flatMap { it.second }

        assertEquals(destinations.size, destinations.map { it.second }.distinct().size)
        assertEquals(
            listOf("TRADING & TOOLS", "MARKET INTELLIGENCE", "PERFORMANCE & REPORTS", "EXCHANGE & ACCOUNT", "APP"),
            groups.map { it.first }
        )
        assertEquals("Coin Explosion Radar" to 32, groups.first { it.first == "MARKET INTELLIGENCE" }.second.single())
    }

    @Test fun exploreDoesNotDuplicatePrimaryNavigationRoutes() {
        val routes = exploreMenuGroups().flatMap { it.second }.map { it.second }
        listOf(0, 1, 21, 29).forEach { primaryRoute ->
            org.junit.Assert.assertFalse(primaryRoute in routes)
        }
    }

}
