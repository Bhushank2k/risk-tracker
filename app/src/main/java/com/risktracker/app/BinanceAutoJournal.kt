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

data class BinanceJournalLoadResult(
    val fills: List<BinanceJournalFill>,
    val skippedRecords: Int,
    val unreadableStore: Boolean
)

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

/**
 * Pure merge helper so CSV/API reconciliation can be tested without Android storage.
 * Fuzzy matching is deliberately cross-source only and one-to-one: two equal fills from
 * the same source remain separate executions, while a CSV row can be upgraded by its API row.
 */
internal fun mergeBinanceJournalFills(
    existing: List<BinanceJournalFill>,
    incoming: List<BinanceJournalFill>
): Pair<List<BinanceJournalFill>, Int> {
    val all = existing.associateBy { it.id }.toMutableMap()
    val consumedForCrossSourceMatch = mutableSetOf<String>()
    var inserted = 0
    fun isCsv(fill: BinanceJournalFill) = fill.id.contains(":csv:")
    fun sameExecution(a: BinanceJournalFill, b: BinanceJournalFill): Boolean {
        if (a.market != b.market || a.symbol != b.symbol || a.side != b.side) return false
        if (a.orderId.isNotBlank() && b.orderId.isNotBlank() && a.orderId != b.orderId) return false
        return kotlin.math.abs(a.time - b.time) <= 1_000L &&
            kotlin.math.abs(a.quantity - b.quantity) <= maxOf(1e-10, kotlin.math.abs(a.quantity) * 1e-8) &&
            kotlin.math.abs(a.price - b.price) <= maxOf(1e-8, kotlin.math.abs(a.price) * 1e-8)
    }
    incoming.forEach { fill ->
        if (all.containsKey(fill.id)) {
            all[fill.id] = fill
            return@forEach
        }
        val equivalent = all.values.firstOrNull { candidate ->
            isCsv(candidate) != isCsv(fill) &&
                candidate.id !in consumedForCrossSourceMatch &&
                sameExecution(candidate, fill)
        }
        if (equivalent == null) {
            all[fill.id] = fill
            inserted++
        } else {
            consumedForCrossSourceMatch += equivalent.id
            if (isCsv(equivalent) && !isCsv(fill)) {
                // Prefer the exchange API record when the same execution was previously imported from CSV.
                all.remove(equivalent.id)
                all[fill.id] = fill
            }
        }
    }
    return all.values.sortedByDescending { it.time } to inserted
}

object BinanceAutoJournalStore {
    private const val PREFS = "binance_auto_journal_v1"
    private const val KEY = "fills"

    fun load(context: Context): List<BinanceJournalFill> = loadWithDiagnostics(context).fills

    /** Parses each saved execution independently so one damaged row cannot hide the entire history. */
    internal fun parseSavedFills(raw: String): BinanceJournalLoadResult {
        val rows = try { JSONArray(raw) } catch (_: Exception) {
            return BinanceJournalLoadResult(emptyList(), 1, true)
        }
        val fills = mutableListOf<BinanceJournalFill>()
        var skipped = 0
        for (i in 0 until rows.length()) {
            val parsed = runCatching {
                val row = rows.getJSONObject(i)
                val id = row.optString("id").trim()
                val symbol = row.optString("symbol").trim()
                val side = row.optString("side").trim().uppercase()
                val quantity = row.optDouble("quantity", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                val quoteAmount = row.optDouble("quoteAmount", Double.NaN)
                val fee = row.optDouble("fee", 0.0)
                val time = row.optLong("time", 0L)
                require(id.isNotBlank() && symbol.isNotBlank() && side in setOf("BUY", "SELL"))
                require(quantity.isFinite() && quantity > 0.0 && price.isFinite() && price > 0.0)
                require(quoteAmount.isFinite() && quoteAmount >= 0.0 && fee.isFinite() && time > 0L)
                BinanceJournalFill(
                    id = id, exchange = row.optString("exchange", "Binance"),
                    market = row.optString("market", "Spot"), symbol = symbol,
                    side = side, quantity = quantity, price = price, quoteAmount = quoteAmount,
                    fee = fee, feeAsset = row.optString("feeAsset"),
                    realizedPnl = if (row.isNull("realizedPnl")) null else row.optDouble("realizedPnl"),
                    time = time, orderId = row.optString("orderId")
                )
            }.getOrNull()
            if (parsed == null) skipped++ else fills += parsed
        }
        return BinanceJournalLoadResult(fills.distinctBy { it.id }.sortedByDescending { it.time }, skipped, false)
    }

    fun loadWithDiagnostics(context: Context): BinanceJournalLoadResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val result = parseSavedFills(raw)
        if (result.skippedRecords > 0 || result.unreadableStore) {
            // Keep the exact pre-recovery payload so skipped records can be recovered later.
            val recovery = context.getSharedPreferences("binance_auto_journal_recovery_v1", Context.MODE_PRIVATE)
            if (recovery.getString("fills_backup", null) == null) {
                recovery.edit().putString("fills_backup", raw).commit()
            }
        }
        return result
    }

