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
}
