package com.risktracker.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.os.SystemClock
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.Locale

internal object BinanceFuturesArchiveScheduler {
    private const val UNIQUE_WORK = "binance_futures_five_year_archive_backfill"

    fun start(context: Context) {
        enqueue(context, 0L)
    }

    internal fun enqueue(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<BinanceFuturesArchiveWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

/**
 * Performs at most one archive API operation per run. It persists each returned download ID,
 * then schedules the next small step; cooldown responses suspend the queue until Retry-After.
 */
internal class BinanceFuturesArchiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val progress = FuturesTradeArchiveProgress(applicationContext)
        val windows = progress.windows()
        val credentials = BinanceCredentialStore(applicationContext).read()
        if (credentials == null) {
            progress.setAutomaticEnabled(false)
            progress.saveStatus("Automatic import paused: connect Binance with read-only API credentials.")
            return@withContext Result.failure()
        }

        val now = System.currentTimeMillis()
        val cooldown = progress.cooldownUntilMillis()
        if (cooldown > now) {
            progress.saveStatus("Binance cooldown active until ${formatBinanceTimestampIST(cooldown)}. Automatic import is paused.")
            BinanceFuturesArchiveScheduler.enqueue(applicationContext, cooldown - now + 1_000L)
            return@withContext Result.success()
        }
        progress.clearExpiredCooldown(now)

        if (windows.all { progress.imported(it) }) {
            progress.setAutomaticEnabled(false)
            progress.saveStatus("Automatic five-year Futures history import is complete.")
            return@withContext Result.success()
        }

        val api = BinanceFuturesTradeArchiveApi(credentials.first, credentials.second)
        try {
            val pending = windows.firstOrNull { progress.id(it) != null && !progress.imported(it) }
            if (pending != null) {
                val id = progress.id(pending)!!
                val state = api.status(id)
                val url = sequenceOf("downloadUrl", "url", "downloadLink")
                    .map { state.optString(it) }.firstOrNull { it.isNotBlank() }
                val label = state.optString("status", state.optString("state")).lowercase(Locale.ROOT)
                if (url.isNullOrBlank()) {
                    if (label.contains("fail") || label.contains("expired")) {
                        progress.removeId(pending)
                        progress.saveStatus("Binance archive ${pending.key} expired or failed. It will be requested again automatically.")
                    } else {
                        progress.saveStatus("Archive ${pending.key} is ${label.ifBlank { "processing" }}. Will check again automatically.")
                    }
                } else {
                    val fills = api.downloadCsv(url)
                    val added = BinanceAutoJournalStore.merge(applicationContext, fills)
                    progress.markImported(pending)
                    progress.saveStatus("Imported archive ${pending.key}: ${added} new Futures trades. Continuing automatically.")
                }
            } else {
                val next = windows.firstOrNull { !progress.imported(it) && progress.id(it) == null }
                if (next != null) {
                    val id = api.request(next)
                    progress.saveId(next, id)
                    progress.saveStatus("Binance accepted archive period ${next.key}. Saved request ID; checking it automatically.")
                }
            }
        } catch (blocked: BinanceArchiveIpBanException) {
            progress.saveCooldownUntilMillis(blocked.untilMillis)
            progress.saveStatus("Binance temporarily limited archive requests. Paused until ${formatBinanceTimestampIST(blocked.untilMillis)}; will resume automatically.")
            BinanceFuturesArchiveScheduler.enqueue(applicationContext, blocked.untilMillis - System.currentTimeMillis() + 1_000L)
            return@withContext Result.success()
        } catch (error: Exception) {
            progress.saveStatus(AppErrorMessage.from(error, "Automatic archive import paused after an error. You can resume it from the import screen."))
            return@withContext Result.retry()
        }

        if (windows.any { !progress.imported(it) }) {
            BinanceFuturesArchiveScheduler.enqueue(applicationContext, TimeUnit.MINUTES.toMillis(1))
        }
        Result.success()
    }
}
