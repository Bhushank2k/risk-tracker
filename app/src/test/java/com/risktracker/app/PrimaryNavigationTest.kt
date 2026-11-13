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
}
