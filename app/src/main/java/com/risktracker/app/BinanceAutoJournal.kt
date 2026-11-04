package com.risktracker.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    fun knownSymbols(context: Context, market: String): Set<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("symbols_${market.hashCode()}", emptySet()) ?: emptySet()
        return saved + load(context).filter { it.market == market }.map { it.symbol }
    }

    fun rememberSymbols(context: Context, market: String, symbols: Set<String>) {
        if (symbols.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "symbols_${market.hashCode()}"
        val merged = (prefs.getStringSet(key, emptySet()) ?: emptySet()) + symbols
        prefs.edit().putStringSet(key, merged).apply()
    }

    fun symbolCursor(context: Context, market: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("symbol_cursor_${market.hashCode()}", 0)

    fun saveSymbolCursor(context: Context, market: String, cursor: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("symbol_cursor_${market.hashCode()}", cursor.coerceAtLeast(0)).apply()
    }

    fun lastSync(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_sync", 0L)

    /** Per-symbol checkpoint. Advance only after that symbol's history fetch succeeds. */
    fun symbolLastSync(context: Context, market: String, symbol: String): Long {
        val marketKey = if (market == "Spot") "spot" else "futures"
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("symbol_last_sync_${marketKey}_${symbol}", 0L)
    }

    fun recordSymbolSync(context: Context, market: String, symbol: String, syncedThrough: Long) {
        val marketKey = if (market == "Spot") "spot" else "futures"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("symbol_last_sync_${marketKey}_${symbol}", syncedThrough)
            .apply()
    }

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
internal fun classifyBinanceSyncState(success: Boolean, failureCount: Int, discoverySucceeded: Int): String = when {
    success -> "Up to date (7-day window)"
    failureCount > 0 && discoverySucceeded > 0 -> "Partial sync"
    else -> "Sync failed"
}

/** Selects a deterministic rotating batch so symbol sets larger than the API-work cap are not starved. */
internal fun selectBinanceSymbolBatch(symbols: Collection<String>, offset: Int, limit: Int = 100): Pair<List<String>, Int> {
    require(limit > 0) { "Batch limit must be positive." }
    val ordered = symbols.distinct().sorted()
    if (ordered.isEmpty()) return emptyList<String>() to 0
    if (ordered.size <= limit) return ordered to 0
    val start = offset.mod(ordered.size)
    // Stop at the end of the ordered list; wrapping within a batch repeats symbols
    // and can starve the final entries when the symbol count is not a multiple of limit.
    val count = minOf(limit, ordered.size - start)
    val batch = ordered.subList(start, start + count)
    return batch to ((start + count) % ordered.size)
}

/** Minimum gap between syncs started by opening a screen. Each sync makes several signed API calls. */
const val BINANCE_OPEN_SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000

/**
 * Whether opening a screen should start a sync, given when the last attempt ran.
 * Runs when there is no earlier attempt or the last one is old enough. A clock that
 * moved backwards also counts as due, so a wrong clock cannot block syncing for hours.
 */
fun shouldSyncOnOpen(lastAttemptMillis: Long, nowMillis: Long): Boolean {
    if (lastAttemptMillis <= 0L) return true
    val elapsed = nowMillis - lastAttemptMillis
    return elapsed < 0L || elapsed >= BINANCE_OPEN_SYNC_MIN_INTERVAL_MS
}

object BinanceAutoJournalRunner {
    private const val LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
    private const val CHECKPOINT_OVERLAP_MS = 60_000L
    private const val MAX_PARALLEL_SYMBOLS = 4

    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        BinanceRestGuard.configure(context)
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

        // Record immediately so app-open and screen-open triggers do not start duplicate work.
        BinanceAutoJournalStore.recordSyncStatus(context, previous.copy(
            lastAttempt = attemptedAt,
            state = "Syncing",
            detail = "Discovering Binance activity and refreshing known symbols."
        ))

        val api = BinanceApi(credentials.first, credentials.second)
        val fallbackStart = attemptedAt - LOOKBACK_MS
        val knownSpot = BinanceAutoJournalStore.knownSymbols(context, "Spot").toMutableSet()
        val knownFutures = BinanceAutoJournalStore.knownSymbols(context, "USDⓈ-M Futures").toMutableSet()
        val failures = mutableListOf<String>()
        var discoverySucceeded = 0

        // Independent discovery calls overlap; the shared REST guard still paces requests.
        val discovery = coroutineScope {
            val spot = async { runCatching { api.recentSpotSymbols() } }
            val positions = async { runCatching {
                api.liveTrades().filter { it.market == "USDⓈ-M Futures" }.map { it.symbol }.toSet()
            } }
            val futures = async { runCatching { api.recentFuturesSymbols(fallbackStart, attemptedAt) } }
            Triple(spot.await(), positions.await(), futures.await())
        }
        discovery.first.onSuccess { knownSpot += it; discoverySucceeded++ }
            .onFailure { failures += "Spot symbol discovery: ${it.message ?: "request failed"}" }
        discovery.second.onSuccess { knownFutures += it; discoverySucceeded++ }
            .onFailure { failures += "Futures position discovery: ${it.message ?: "request failed"}" }
        discovery.third.onSuccess { knownFutures += it; discoverySucceeded++ }
            .onFailure { failures += "Futures symbol discovery: ${it.message ?: "request failed"}" }

        BinanceAutoJournalStore.rememberSymbols(context, "Spot", knownSpot)
        BinanceAutoJournalStore.rememberSymbols(context, "USDⓈ-M Futures", knownFutures)

        val spotBatch = selectBinanceSymbolBatch(knownSpot, BinanceAutoJournalStore.symbolCursor(context, "Spot"))
        val futuresBatch = selectBinanceSymbolBatch(knownFutures, BinanceAutoJournalStore.symbolCursor(context, "USDⓈ-M Futures"))
        val coverageLimited = spotBatch.first.size < knownSpot.size || futuresBatch.first.size < knownFutures.size
        if (coverageLimited) {
            failures += "Known symbols exceed the 100-per-market sync batch; this run covered ${spotBatch.first.size}/${knownSpot.size} Spot and ${futuresBatch.first.size}/${knownFutures.size} Futures symbols. Remaining symbols rotate into later syncs."
        }

        data class SymbolResult(
            val market: String,
            val symbol: String,
            val fills: List<BinanceJournalFill>,
            val start: Long,
            val error: String? = null
        )

        val tasks = spotBatch.first.map { "Spot" to it } + futuresBatch.first.map { "USDⓈ-M Futures" to it }
        val limiter = Semaphore(MAX_PARALLEL_SYMBOLS)
        val results = coroutineScope {
            tasks.map { (market, symbol) ->
                async {
                    limiter.withPermit {
                        val lastSuccess = BinanceAutoJournalStore.symbolLastSync(context, market, symbol)
                        // One-minute overlap catches delayed records and API boundary effects.
                        val start = if (lastSuccess > 0L)
                            maxOf(fallbackStart, lastSuccess - CHECKPOINT_OVERLAP_MS)
                        else fallbackStart
                        try {
                            val rows = if (market == "Spot")
                                api.spotExecutionHistory(symbol, start, attemptedAt)
                            else api.futuresExecutionHistory(symbol, start, attemptedAt)
                            BinanceAutoJournalStore.recordSymbolSync(context, market, symbol, attemptedAt)
                            SymbolResult(market, symbol, rows, start)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            SymbolResult(market, symbol, emptyList(), start, error.message ?: "request failed")
                        }
                    }
                }
            }.awaitAll()
        }

        // Failed symbols retain their previous checkpoint and are retried on later passes.
        BinanceAutoJournalStore.saveSymbolCursor(context, "Spot", spotBatch.second)
        BinanceAutoJournalStore.saveSymbolCursor(context, "USDⓈ-M Futures", futuresBatch.second)
        results.filter { it.error != null }.forEach {
            failures += "${it.market} ${it.symbol}: ${it.error}"
        }

        val fetched = results.flatMap { it.fills }
        val inserted = BinanceAutoJournalStore.merge(context, fetched)
        val symbolFailures = results.count { it.error != null }
        val success = failures.isEmpty() && discoverySucceeded > 0
        val state = classifyBinanceSyncState(success, failures.size, discoverySucceeded)
        val detail = when (state) {
            "Up to date (7-day window)" -> "Incremental sync completed. Known symbols were refreshed from their last successful checkpoint with a one-minute overlap; first-time symbols retain the rolling 7-day window. Older closed Spot pairs may require Historical import."
            "Partial sync" -> "Partial sync: ${failures.size} request(s) failed. First issue: ${failures.first()}. Successful symbols were checkpointed; failed symbols will retry from their previous checkpoint."
            else -> failures.firstOrNull()?.let { "Sync failed: $it. Check connectivity and read-only API permissions." }
                ?: "No discovery request succeeded. Check network access and read-only API permissions."
        }
        val earliestStart = results.minOfOrNull { it.start } ?: fallbackStart
        BinanceAutoJournalStore.recordSyncStatus(context, BinanceJournalSyncStatus(
            lastAttempt = attemptedAt,
            lastSuccess = if (success) attemptedAt else previous.lastSuccess,
            state = state,
            detail = detail,
            symbolsAttempted = results.size,
            symbolsFailed = symbolFailures,
            fillsFetched = fetched.size,
            fillsInserted = inserted,
            windowStart = earliestStart,
            windowEnd = attemptedAt
        ))
        inserted
    }
}

class BinanceJournalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BinanceCredentialStore(applicationContext).read() == null) Result.success()
        else {
            BinanceAutoJournalRunner.sync(applicationContext)
            val state = BinanceAutoJournalStore.syncStatus(applicationContext).state
            if (state == "Sync failed" || state == "Partial sync") Result.retry() else Result.success()
        }
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
