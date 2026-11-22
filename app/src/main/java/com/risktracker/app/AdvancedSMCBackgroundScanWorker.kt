package com.risktracker.app

import android.content.Context
import androidx.work.WorkManager

internal object AdvancedSMCScanScheduler {
    private const val UNIQUE_WORK = "advanced_smc_signal_scan"

    fun cancelLegacyWork(context: Context) {
        // The unified SMC worker now saves Core and Advanced results in one pass.
        // cancelUniqueWork also cancels periodic work registered under this name.
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UNIQUE_WORK)
        workManager.cancelUniqueWork("${UNIQUE_WORK}_startup")
    }
}