    /** Merge CSV/API representations one-to-one while preserving identical partial executions. */
    fun merge(context: Context, incoming: List<BinanceJournalFill>): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val merged = mergeBinanceJournalFills(load(context), incoming)
        val all = merged.first
        val inserted = merged.second
        val array = JSONArray()
        all.sortedBy { it.time }.forEach { fill ->
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

    /** Clears saved execution history and sync cursors/status without touching API credentials. */
    fun clear(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Could not clear saved Binance trade history."
        }
        context.getSharedPreferences("binance_auto_journal_recovery_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
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

    /** Per-symbol historical cursor. Long.MAX_VALUE means that symbol's API backfill is complete. */
    fun backfillCursor(context: Context, market: String, symbol: String, initialStart: Long): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("backfill_${market.hashCode()}_$symbol", initialStart)

    fun saveBackfillCursor(context: Context, market: String, symbol: String, cursor: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("backfill_${market.hashCode()}_$symbol", cursor).apply()
    }

    fun backfillSymbolCursor(context: Context, market: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("backfill_symbol_cursor_${market.hashCode()}", 0)

    fun saveBackfillSymbolCursor(context: Context, market: String, cursor: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("backfill_symbol_cursor_${market.hashCode()}", cursor.coerceAtLeast(0)).apply()
    }

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
 * App-open and periodic sync refresh only the rolling seven-day execution window. Older history is
 * retained locally and backfilled through explicit CSV imports. Binance has no all-symbol user-trades
 * endpoint, so CSV imports remain necessary for symbols outside the discovered/imported universe.
 */
/** Automatic execution-history refresh is deliberately limited to a rolling seven-day window. */
const val BINANCE_RECENT_SYNC_DAYS = 7L

internal fun classifyBinanceSyncState(success: Boolean, failureCount: Int, discoverySucceeded: Int): String = when {
    success -> "Synced (last 7 days)"
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
        // Keep a rolling overlap to catch late updates and avoid gaps after the device is offline.
        val recentStart = end - BINANCE_RECENT_SYNC_DAYS * 24L * 60 * 60 * 1000
        val knownSpot = BinanceAutoJournalStore.knownSymbols(context, "Spot").toMutableSet()
        val knownFutures = BinanceAutoJournalStore.knownSymbols(context, "USDⓈ-M Futures").toMutableSet()
        val failures = mutableListOf<String>()
        var discoverySucceeded = 0

        runCatching { knownSpot += api.recentSpotSymbols() }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Spot symbol discovery: ${it.message ?: "request failed"}" }
        runCatching { api.liveTrades().filter { it.market == "USDⓈ-M Futures" }.forEach { knownFutures += it.symbol } }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Futures position discovery: ${it.message ?: "request failed"}" }
        runCatching { knownFutures += api.recentFuturesSymbols(end - 90L * 24 * 60 * 60 * 1000, end) }
            .onSuccess { discoverySucceeded++ }
            .onFailure { failures += "Futures symbol discovery: ${it.message ?: "request failed"}" }

        BinanceAutoJournalStore.rememberSymbols(context, "Spot", knownSpot)
        BinanceAutoJournalStore.rememberSymbols(context, "USDⓈ-M Futures", knownFutures)

        val spotBatch = selectBinanceSymbolBatch(
            knownSpot, BinanceAutoJournalStore.symbolCursor(context, "Spot")
        )
        val futuresBatch = selectBinanceSymbolBatch(
            knownFutures, BinanceAutoJournalStore.symbolCursor(context, "USDⓈ-M Futures")
        )
        BinanceAutoJournalStore.saveSymbolCursor(context, "Spot", spotBatch.second)
        BinanceAutoJournalStore.saveSymbolCursor(context, "USDⓈ-M Futures", futuresBatch.second)
        val coverageLimited = spotBatch.first.size < knownSpot.size || futuresBatch.first.size < knownFutures.size
        if (coverageLimited) {
            failures += "Known symbols exceed the 100-per-market sync batch; this run covered ${spotBatch.first.size}/${knownSpot.size} Spot and ${futuresBatch.first.size}/${knownFutures.size} Futures symbols. Remaining symbols rotate into later syncs."
        }

        val fetched = mutableListOf<BinanceJournalFill>()
        var symbolsAttempted = 0
        var symbolFailures = 0
        (spotBatch.first.map { "Spot" to it } +
            futuresBatch.first.map { "USDⓈ-M Futures" to it }).forEach { (market, symbol) ->
            symbolsAttempted++
            val result = runCatching {
                if (market == "Spot") api.spotExecutionHistory(symbol, recentStart, end)
                else api.futuresExecutionHistory(symbol, recentStart, end)
            }
            result.onSuccess { fetched += it }
                .onFailure { symbolFailures++; failures += "$market $symbol recent: ${it.message ?: "request failed"}" }
        }

        // Older execution history is supplied by explicit CSV backfill, not by automatic API sync.
        val inserted = BinanceAutoJournalStore.merge(context, fetched)
        val success = failures.isEmpty() && discoverySucceeded > 0
        val state = classifyBinanceSyncState(success, failures.size, discoverySucceeded)
        val detail = when (state) {
            "Synced (last 7 days)" -> "The rolling 7-day execution window was refreshed and merged into saved history. Older trades remain stored and can be added with Binance CSV imports. Binance has no all-symbol trade endpoint, so symbols outside the discovered/imported set may require CSV exports."
            "Partial sync" -> "Partial sync: ${failures.size} request(s) failed. First issue: ${failures.first()}. Review history/imports for gaps."
            else -> failures.firstOrNull()?.let { "Sync failed: $it. Check connectivity and read-only API permissions." }
                ?: "No discovery request succeeded. Check network access and read-only API permissions."
        }
        val priorSuccess = previous.lastSuccess
        BinanceAutoJournalStore.recordSyncStatus(context, BinanceJournalSyncStatus(
            lastAttempt = attemptedAt,
            lastSuccess = if (success) attemptedAt else priorSuccess,
            state = state,
            detail = detail,
            symbolsAttempted = symbolsAttempted,
            symbolsFailed = symbolFailures,
            fillsFetched = fetched.size,
            fillsInserted = inserted,
            windowStart = recentStart,
            windowEnd = end
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
            val request = PeriodicWorkRequestBuilder<BinanceJournalWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "binance_auto_trade_journal", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork("binance_auto_trade_journal")
    }
}
