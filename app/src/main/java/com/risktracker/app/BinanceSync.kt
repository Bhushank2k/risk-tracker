package com.risktracker.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class BinanceDailyResult(val date: String, val spotPnl: Double, val futuresPnl: Double) {
    val total: Double get() = spotPnl + futuresPnl
}

/** Encrypted with an Android Keystore AES-GCM key; credentials are never logged or exported. */
class BinanceCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("binance_credentials_v1", Context.MODE_PRIVATE)
    private val alias = "risk_tracker_binance_aes_v1"

    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? java.security.Key)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun save(apiKey: String, secret: String) {
        require(apiKey.isNotBlank() && secret.isNotBlank()) { "Enter both API key and secret." }
        prefs.edit().putString("api_key_enc", encrypt(apiKey.trim()))
            .putString("secret_enc", encrypt(secret.trim())).apply()
    }

    fun read(): Pair<String, String>? {
        return try {
            val a = prefs.getString("api_key_enc", null) ?: return null
            val s = prefs.getString("secret_enc", null) ?: return null
            decrypt(a) to decrypt(s)
        } catch (_: Exception) { null }
    }

    fun clear() { prefs.edit().clear().apply() }
}

class BinanceApi(private val apiKey: String, private val secret: String) {
    private fun signedRequest(base: String, path: String, params: List<Pair<String, String>>): String {
        val all = params + ("timestamp" to System.currentTimeMillis().toString()) + ("recvWindow" to "10000")
        val query = all.joinToString("&") { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(query.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val conn = (URL("$base$path?$query&signature=$signature").openConnection() as HttpURLConnection)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-MBX-APIKEY", apiKey)
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("msg") }.getOrDefault("HTTP $status")
                throw IllegalStateException("Binance API error ($status): $message")
            }
            return body
        } finally { conn.disconnect() }
    }

    /** Fetches available Spot trade history. P&L is FIFO matched across imported fills. */
    fun spotDailyPnl(): Map<String, Double> {
        val fills = mutableListOf<JSONObject>()
        // Binance Spot myTrades requires a symbol; discover account symbols via read-only account endpoint.
        val account = signedGetObject("https://api.binance.com", "/api/v3/account", emptyList())
        val balances = account.optJSONArray("balances") ?: JSONArray()
        val symbols = (0 until balances.length()).mapNotNull { i ->
            val b = balances.getJSONObject(i)
            val asset = b.optString("asset")
            if (asset.isNotBlank() && (b.optString("free").toDoubleOrNull() ?: 0.0) + (b.optString("locked").toDoubleOrNull() ?: 0.0) > 0.0 && asset !in setOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "USDP", "DAI")) asset else null
        }.distinct().take(100)
        // Use exchangeInfo to find quote pairs for held assets. Only symbols with a supported USDT quote are queried.
        val info = publicGet("https://api.binance.com/api/v3/exchangeInfo")
        val tradable = mutableListOf<String>()
        val allSymbols = info.optJSONArray("symbols") ?: JSONArray()
        for (i in 0 until allSymbols.length()) {
            val s = allSymbols.getJSONObject(i)
            if (s.optString("status") == "TRADING" && s.optString("quoteAsset") in setOf("USDT", "USDC") && s.optString("baseAsset") in symbols) tradable += s.optString("symbol")
        }
        for (symbol in tradable.take(80)) {
            var cursor: Long? = null
            var more = true
            var pages = 0
            while (more && pages < 100) {
                pages++
                val p = mutableListOf("symbol" to symbol, "limit" to "1000")
                if (cursor != null) p += "fromId" to cursor.toString()
                val page = signedGetArray("https://api.binance.com", "/api/v3/myTrades", p)
                if (page.length() == 0) {
                    more = false
                } else {
                    for (i in 0 until page.length()) fills += page.getJSONObject(i).put("_symbol", symbol)
                    val lastId = page.getJSONObject(page.length() - 1).optLong("id", -1)
                    if (page.length() < 1000 || lastId < 0) more = false else cursor = lastId + 1
                }
            }
        }
        // FIFO cost basis per pair. Only closed quantity contributes realized P&L; commissions are deducted.
        data class Lot(var qty: Double, val cost: Double)
        val lots = mutableMapOf<String, ArrayDeque<Lot>>()
        val daily = mutableMapOf<String, Double>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fills.sortedBy { it.optLong("time") }.forEach { f ->
            val symbol = f.optString("_symbol")
            val qty = f.optString("qty").toDoubleOrNull() ?: f.optDouble("qty")
            val price = f.optString("price").toDoubleOrNull() ?: f.optDouble("price")
            val quote = f.optString("quoteQty").toDoubleOrNull() ?: f.optDouble("quoteQty", qty * price)
            val commission = f.optString("commission").toDoubleOrNull() ?: f.optDouble("commission")
            val commissionAsset = f.optString("commissionAsset")
            val day = sdf.format(Date(f.optLong("time")))
            val queue = lots.getOrPut(symbol) { ArrayDeque() }
            val isBuy = f.optBoolean("isBuyer")
            if (isBuy) queue.addLast(Lot(qty, price))
            else {
                var remaining = qty
                var realized = 0.0
                while (remaining > 1e-12 && queue.isNotEmpty()) {
                    val lot = queue.first()
                    val used = minOf(remaining, lot.qty)
                    realized += used * (price - lot.cost)
                    remaining -= used
                    lot.qty -= used
                    if (lot.qty <= 1e-12) queue.removeFirst()
                }
                // Include quote-denominated commission; other commission assets are not silently converted.
                val fee = if (commissionAsset in setOf("USDT", "USDC")) commission else 0.0
                daily[day] = (daily[day] ?: 0.0) + realized - fee
            }
            // Deduct base-asset commission on buys by reducing received quantity.
            if (isBuy && commissionAsset == symbol.removeSuffix("USDT") && queue.isNotEmpty()) queue.last().qty = maxOf(0.0, queue.last().qty - commission)
        }
        return daily
    }

    fun futuresDailyPnl(): Map<String, Double> {
        val daily = mutableMapOf<String, Double>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // Binance's income-history endpoint has a limited retention window; request the latest 90 days.
        var cursor = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        var more = true
        var pages = 0
        while (more && pages < 100) {
            pages++
            val page = signedGetArray("https://fapi.binance.com", "/fapi/v1/income", listOf("startTime" to cursor.toString(), "limit" to "1000"))
            if (page.length() == 0) {
                more = false
            } else {
                var maxTime = cursor
                for (i in 0 until page.length()) {
                    val row = page.getJSONObject(i)
                    val type = row.optString("incomeType")
                    if (type in setOf("REALIZED_PNL", "COMMISSION", "FUNDING_FEE", "INSURANCE_CLEAR")) {
                        val day = sdf.format(Date(row.optLong("time")))
                        val value = row.optString("income").toDoubleOrNull() ?: row.optDouble("income")
                        daily[day] = (daily[day] ?: 0.0) + value
                    }
                    maxTime = maxOf(maxTime, row.optLong("time"))
                }
                if (page.length() < 1000 || maxTime <= cursor) more = false else cursor = maxTime + 1
            }
        }
        return daily
    }

    private fun signedGetArray(base: String, path: String, params: List<Pair<String, String>>): JSONArray =
        JSONArray(signedRequest(base, path, params))

    private fun signedGetObject(base: String, path: String, params: List<Pair<String, String>>): JSONObject =
        JSONObject(signedRequest(base, path, params))

    private fun publicGet(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("Could not load Binance market symbols.")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
}

