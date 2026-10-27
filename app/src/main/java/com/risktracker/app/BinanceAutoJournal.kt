package com.risktracker.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One immutable exchange execution. Fills stay distinct; they are not misrepresented as closed positions. */
data class BinanceJournalFill(
    val id: String,
    val exchange: String,
    val market: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val quoteAmount: Double,
    val fee: Double,
    val feeAsset: String,
    val realizedPnl: Double?,
    val time: Long,
    val orderId: String
)

data class BinanceJournalSyncStatus(
    val lastAttempt: Long = 0L,
    val lastSuccess: Long = 0L,
    val state: String = "Never synced",
    val detail: String = "Connect Binance with read-only API credentials to start automatic sync.",
    val symbolsAttempted: Int = 0,
    val symbolsFailed: Int = 0,
    val fillsFetched: Int = 0,
    val fillsInserted: Int = 0,
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L
)

object BinanceAutoJournalStore {
    private const val PREFS = "binance_auto_journal_v1"
    private const val KEY = "fills"

    fun load(context: Context): List<BinanceJournalFill> = try {
        val rows = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            BinanceJournalFill(
                id = row.getString("id"), exchange = row.optString("exchange", "Binance"),
                market = row.optString("market", "Spot"), symbol = row.optString("symbol"),
                side = row.optString("side"), quantity = row.optDouble("quantity"),
                price = row.optDouble("price"), quoteAmount = row.optDouble("quoteAmount"),
                fee = row.optDouble("fee"), feeAsset = row.optString("feeAsset"),
                realizedPnl = if (row.isNull("realizedPnl")) null else row.optDouble("realizedPnl"),
                time = row.optLong("time"), orderId = row.optString("orderId")
            )
        }.distinctBy { it.id }.sortedByDescending { it.time }
    } catch (_: Exception) { emptyList() }

    /** Merge is idempotent: exchange + market + symbol + exchange trade ID is the primary key. */
    fun merge(context: Context, incoming: List<BinanceJournalFill>): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = load(context).associateBy { it.id }.toMutableMap()
        var inserted = 0
        fun sameExecution(a: BinanceJournalFill, b: BinanceJournalFill): Boolean {
            if (a.market != b.market || a.symbol != b.symbol || a.side != b.side) return false
            if (a.orderId.isNotBlank() && b.orderId.isNotBlank() && a.orderId != b.orderId) return false
            val timeTolerance = 1_000L
            return kotlin.math.abs(a.time - b.time) <= timeTolerance &&
                kotlin.math.abs(a.quantity - b.quantity) <= maxOf(1e-10, kotlin.math.abs(a.quantity) * 1e-8) &&
                kotlin.math.abs(a.price - b.price) <= maxOf(1e-8, kotlin.math.abs(a.price) * 1e-8)
        }
        incoming.forEach { fill ->
            val existingById = all[fill.id]
            if (existingById != null) {
                all[fill.id] = fill
                return@forEach
            }
            val equivalent = all.values.firstOrNull { sameExecution(it, fill) }
            if (equivalent == null) {
                all[fill.id] = fill
                inserted++
            } else if (equivalent.id.contains(":csv:") && !fill.id.contains(":csv:")) {
                // Prefer the exchange API record when the same execution was previously imported from CSV.
                all.remove(equivalent.id)
                all[fill.id] = fill
            }
        }
        val array = JSONArray()
        all.values.sortedBy { it.time }.forEach { fill ->
            array.put(JSONObject()
                .put("id", fill.id).put("exchange", fill.exchange).put("market", fill.market)
                .put("symbol", fill.symbol).put("side", fill.side).put("quantity", fill.quantity)
                .put("price", fill.price).put("quoteAmount", fill.quoteAmount).put("fee", fill.fee)
                .put("feeAsset", fill.feeAsset).put("realizedPnl", fill.realizedPnl ?: JSONObject.NULL)
                .put("time", fill.time).put("orderId", fill.orderId))
        }
        prefs.edit().putString(KEY, array.toString()).putLong("last_sync", System.currentTimeMillis()).apply()
        return inserted
    }

    fun knownSymbols(context: Context, market: String): Set<String> =
        load(context).filter { it.market == market }.map { it.symbol }.toSet()

    fun lastSync(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_sync", 0L)

    fun syncStatus(context: Context): BinanceJournalSyncStatus {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BinanceJournalSyncStatus(
            lastAttempt = p.getLong("sync_last_attempt", 0L),
            lastSuccess = p.getLong("sync_last_success", 0L),
            state = p.getString("sync_state", "Never synced") ?: "Never synced",
            detail = p.getString("sync_detail", "Connect Binance with read-only API credentials to start automatic sync.")
                ?: "Connect Binance with read-only API credentials to start automatic sync.",
            symbolsAttempted = p.getInt("sync_symbols_attempted", 0),
            symbolsFailed = p.getInt("sync_symbols_failed", 0),
            fillsFetched = p.getInt("sync_fills_fetched", 0),
            fillsInserted = p.getInt("sync_fills_inserted", 0),
            windowStart = p.getLong("sync_window_start", 0L),
            windowEnd = p.getLong("sync_window_end", 0L)
        )
    }

    fun recordSyncStatus(context: Context, status: BinanceJournalSyncStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("sync_last_attempt", status.lastAttempt)
            .putLong("sync_last_success", status.lastSuccess)
            .putString("sync_state", status.state)
            .putString("sync_detail", status.detail)
            .putInt("sync_symbols_attempted", status.symbolsAttempted)
            .putInt("sync_symbols_failed", status.symbolsFailed)
            .putInt("sync_fills_fetched", status.fillsFetched)
            .putInt("sync_fills_inserted", status.fillsInserted)
            .putLong("sync_window_start", status.windowStart)
            .putLong("sync_window_end", status.windowEnd)
            .apply()
    }
}

