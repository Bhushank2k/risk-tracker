package com.risktracker.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object AdvancedSMCScanScheduler {
    private const val UNIQUE_WORK = "advanced_smc_signal_scan"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AdvancedSMCScanWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
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
