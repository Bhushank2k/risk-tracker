package com.risktracker.app

import org.junit.Assert.assertFalse
import org.junit.Test

class SpotEstimateToggleTest {
    @Test fun spotEstimateIsOffByDefault() {
        assertFalse(SPOT_ESTIMATE_DEFAULT_ENABLED)
    }
}
