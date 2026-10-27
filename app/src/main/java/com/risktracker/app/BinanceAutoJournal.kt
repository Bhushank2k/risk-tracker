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
}

/**
 * App-open and periodic best-effort sync. It refreshes symbols known from an earlier import,
 * current Spot holdings and currently open USDⓈ-M positions. Use Historical import to seed older
 * or closed symbols; Binance doesn't provide a single all-symbol user-trades endpoint.
 */
object BinanceAutoJournalRunner {
    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        val credentials = BinanceCredentialStore(context).read() ?: return@withContext 0
        val api = BinanceApi(credentials.first, credentials.second)
        val end = System.currentTimeMillis()
        val start = end - 7L * 24 * 60 * 60 * 1000
        val knownSpot = BinanceAutoJournalStore.knownSymbols(context, "Spot").toMutableSet()
        val knownFutures = BinanceAutoJournalStore.knownSymbols(context, "USDⓈ-M Futures").toMutableSet()

        runCatching { api.recentSpotTransactions().forEach { knownSpot += it.symbol } }
        runCatching { api.liveTrades().filter { it.market == "USDⓈ-M Futures" }.forEach { knownFutures += it.symbol } }
        runCatching { knownFutures += api.recentFuturesSymbols(start, end) }

        val fetched = mutableListOf<BinanceJournalFill>()
        knownSpot.take(100).forEach { symbol ->
            runCatching { fetched += api.spotExecutionHistory(symbol, start, end) }
        }
        knownFutures.take(100).forEach { symbol ->
            runCatching { fetched += api.futuresExecutionHistory(symbol, start, end) }
        }
        BinanceAutoJournalStore.merge(context, fetched)
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
