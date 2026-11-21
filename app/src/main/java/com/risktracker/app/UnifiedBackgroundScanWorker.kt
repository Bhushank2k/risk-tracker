package com.risktracker.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * One persistent background cycle for both market engines.
 *
 * Trade Ideas run first so their existing cache, health and alert processing is
 * preserved. The unified SMC pass then persists both Core and Advanced results.
 */
object UnifiedBackgroundScanScheduler {
    private const val UNIQUE_WORK = "unified_market_background_scan"
    private const val TRADE_IDEA_WORK = "trade_idea_market_monitor"
    private const val SMC_WORK = "smc_market_background_scan"

    fun schedule(context: Context) {
        val app = context.applicationContext
        val workManager = WorkManager.getInstance(app)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Retire the two former independent loops and their startup kicks.
        listOf(
            TRADE_IDEA_WORK,
            "${TRADE_IDEA_WORK}_startup",
            SMC_WORK,
            "${SMC_WORK}_startup"
        ).forEach(workManager::cancelUniqueWork)

        val periodic = PeriodicWorkRequestBuilder<UnifiedBackgroundScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )

        val now = System.currentTimeMillis()
        val tradeIdeasStale = TradeIdeaScanHealthStore.load(app).lastSuccessAt.let {
            it == 0L || now - it >= TimeUnit.MINUTES.toMillis(15)
        }
        val smcResultsStale = SMCScanStateStore.resultsUpdatedAt(app).let {
            it == 0L || now - it >= TimeUnit.MINUTES.toMillis(15)
        }
        if (tradeIdeasStale || smcResultsStale) {
            val startup = OneTimeWorkRequestBuilder<UnifiedBackgroundScanWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                "${UNIQUE_WORK}_startup",
                ExistingWorkPolicy.KEEP,
                startup
            )
        }
    }
}

class UnifiedBackgroundScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            // Reuse the existing Trade Ideas pipeline, including persisted cache,
            // scan-health reporting and optional notification/paper-plan updates.
            val tradeIdeasResult = runTradeIdeaMonitorScan(app)
            if (tradeIdeasResult !is Result.Success) return tradeIdeasResult

            BinanceMarketScanner.initialize(app)
            SMCScanStateStore.update(app, 0, 0, true, "Loading liquid USDT perpetual markets…")
            val smcResult = BinanceMarketScanner.scanUnifiedSmc(
                progress = { message ->
                    val old = SMCScanStateStore.status(app)
                    SMCScanStateStore.update(
                        app, old.optInt("scanned"), old.optInt("total"), true, message
                    )
                },
                onProgress = { scanned, total ->
                    val old = SMCScanStateStore.status(app)
                    SMCScanStateStore.update(
                        app, scanned, total, true, old.optString("message")
                    )
                }
            )

            // One SMC pass populates both signal stores.
            AdvancedSMCSignalStore.save(app, smcResult.advanced)
            SMCScanStateStore.saveResults(app, smcResult.core)
            val total = SMCScanStateStore.status(app).optInt("total")
            SMCScanStateStore.update(
                app,
                total,
                total,
                false,
                "Background unified scan complete · Trade Ideas + ${smcResult.core.size} Core + ${smcResult.advanced.size} Advanced candidates"
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = SMCScanStateStore.status(app)
            SMCScanStateStore.update(
                app,
                old.optInt("scanned"),
                old.optInt("total"),
                false,
                "Background scan failed: ${e.message ?: "network error"}"
            )
            TradeIdeaScanHealthStore.failed(app, e.message ?: "Unified background market scan failed")
            Result.retry()
        }
    }
}
