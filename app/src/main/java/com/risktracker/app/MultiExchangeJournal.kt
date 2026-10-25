package com.risktracker.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

/** Supported exchange identifiers. Credentials are scoped per exchange and never shared between adapters. */
enum class JournalExchange(val displayName: String) {
    BINANCE("Binance"), BYBIT("Bybit"), OKX("OKX"), KRAKEN("Kraken"),
    COINBASE("Coinbase"), KUCOIN("KuCoin"), BITGET("Bitget"), GATE_IO("Gate.io")
}

data class ExchangeApiCredentials(
    val apiKey: String,
    val secret: String,
    val passphrase: String = "",
    val extra: String = ""
)

/**
 * Separate encrypted credential namespace for non-Binance adapters. Uses an Android Keystore
 * AES-GCM key; keys should be created with read-only permissions at the exchange.
 */
class MultiExchangeCredentialStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("multi_exchange_credentials_v1", Context.MODE_PRIVATE)
    private val alias = "risk_tracker_exchange_aes_v1"

    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? java.security.Key)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
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

    @Synchronized fun save(exchange: JournalExchange, credentials: ExchangeApiCredentials) {
        require(credentials.apiKey.isNotBlank() && credentials.secret.isNotBlank()) { "API key and secret are required." }
        val json = JSONObject().put("apiKey", credentials.apiKey.trim())
            .put("secret", credentials.secret.trim()).put("passphrase", credentials.passphrase)
            .put("extra", credentials.extra).toString()
        prefs.edit().putString(exchange.name, encrypt(json)).apply()
    }

    @Synchronized fun read(exchange: JournalExchange): ExchangeApiCredentials? = try {
        val encrypted = prefs.getString(exchange.name, null) ?: return null
        val row = JSONObject(decrypt(encrypted))
        ExchangeApiCredentials(row.getString("apiKey"), row.getString("secret"),
            row.optString("passphrase"), row.optString("extra"))
    } catch (_: Exception) { null }

    @Synchronized fun clear(exchange: JournalExchange) { prefs.edit().remove(exchange.name).apply() }
    fun connectedExchanges(): Set<JournalExchange> = JournalExchange.values().filter { read(it) != null }.toSet()
}

/** Implementations return raw executions; they must never submit orders or request withdrawal permissions. */
interface ExchangeJournalAdapter {
    val exchange: JournalExchange
    fun fetchFills(credentials: ExchangeApiCredentials, symbol: String, startTimeMs: Long, endTimeMs: Long): List<BinanceJournalFill>
}

/** Bybit V5 execution-history adapter (Spot and linear perpetuals). Uses read-only signed GET requests. */
class BybitJournalAdapter : ExchangeJournalAdapter {
    override val exchange = JournalExchange.BYBIT

    override fun fetchFills(
        credentials: ExchangeApiCredentials,
        symbol: String,
        startTimeMs: Long,
        endTimeMs: Long
    ): List<BinanceJournalFill> {
        require(symbol.matches(Regex("[A-Z0-9]{4,30}"))) { "Enter a valid symbol, such as BTCUSDT." }
        require(startTimeMs <= endTimeMs) { "Start date must be before end date." }
        return listOf("spot", "linear").flatMap { category ->
            fetchCategory(credentials, symbol, startTimeMs, endTimeMs, category)
        }.distinctBy { it.id }.sortedBy { it.time }
    }

    private fun fetchCategory(
        credentials: ExchangeApiCredentials, symbol: String, start: Long, end: Long, category: String
    ): List<BinanceJournalFill> {
        val result = mutableListOf<BinanceJournalFill>()
        var cursor = ""
        var pages = 0
        while (pages++ < 20) {
            val params = linkedMapOf(
                "category" to category, "symbol" to symbol,
                "startTime" to start.toString(), "endTime" to end.toString(), "limit" to "100"
            )
            if (cursor.isNotBlank()) params["cursor"] = cursor
            val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val timestamp = System.currentTimeMillis().toString()
            val recvWindow = "5000"
            val payload = timestamp + credentials.apiKey + recvWindow + query
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(credentials.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val conn = (URL("https://api.bybit.com/v5/execution/list?$query").openConnection() as HttpURLConnection)
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-BAPI-API-KEY", credentials.apiKey)
            conn.setRequestProperty("X-BAPI-SIGN", signature)
            conn.setRequestProperty("X-BAPI-SIGN-TYPE", "2")
            conn.setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            conn.setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            val body = try {
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) error("Bybit API HTTP $status: $text")
                text
            } finally { conn.disconnect() }
            val root = JSONObject(body)
            require(root.optInt("retCode", -1) == 0) { "Bybit API error: ${root.optString("retMsg", "unknown error")}" }
            val data = root.getJSONObject("result")
            val rows = data.getJSONArray("list")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val time = row.optString("execTime").toLongOrNull() ?: continue
                if (time !in start..end) continue
                val qty = row.optString("execQty").toDoubleOrNull() ?: continue
                val price = row.optString("execPrice").toDoubleOrNull() ?: continue
                val fee = row.optString("execFee").toDoubleOrNull() ?: 0.0
                val feeAsset = row.optString("feeCurrency")
                val side = row.optString("side").uppercase()
                result += BinanceJournalFill(
                    id = "bybit:$category:${row.optString("execId")}", exchange = "Bybit",
                    market = if (category == "spot") "Spot" else "Linear Futures", symbol = symbol,
                    side = side, quantity = qty, price = price, quoteAmount = qty * price,
                    fee = fee, feeAsset = feeAsset, realizedPnl = row.optString("closedPnl").toDoubleOrNull(),
                    time = time, orderId = row.optString("orderId")
                )
            }
            cursor = data.optString("nextPageCursor")
            if (cursor.isBlank() || rows.length() == 0) break
        }
        return result
    }
}

/** Only adapters with implemented signing and endpoint semantics are returned. */
object ExchangeJournalAdapters {
    fun implemented(): Map<JournalExchange, ExchangeJournalAdapter> =
        mapOf(JournalExchange.BYBIT to BybitJournalAdapter())
}