object BinanceSyncData {
    fun save(context: Context, data: List<BinanceDailyResult>) {
        val array = JSONArray()
        data.forEach { array.put(JSONObject().put("date", it.date).put("spotPnl", it.spotPnl).put("futuresPnl", it.futuresPnl)) }
        context.getSharedPreferences("binance_import_v1", Context.MODE_PRIVATE).edit().putString("daily", array.toString())
            .putLong("last_sync", System.currentTimeMillis()).apply()
    }
    fun load(context: Context): List<BinanceDailyResult> = try {
        val a = JSONArray(context.getSharedPreferences("binance_import_v1", Context.MODE_PRIVATE).getString("daily", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { BinanceDailyResult(it.getString("date"), it.optDouble("spotPnl"), it.optDouble("futuresPnl")) } }
    } catch (_: Exception) { emptyList() }
    fun lastSync(context: Context): Long = context.getSharedPreferences("binance_import_v1", Context.MODE_PRIVATE).getLong("last_sync", 0L)
}

object BinanceSyncRunner {
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        val credentials = BinanceCredentialStore(context).read() ?: error("Save Binance read-only API credentials first.")
        val api = BinanceApi(credentials.first, credentials.second)
        val spot = api.spotDailyPnl()
        val futures = api.futuresDailyPnl()
        val days = (spot.keys + futures.keys).toSortedSet()
        BinanceSyncData.save(context, days.map { BinanceDailyResult(it, spot[it] ?: 0.0, futures[it] ?: 0.0) })
    }
}

class BinanceDailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BinanceCredentialStore(applicationContext).read() == null) Result.success()
        else { BinanceSyncRunner.sync(applicationContext); Result.success() }
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BinanceDailyWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("binance_daily_trade_sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("binance_daily_trade_sync")
    }
}
