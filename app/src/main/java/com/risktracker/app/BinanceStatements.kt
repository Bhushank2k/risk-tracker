package com.risktracker.app

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
object BinanceStatementStore {
    private const val PREFS = "binance_account_statements_v1"
    fun load(context: Context): List<BinanceStatement> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rows", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            BinanceStatement(o.getString("id"), o.getLong("time"), o.getString("category"),
                o.optString("asset"), o.optDouble("amount"), o.optString("status"),
                o.optString("reference"), o.optString("details"))
        }.sortedByDescending { it.time }
    }.getOrDefault(emptyList())

    fun saveMerge(context: Context, incoming: List<BinanceStatement>): Pair<Int, Int> {
        val old = load(context)
        val seen = old.map { it.id }.toHashSet()
        val fresh = incoming.filter { seen.add(it.id) }
        val all = (old + fresh).sortedBy { it.time }
        val a = JSONArray()
        all.forEach { r -> a.put(JSONObject().put("id", r.id).put("time", r.time)
            .put("category", r.category).put("asset", r.asset).put("amount", r.amount)
            .put("status", r.status).put("reference", r.reference).put("details", r.details)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rows", a.toString())
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
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
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
        } catch (_: Exception) {
            // Keep syncing other statement types, but report this category as unavailable to the UI.
            failedCategories += category
        }
    }

    fun fetchAvailableStatements(): BinanceStatementFetchResult {
        val rows = mutableListOf<BinanceStatement>()
        val failedCategories = mutableListOf<String>()
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
        bestEffort(rows, failedCategories, "Deposits", "https://api.binance.com", "/sapi/v1/capital/deposit/hisrec", timeParams) { o ->
            map("Deposits", o, listOf("insertTime"), "coin", "amount", idKey = "txId", detailKeys = listOf("network", "address", "txId"))
        }
        attemptedCategories += "Withdrawals"
        bestEffort(rows, failedCategories, "Withdrawals", "https://api.binance.com", "/sapi/v1/capital/withdraw/history", timeParams) { o ->
            map("Withdrawals", o, listOf("applyTime"), "coin", "amount", idKey = "id", detailKeys = listOf("network", "address", "txId", "transactionFee"))
        }
        attemptedCategories += "Distribution"
        bestEffort(rows, failedCategories, "Distribution", "https://api.binance.com", "/sapi/v1/asset/assetDividend",
            listOf("startTime" to start.toString(), "endTime" to end.toString(), "limit" to "500")) { o ->
            map("Distribution", o, listOf("divTime"), "asset", "amount", idKey = "tranId", detailKeys = listOf("enInfo", "info"))
        }
        attemptedCategories += "Futures income"
        bestEffort(rows, failedCategories, "Futures income", "https://fapi.binance.com", "/fapi/v1/income",
            listOf("startTime" to start.toString(), "endTime" to end.toString(), "limit" to "1000")) { o ->
            map("Futures income", o, listOf("time"), "asset", "income", idKey = "tranId", detailKeys = listOf("incomeType", "symbol", "tradeId"))
        }
        attemptedCategories += "Universal transfer"
        bestEffort(rows, failedCategories, "Universal transfer", "https://api.binance.com", "/sapi/v1/asset/transfer",
            listOf("type" to "MAIN_UMFUTURE", "startTime" to start.toString(), "endTime" to end.toString(), "current" to "1", "size" to "100")) { o ->
            map("Universal transfer", o, listOf("timestamp"), "asset", "amount", idKey = "tranId", detailKeys = listOf("type", "from", "to"))
        }
        return BinanceStatementFetchResult(rows.distinctBy { it.id }, failedCategories.distinct(), attemptedCategories)
    }
}

object BinanceStatementSyncRunner {
    suspend fun sync(context: Context): BinanceStatementSyncResult = withContext(Dispatchers.IO) {
        val credentials = BinanceCredentialStore(context).read() ?: error("Save Binance read-only API credentials first.")
        val fetch = BinanceStatementApi(credentials.first, credentials.second).fetchAvailableStatements()
        val saved = BinanceStatementStore.saveMerge(context, fetch.records)
        BinanceStatementSyncResult(saved.first, saved.second, fetch.failedCategories, fetch.attemptedCategories)
    }
}

class BinanceStatementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BinanceCredentialStore(applicationContext).read() == null) Result.success()
        else { BinanceStatementSyncRunner.sync(applicationContext); Result.success() }
    } catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BinanceStatementWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("binance_statement_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("binance_statement_sync")
    }
}
