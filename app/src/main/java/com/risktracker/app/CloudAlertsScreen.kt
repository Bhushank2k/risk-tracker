package com.risktracker.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CloudTradeAlert(
    val id: String, val symbol: String, val side: String, val setup: String,
    val horizon: String, val createdAt: Long, val entry: Double, val stop: Double,
    val target1: Double, val status: String
)

private object CloudAlertSettings {
    private const val PREFS = "cloud_alert_connection_v1"
    private const val KEY_ALIAS = "risk_tracker_cloud_alert_token"
    fun url(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "") ?: ""
    fun saveUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("url", value.trim()).apply()
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun token(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("token_iv", null) ?: return null
        val data = prefs.getString("token_data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun saveToken(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove("token_iv").remove("token_data").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
}

private fun fetchCloudAlerts(baseUrl: String, token: String): List<CloudTradeAlert> {
    val endpoint = baseUrl.trim().trimEnd('/') + "/alerts"
    require(endpoint.startsWith("https://")) { "Use your HTTPS Cloud Run URL." }
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Accept", "application/json")
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException(
            if (code == 401 || code == 403) "Cloud access rejected. Check the alert token and service access."
            else "Cloud service returned HTTP $code."
        )
        val rows = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("alerts")
            ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                CloudTradeAlert(item.getString("alert_id"), item.getString("symbol"), item.getString("side"),
                    item.getString("setup"), item.getString("horizon"),
                    (item.getDouble("created_at") * 1000.0).toLong(), item.getDouble("entry"),
                    item.getDouble("stop"), item.getDouble("target1"), item.getString("status"))
            }.getOrNull()
        }.filter { it.status == "READY" && it.horizon.equals("Intraday", true) }
    } finally { connection.disconnect() }
}

@Composable
fun CloudAlertsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var baseUrl by remember { mutableStateOf(CloudAlertSettings.url(context)) }
    var token by remember { mutableStateOf(CloudAlertSettings.token(context).orEmpty()) }
    var alerts by remember { mutableStateOf<List<CloudTradeAlert>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect your cloud alert service to view signals.") }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        if (baseUrl.isBlank() || token.isBlank()) { status = "Enter your Cloud Run URL and alert access token."; return }
        loading = true
        try {
            alerts = withContext(Dispatchers.IO) { fetchCloudAlerts(baseUrl, token) }
            lastUpdated = System.currentTimeMillis()
            status = if (alerts.isEmpty()) "Connected · no recent READY alerts." else "Connected · " + alerts.size + " recent alert(s)."
        } catch (error: Exception) { status = error.message ?: "Unable to load cloud alerts." }
        finally { loading = false }
    }

    LaunchedEffect(Unit) {
        if (baseUrl.isNotBlank() && token.isNotBlank()) refresh()
        while (isActive) {
            delay(60_000)
            if (baseUrl.isNotBlank() && token.isNotBlank()) refresh()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cloud Alerts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Fresh READY Intraday signals. The app checks every 60 seconds while this screen is open.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Cloud Run service URL") }, placeholder = { Text("https://your-service.run.app") })
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Alert access token") }, visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            CloudAlertSettings.saveUrl(context, baseUrl)
                            runCatching { CloudAlertSettings.saveToken(context, token) }
                                .onSuccess { status = "Connection settings saved securely on this device." }
                                .onFailure { status = "Could not securely save the token on this device." }
                        }) { Text("Save connection") }
                        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (loading) "Loading…" else "Refresh")
                        }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lastUpdated?.let { stamp ->
                        Text("Last checked: " + SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(stamp)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (alerts.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No cloud alerts to show", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Valid cloud READY Intraday signals will appear here after the scanner publishes them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(alerts, key = { it.id }) { alert ->
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(alert.symbol + " · " + alert.side, Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                                Text("READY", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Text(alert.setup + " · " + alert.horizon, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Entry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(alert.entry.toString(), fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(alert.stop.toString(), fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Target 1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(alert.target1.toString(), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.getDefault()).format(Date(alert.createdAt)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("No order placement. Verify live price, stop and setup before taking any trade.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
