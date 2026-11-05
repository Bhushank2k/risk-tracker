package com.risktracker.app

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

data class BinanceStatement(
    val id: String,
    val time: Long,
    val category: String,
    val asset: String,
    val amount: Double,
    val status: String,
    val reference: String,
    val details: String
)

data class BinanceStatementFetchResult(
    val records: List<BinanceStatement>,
    val failedCategories: List<String>,
    val attemptedCategories: List<String>
)

data class BinanceStatementSyncResult(
    val newRecords: Int,
    val totalRecords: Int,
    val failedCategories: List<String>,
    val attemptedCategories: List<String>
)

/**
 * Read-only account statement importer. Binance has no single endpoint for every statement type;
 * each supported endpoint is best-effort and Binance retention limits still apply.
 */
internal fun parseBinanceStatements(raw: String?): List<BinanceStatement> {
    val rows = JSONArray(raw ?: "[]")
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        BinanceStatement(row.getString("id"), row.getLong("time"), row.getString("category"),
            row.optString("asset"), row.optDouble("amount"), row.optString("status"),
            row.optString("reference"), row.optString("details"))
    }.sortedByDescending { it.time }
}

object BinanceStatementStore {
    private const val PREFS = "binance_account_statements_v1"
    fun load(context: Context): List<BinanceStatement> = runCatching {
        parseBinanceStatements(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rows", "[]"))
    }.getOrDefault(emptyList())

    private fun statementKey(r: BinanceStatement): String = listOf(r.category, r.time, r.asset, r.amount).joinToString("|")

    @Synchronized
    fun saveMerge(context: Context, incoming: List<BinanceStatement>): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = parseBinanceStatements(prefs.getString("rows", "[]"))
        // A record is identified by time, category, asset and amount, not by txId or status: a
        // withdrawal gets its txId only on completion, and a deposit moves from pending to
        // completed. Keep the newest version of each record, which also collapses duplicates
        // stored by earlier syncs.
        val oldKeys = old.map { statementKey(it) }.toHashSet()
        val fresh = incoming.map { statementKey(it) }.filter { it !in oldKeys }.toHashSet().size
        val byKey = LinkedHashMap<String, BinanceStatement>()
        old.forEach { byKey[statementKey(it)] = it }
        incoming.forEach { byKey[statementKey(it)] = it }
        val all = byKey.values.sortedBy { it.time }
        val a = JSONArray()
        all.forEach { r -> a.put(JSONObject().put("id", r.id).put("time", r.time)
            .put("category", r.category).put("asset", r.asset).put("amount", r.amount)
            .put("status", r.status).put("reference", r.reference).put("details", r.details)) }
        prefs.edit().putString("rows", a.toString())
            .putLong("last_sync", System.currentTimeMillis()).apply()
        return fresh.size to all.size
    }

