package com.risktracker.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters

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

internal class AdvancedSMCScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            BinanceMarketScanner.initialize(app)
            AdvancedSMCSignalStore.update(app, 0, 0, true, "Loading advanced SMC markets…")
            val signals = BinanceMarketScanner.scanAdvancedSmc(
                progress = { message ->
                    val old = AdvancedSMCSignalStore.status(app)
                    AdvancedSMCSignalStore.update(app, old.optInt("scanned"), old.optInt("total"), true, message)
                },
                onProgress = { scanned, total ->
                    val old = AdvancedSMCSignalStore.status(app)
                    AdvancedSMCSignalStore.update(app, scanned, total, true, old.optString("message"))
                }
            )
            AdvancedSMCSignalStore.save(app, signals)
            val total = AdvancedSMCSignalStore.status(app).optInt("total")
            AdvancedSMCSignalStore.update(app, total, total, false, "Background scan complete · ${signals.size} advanced SMC ideas")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = AdvancedSMCSignalStore.status(app)
            AdvancedSMCSignalStore.update(app, old.optInt("scanned"), old.optInt("total"), false,
                "Background scan failed: ${e.message ?: "network error"}")
            Result.retry()
        }
    }
}
