package com.risktracker.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class FuturesTradeArchiveWindow(val start: Long, val end: Long, val key: String)

internal fun fiveYearFuturesTradeWindows(now: Long = System.currentTimeMillis()): List<FuturesTradeArchiveWindow> {
    val utc = TimeZone.getTimeZone("UTC")
    val cursor = Calendar.getInstance(utc).apply { timeInMillis = now; add(Calendar.YEAR, -5) }
    val result = mutableListOf<FuturesTradeArchiveWindow>()
    repeat(5) {
        val start = cursor.timeInMillis
        cursor.add(Calendar.YEAR, 1)
        val nextYearBoundary = cursor.timeInMillis
        // Include the current instant when the final annual boundary lands exactly on now.
        val end = if (nextYearBoundary >= now) now else nextYearBoundary - 1L
        if (start <= end) result += FuturesTradeArchiveWindow(start, end, "${start}_${end}")
    }
    return result
}

/** Read-only access to Binance's official USDⓈ-M Futures trade-history archive endpoints. */
private class BinanceFuturesTradeArchiveApi(private val apiKey: String, private val secret: String) {
    private fun signedGet(path: String, params: List<Pair<String, String>>): JSONObject {
        val all = params + ("timestamp" to System.currentTimeMillis().toString()) + ("recvWindow" to "10000")
        val query = all.joinToString("&") { (key, value) ->
            "${key}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val signature = mac.doFinal(query.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val connection = URL("https://fapi.binance.com$path?$query&signature=$signature")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("X-MBX-APIKEY", apiKey)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("msg") }.getOrDefault("HTTP $code")
                error("Binance archive API error ($code): $message")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    fun request(window: FuturesTradeArchiveWindow): String {
        val response = signedGet(
            "/fapi/v1/trade/asyn",
            listOf("startTime" to window.start.toString(), "endTime" to window.end.toString())
        )
        return response.optString("downloadId").takeIf(String::isNotBlank)
            ?: error("Binance did not return an archive download ID.")
    }

    fun status(downloadId: String): JSONObject =
        signedGet("/fapi/v1/trade/asyn/id", listOf("downloadId" to downloadId))

    fun downloadCsv(url: String): List<BinanceJournalFill> {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Binance archive link must use HTTPS." }
        require(parsed.host == "binance.com" || parsed.host.endsWith(".binance.com") || parsed.host == "binance.net" || parsed.host.endsWith(".binance.net")) {
            "Binance returned an unexpected archive download host."
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode !in 200..299) error("Archive download failed (HTTP ${connection.responseCode}).")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.isNotEmpty()) { "Binance returned an empty archive." }
            val csvStreams = mutableListOf<ByteArray>()
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                            csvStreams += zip.readBytes()
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                csvStreams += bytes
            }
            require(csvStreams.isNotEmpty()) { "The downloaded archive did not contain a CSV trade-history file." }
            return csvStreams.flatMap { csv ->
                BinanceFillCsvImporter.parse(ByteArrayInputStream(csv), "USDⓈ-M Futures").fills
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Local progress is durable: request IDs survive app restarts, and already-imported years are never requested twice. */
private class FuturesTradeArchiveProgress(context: Context) {
    private val prefs = context.getSharedPreferences("binance_futures_trade_archives_v1", Context.MODE_PRIVATE)

    fun id(window: FuturesTradeArchiveWindow): String? = prefs.getString("id_${window.key}", null)
    fun saveId(window: FuturesTradeArchiveWindow, id: String) {
        check(prefs.edit().putString("id_${window.key}", id).commit()) { "Could not save the Binance archive request ID." }
    }
    fun imported(window: FuturesTradeArchiveWindow): Boolean = prefs.getBoolean("imported_${window.key}", false)
    fun markImported(window: FuturesTradeArchiveWindow) {
        check(prefs.edit().putBoolean("imported_${window.key}", true).commit()) { "Could not save archive import progress." }
    }
    fun reset() { prefs.edit().clear().apply() }
    fun summary(windows: List<FuturesTradeArchiveWindow>): String {
        val requested = windows.count { id(it) != null }
        val imported = windows.count { imported(it) }
        return "$imported of ${windows.size} periods imported · $requested archive request(s) saved"
    }
}

@Composable
fun BinanceFiveYearTradeBackfillSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windows = remember { fiveYearFuturesTradeWindows() }
    val utcDateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val progress = remember { FuturesTradeArchiveProgress(context) }
    var summary by remember { mutableStateOf(progress.summary(windows)) }
    var status by remember { mutableStateOf("Five-year USDⓈ-M Futures trade archive. Binance allows up to five archive requests per month.") }
    var busy by remember { mutableStateOf(false) }

    RiskCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Five-year Futures trade history", style = MaterialTheme.typography.titleMedium)
            Text("Target: ${utcDateFormat.format(Date(windows.first().start))} – ${utcDateFormat.format(Date(windows.last().end))} UTC. Each request covers at most one year; the five-year range is split into five contiguous periods.", style = MaterialTheme.typography.bodySmall)
            Text(summary, style = MaterialTheme.typography.labelLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val credentials = BinanceCredentialStore(context).read()
                    if (credentials == null) {
                        status = "Connect Binance with read-only API credentials first."
                    } else {
                        busy = true
                        status = "Submitting missing archive periods to Binance…"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val api = BinanceFuturesTradeArchiveApi(credentials.first, credentials.second)
                                    var requested = 0
                                    val failures = mutableListOf<String>()
                                    windows.filterNot { progress.imported(it) || progress.id(it) != null }.forEach { window ->
                                        runCatching {
                                            val id = api.request(window)
                                            progress.saveId(window, id)
                                            requested++
                                        }.onFailure { failures += it.message ?: "request failed" }
                                    }
                                    requested to failures
                                }
                                summary = progress.summary(windows)
                                status = if (result.second.isEmpty()) {
                                    "Saved ${result.first} new archive request ID(s). Use Check & import to retrieve completed files. Existing requests were not repeated."
                                } else {
                                    "${result.first} request(s) saved. Binance rejected another request: ${result.second.first()} Remaining periods can be retried later; monthly quota is controlled by Binance."
                                }
                            } catch (e: Exception) {
                                status = e.message ?: "Could not request historical archives."
                            } finally { busy = false }
                        }
                    }
                }
            ) { Text(if (busy) "Working…" else "Request missing archive periods") }
            OutlinedButton(
                enabled = !busy && windows.any { progress.id(it) != null && !progress.imported(it) },
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val credentials = BinanceCredentialStore(context).read()
                    if (credentials == null) {
                        status = "Connect Binance with read-only API credentials first."
                    } else {
                        busy = true
                        status = "Checking archive status and importing completed files…"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val api = BinanceFuturesTradeArchiveApi(credentials.first, credentials.second)
                                    var importedPeriods = 0
                                    var importedFills = 0
                                    val pending = mutableListOf<String>()
                                    val failures = mutableListOf<String>()
                                    windows.filter { progress.id(it) != null && !progress.imported(it) }.forEach { window ->
                                        val id = progress.id(window) ?: return@forEach
                                        runCatching {
                                            val state = api.status(id)
                                            val url = sequenceOf("downloadUrl", "url", "downloadLink")
                                                .map { state.optString(it) }.firstOrNull { it.isNotBlank() }
                                            val stateLabel = state.optString("status", state.optString("state")).lowercase(Locale.ROOT)
                                            if (url.isNullOrBlank()) {
                                                pending += "${window.key}: ${stateLabel.ifBlank { "processing" }}"
                                            } else {
                                                val fills = api.downloadCsv(url)
                                                val added = BinanceAutoJournalStore.merge(context, fills)
                                                progress.markImported(window)
                                                importedPeriods++
                                                importedFills += added
                                            }
                                        }.onFailure { failures += it.message ?: "archive processing failed" }
                                    }
                                    Triple(importedPeriods to importedFills, pending, failures)
                                }
                                summary = progress.summary(windows)
                                status = buildString {
                                    append("Imported ${result.first.first} period(s), ${result.first.second} new trade(s).")
                                    if (result.second.isNotEmpty()) append(" Still processing: ${result.second.joinToString()}.")
                                    if (result.third.isNotEmpty()) append(" Errors: ${result.third.joinToString("; ")}")
                                    if (result.first.first == 0 && result.second.isEmpty() && result.third.isEmpty()) append(" No unprocessed archive requests were found.")
                                }
                            } catch (e: Exception) {
                                status = e.message ?: "Could not check the archive requests."
                            } finally { busy = false }
                        }
                    }
                }
            ) { Text("Check & import completed archives") }
            Text("This archive covers USDⓈ-M Futures executions. Spot history is a separate symbol-scoped API and cannot be guaranteed account-wide when previously traded assets are no longer held. Binance controls archive availability, request quota and link expiry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
