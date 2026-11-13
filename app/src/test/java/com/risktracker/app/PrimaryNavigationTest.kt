package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryNavigationTest {
    @Test fun primaryTabsRouteToTheFourCoreDestinations() {
        assertEquals(0, primaryDestinationForNavItem(0))  // Market overview
        assertEquals(21, primaryDestinationForNavItem(1)) // Daily trade ideas
        assertEquals(11, primaryDestinationForNavItem(2)) // Risk monitor
        assertEquals(1, primaryDestinationForNavItem(3))  // Trade history
    }

    @Test fun coreDestinationsAreNeverDuplicatedInSecondaryNavigation() {
        listOf(0, 1, 11, 21).forEach { destination ->
            org.junit.Assert.assertTrue(isPrimaryNavigationDestination(destination))
        }
        listOf(2, 5, 6, 7, 9, 13, 14, 16, 17, 18, 19, 23, 25, 26, 27, 28).forEach { destination ->
            org.junit.Assert.assertFalse(isPrimaryNavigationDestination(destination))
        }
    }
}
