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

        // Refresh if either pipeline lacks a recent successful snapshot. WorkManager unique work
        // prevents duplicate startup requests while one is pending and recovers after cancellation.
        val now = System.currentTimeMillis()
        val staleAfterMs = TimeUnit.MINUTES.toMillis(15)
        val smcUpdatedAt = SMCScanStateStore.resultsUpdatedAt(app)
        val tradeIdeasUpdatedAt = TradeIdeaScanHealthStore.load(app).lastSuccessAt
        val smcIsFresh = smcUpdatedAt > 0L && now >= smcUpdatedAt && now - smcUpdatedAt < staleAfterMs
        val tradeIdeasAreFresh = tradeIdeasUpdatedAt > 0L && now >= tradeIdeasUpdatedAt &&
            now - tradeIdeasUpdatedAt < staleAfterMs
        if (!smcIsFresh || !tradeIdeasAreFresh) {
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

        // The two pipelines are independent: a Trade Ideas failure must not
        // prevent unified SMC from refreshing and persisting its own snapshot.
        val tradeIdeasSucceeded = try {
            runTradeIdeaMonitorScan(app) is Result.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TradeIdeaScanHealthStore.failed(app, AppErrorMessage.from(e, "Background market scan failed. It will retry automatically."))
            false
        }

        val smcSucceeded = try {
            BinanceMarketScanner.initialize(app)
            SMCScanStateStore.update(app, 0, 0, true, "Loading liquid USDT perpetual markets…")
            val smcResult = withMarketDataRetry {
                BinanceMarketScanner.scanUnifiedSmc(
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
            }

            // One SMC pass populates both signal stores.
            AdvancedSMCSignalStore.save(app, smcResult.advanced)
            SMCScanStateStore.saveResults(app, smcResult.core)
            val total = SMCScanStateStore.status(app).optInt("total")
            SMCScanStateStore.update(
                app,
                total,
                total,
                false,
                "Background unified SMC scan complete · ${smcResult.core.size} Core + ${smcResult.advanced.size} Advanced candidates"
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = SMCScanStateStore.status(app)
            SMCScanStateStore.update(
                app,
                old.optInt("scanned"),
                old.optInt("total"),
                false,
                if (e.isDnsResolutionFailure()) {
                    "Background SMC scan paused: Binance DNS lookup failed. WorkManager will retry; cached SMC signals are preserved."
                } else AppErrorMessage.from(e, "Background SMC scan failed. It will retry automatically.")
            )
            false
        }

        // Retry if either pipeline failed; each pipeline has already recorded its
        // own status and any successful pipeline has persisted its fresh results.
        return if (tradeIdeasSucceeded && smcSucceeded) Result.success() else Result.retry()
    }
}