/**
 * App-open and periodic best-effort sync. It refreshes symbols known from an earlier import,
 * current Spot holdings and currently open USDⓈ-M positions. Use Historical import to seed older
 * or closed symbols; Binance doesn't provide a single all-symbol user-trades endpoint.
 */
object BinanceAutoJournalRunner {
    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        val attemptedAt = System.currentTimeMillis()
        val previous = BinanceAutoJournalStore.syncStatus(context)
        val credentials = BinanceCredentialStore(context).read()
        if (credentials == null) {
            BinanceAutoJournalStore.recordSyncStatus(context, previous.copy(
                lastAttempt = attemptedAt,
                state = "Not connected",
                detail = "Connect Binance with read-only API credentials. No exchange requests were made."
            ))
            return@withContext 0
        }

        val api = BinanceApi(credentials.first, credentials.second)
        val end = attemptedAt
        val start = end - 7L * 24 * 60 * 60 * 1000
        val knownSpot = BinanceAutoJournalStore.knownSymbols(context, "Spot").toMutableSet()
        val knownFutures = BinanceAutoJournalStore.knownSymbols(context, "USDⓈ-M Futures").toMutableSet()
        val failures = mutableListOf<String>()
        var discoverySucceeded = 0

        runCatching { api.recentSpotTransactions().forEach { knownSpot += it.symbol } }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Spot symbol discovery: ${it.message ?: "request failed"}" }
        runCatching { api.liveTrades().filter { it.market == "USDⓈ-M Futures" }.forEach { knownFutures += it.symbol } }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Futures position discovery: ${it.message ?: "request failed"}" }
        runCatching { knownFutures += api.recentFuturesSymbols(start, end) }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Futures symbol discovery: ${it.message ?: "request failed"}" }

        val fetched = mutableListOf<BinanceJournalFill>()
        var symbolsAttempted = 0
        (knownSpot.take(100).map { "Spot" to it } +
            knownFutures.take(100).map { "USDⓈ-M Futures" to it }).forEach { (market, symbol) ->
            symbolsAttempted++
            val result = runCatching {
                if (market == "Spot") api.spotExecutionHistory(symbol, start, end)
                else api.futuresExecutionHistory(symbol, start, end)
            }
            result.onSuccess { fetched += it }
                .onFailure { failures += "$market $symbol: ${it.message ?: "request failed"}" }
        }

        val inserted = BinanceAutoJournalStore.merge(context, fetched)
        val success = failures.isEmpty() && discoverySucceeded > 0
        val detail = when {
            success -> "Sync completed for the rolling 7-day window. Historical coverage before this window is not guaranteed."
            failures.isNotEmpty() -> "Partial sync: ${failures.size} request(s) failed. First issue: ${failures.first()}. Review history/imports for gaps."
            else -> "No discovery request succeeded. Check network access and read-only API permissions."
        }
        val priorSuccess = previous.lastSuccess
        BinanceAutoJournalStore.recordSyncStatus(context, BinanceJournalSyncStatus(
            lastAttempt = attemptedAt,
            lastSuccess = if (success) attemptedAt else priorSuccess,
            state = when {
                success -> "Up to date (7-day window)"
                failures.isNotEmpty() && (discoverySucceeded > 0 || fetched.isNotEmpty()) -> "Partial sync"
                else -> "Sync failed"
            },
            detail = detail,
            symbolsAttempted = symbolsAttempted,
            symbolsFailed = failures.count { it.contains(":") && (it.startsWith("Spot ") || it.startsWith("USDⓈ-M Futures ")) },
            fillsFetched = fetched.size,
            fillsInserted = inserted,
            windowStart = start,
            windowEnd = end
        ))
        inserted
    }
}

class BinanceJournalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BinanceCredentialStore(applicationContext).read() == null) Result.success()
        else { BinanceAutoJournalRunner.sync(applicationContext); Result.success() }
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BinanceJournalWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "binance_auto_trade_journal", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork("binance_auto_trade_journal")
    }
}