    fun lastSync(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_sync", 0L)

    fun csv(context: Context): String = buildString {
        appendLine("Timestamp UTC,Category,Asset,Amount,Status,Reference,Details")
        load(context).sortedBy { it.time }.forEach { r ->
            appendLine(listOf(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(r.time)),
                r.category, r.asset, r.amount.toString(), r.status, r.reference, r.details)
                .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
        }
    }
}

class BinanceStatementApi(private val apiKey: String, private val secret: String) {
    private fun signedGet(base: String, path: String, params: List<Pair<String, String>>): JSONArray {
        // Every Binance REST call goes through the shared circuit breaker and pacer,
        // including statement history, so a ban cannot be extended by this path.
        BinanceRestGuard.beforeRequest()
        val all = params + ("timestamp" to System.currentTimeMillis().toString()) + ("recvWindow" to "10000")
        val query = all.joinToString("&") { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(query.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val conn = URL("$base$path?$query&signature=$sig").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("X-MBX-APIKEY", apiKey)
        try {
            val status = conn.responseCode
            val body = readBinanceBody(if (status in 200..299) conn.inputStream else conn.errorStream)
            BinanceRestGuard.recordResponse(status, body, conn.getHeaderField("Retry-After"))
            if (status !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("msg") }.getOrDefault("HTTP $status")
                throw IllegalStateException("$path: Binance API error ($status): $msg")
            }
            return if (body.trimStart().startsWith("[")) JSONArray(body) else {
                val obj = JSONObject(body)
                obj.optJSONArray("rows") ?: obj.optJSONArray("data")
                    ?: throw IllegalStateException("$path returned an unsupported statement response.")
            }
        } finally { conn.disconnect() }
    }

    private fun bestEffort(rows: MutableList<BinanceStatement>, failedCategories: MutableList<String>, category: String, base: String, path: String,
                           params: List<Pair<String, String>>, mapper: (JSONObject) -> BinanceStatement?) {
        try {
            // A bounded overlap is intentional: repeat syncs safely catch late-arriving records.
            val page = signedGet(base, path, params)
            for (i in 0 until page.length()) mapper(page.getJSONObject(i))?.let(rows::add)
        } catch (error: Exception) {
            // Keep syncing other statement types, but keep the reason (a rate-limit pause and a
            // bad key need different fixes) so the UI can show it.
            failedCategories += "$category (${error.message ?: "request failed"})"
        }
    }

    fun fetchAvailableStatements(): BinanceStatementFetchResult {
        val rows = java.util.Collections.synchronizedList(mutableListOf<BinanceStatement>())
        val failedCategories = java.util.Collections.synchronizedList(mutableListOf<String>())
        val attemptedCategories = mutableListOf<String>()
        val start = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val end = System.currentTimeMillis()
        fun map(category: String, o: JSONObject, timeKeys: List<String>, assetKey: String, amountKey: String,
                statusKey: String = "status", idKey: String = "id", detailKeys: List<String> = emptyList()): BinanceStatement? {
            val time = timeKeys.firstNotNullOfOrNull { key -> o.optLong(key, -1L).takeIf { it > 0 } } ?: return null
            val asset = o.optString(assetKey)
            val amount = o.optString(amountKey).toDoubleOrNull() ?: o.optDouble(amountKey, 0.0)
            val ref = o.optString(idKey).ifBlank { o.optString("tranId") }.ifBlank { o.optString("txId") }
            val details = detailKeys.mapNotNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                .joinToString(" | ")
            val fingerprint = "$category|$time|$asset|$amount|$ref|$details"
            val id = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
            return BinanceStatement(id, time, category, asset, amount, o.optString(statusKey), ref, details)
        }
        val timeParams = listOf("startTime" to start.toString(), "endTime" to end.toString(), "limit" to "1000")
        attemptedCategories += "Deposits"
        attemptedCategories += "Withdrawals"
        attemptedCategories += "Distribution"
        attemptedCategories += "Futures income"
        attemptedCategories += "Universal transfer"
        val executor = Executors.newFixedThreadPool(4)
        try {
            val requests = listOf(
                executor.submit {
                    bestEffort(rows, failedCategories, "Deposits", "https://api.binance.com",
                        "/sapi/v1/capital/deposit/hisrec", timeParams) { o ->
                        map("Deposits", o, listOf("insertTime"), "coin", "amount",
                            idKey = "txId", detailKeys = listOf("network", "address", "txId"))
                    }
                },
                executor.submit {
                    bestEffort(rows, failedCategories, "Withdrawals", "https://api.binance.com",
                        "/sapi/v1/capital/withdraw/history", timeParams) { o ->
                        map("Withdrawals", o, listOf("applyTime"), "coin", "amount",
                            idKey = "id", detailKeys = listOf("network", "address", "txId", "transactionFee"))
                    }
                },
                executor.submit {
                    bestEffort(rows, failedCategories, "Distribution", "https://api.binance.com",
                        "/sapi/v1/asset/assetDividend",
                        listOf("startTime" to start.toString(), "endTime" to end.toString(), "limit" to "500")) { o ->
                        map("Distribution", o, listOf("divTime"), "asset", "amount",
                            idKey = "tranId", detailKeys = listOf("enInfo", "info"))
                    }
                },
                executor.submit {
                    bestEffort(rows, failedCategories, "Futures income", "https://fapi.binance.com",
                        "/fapi/v1/income",
                        listOf("startTime" to start.toString(), "endTime" to end.toString(), "limit" to "1000")) { o ->
                        map("Futures income", o, listOf("time"), "asset", "income",
                            idKey = "tranId", detailKeys = listOf("incomeType", "symbol", "tradeId"))
                    }
                },
                executor.submit {
                    bestEffort(rows, failedCategories, "Universal transfer", "https://api.binance.com",
                        "/sapi/v1/asset/transfer",
                        listOf("type" to "MAIN_UMFUTURE", "startTime" to start.toString(),
                            "endTime" to end.toString(), "current" to "1", "size" to "100")) { o ->
                        map("Universal transfer", o, listOf("timestamp"), "asset", "amount",
                            idKey = "tranId", detailKeys = listOf("type", "from", "to"))
                    }
                }
            )
            requests.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        return BinanceStatementFetchResult(rows.distinctBy { it.id }, failedCategories.distinct(), attemptedCategories)
    }
}

object BinanceStatementSyncRunner {
    suspend fun sync(context: Context): BinanceStatementSyncResult = withContext(Dispatchers.IO) {
        BinanceRestGuard.configure(context)
        val credentials = BinanceCredentialStore(context).read() ?: error("Save Binance read-only API credentials first.")
        val fetch = BinanceStatementApi(credentials.first, credentials.second).fetchAvailableStatements()
        val saved = BinanceStatementStore.saveMerge(context, fetch.records)
        BinanceStatementSyncResult(saved.first, saved.second, fetch.failedCategories, fetch.attemptedCategories)
    }
}

class BinanceStatementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BinanceCredentialStore(applicationContext).read() == null) {
            Result.success()
        } else {
            val sync = BinanceStatementSyncRunner.sync(applicationContext)
            if (sync.failedCategories.isEmpty()) Result.success() else Result.retry()
        }
    } catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            // Statement downloads require a working network. UPDATE replaces older
            // schedules that may have been created without this constraint.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<BinanceStatementWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "binance_statement_sync", ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("binance_statement_sync")
    }
}
