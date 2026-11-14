package com.risktracker.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shared status/results bridge between WorkManager and the SMC screen. */
object SMCScanStateStore {
    private const val PREFS = "smc_background_scan_v1"

    fun update(context: Context, scanned: Int, total: Int, running: Boolean, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("scanned", scanned).putInt("total", total)
            .putBoolean("running", running).putString("message", message)
            .putLong("updatedAt", System.currentTimeMillis()).apply()
    }

    fun status(context: Context): JSONObject {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject()
            .put("scanned", p.getInt("scanned", 0))
            .put("total", p.getInt("total", 0))
            .put("running", p.getBoolean("running", false))
            .put("message", p.getString("message", "Background scan is scheduled") ?: "")
            .put("updatedAt", p.getLong("updatedAt", 0L))
    }

    fun saveResults(context: Context, setups: List<SMCTradeSetup>) {
        val rows = JSONArray()
        setups.forEach { s ->
            rows.put(JSONObject()
                .put("id", s.id).put("symbol", s.symbol).put("side", s.side)
                .put("horizon", s.horizon).put("score", s.score).put("status", s.status)
                .put("entryLow", s.entryLow).put("entryHigh", s.entryHigh)
                .put("stop", s.stop).put("target", s.target).put("rewardRisk", s.rewardRisk)
                .put("structure", s.structure).put("sweep", s.sweep)
                .put("displacement", s.displacement).put("zoneDescription", s.zoneDescription)
                .put("location", s.location))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("setups", rows.toString())
            .putLong("resultsAt", System.currentTimeMillis()).apply()
    }

    fun loadResults(context: Context): List<SMCTradeSetup> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("setups", "[]") ?: "[]"
        val rows = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val s = rows.getJSONObject(i)
                SMCTradeSetup(
                    id = s.getString("id"), symbol = s.getString("symbol"),
                    side = s.getString("side"), horizon = s.getString("horizon"),
                    score = s.getInt("score"), status = s.getString("status"),
                    entryLow = s.getDouble("entryLow"), entryHigh = s.getDouble("entryHigh"),
                    stop = s.getDouble("stop"), target = s.getDouble("target"),
                    rewardRisk = s.getDouble("rewardRisk"), structure = s.getString("structure"),
                    sweep = s.getString("sweep"), displacement = s.getString("displacement"),
                    zoneDescription = s.getString("zoneDescription"), location = s.getString("location")
                )
            }.getOrNull()
        }
    }

    fun resultsUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("resultsAt", 0L)
}

object SMCBackgroundScanScheduler {
    private const val UNIQUE_WORK = "smc_market_background_scan"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SMCBackgroundScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}

class SMCBackgroundScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            BinanceMarketScanner.initialize(app)
            SMCScanStateStore.update(app, 0, 0, true, "Loading liquid USDT perpetual markets…")
            val setups = BinanceMarketScanner.scanSmc(
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
            SMCScanStateStore.saveResults(app, setups)
            SMCScanStateStore.update(
                app,
                SMCScanStateStore.status(app).optInt("total"),
                SMCScanStateStore.status(app).optInt("total"),
                false,
                "Background scan complete · ${setups.size} qualified setups"
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = SMCScanStateStore.status(app)
            SMCScanStateStore.update(
                app, old.optInt("scanned"), old.optInt("total"), false,
                "Background scan failed: ${e.message ?: "network error"}"
            )
            Result.retry()
        }
    }
}
