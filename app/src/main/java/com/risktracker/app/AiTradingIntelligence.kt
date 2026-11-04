package com.risktracker.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AI_PREFS = "risk_tracker_ai_v1"
private const val AI_KEY_ALIAS = "risk_tracker_openai_key_v1"

/** API key is encrypted with an Android Keystore AES-GCM key and is never exported. */
class OpenAiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs.contains("key_ciphertext") && prefs.contains("key_iv")

    fun save(apiKey: String) {
        require(apiKey.startsWith("sk-") && apiKey.length >= 20) { "Enter a valid OpenAI API key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("key_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("key_iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("key_ciphertext", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("key_iv").remove("key_ciphertext").apply()
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(AI_KEY_ALIAS) } }
    }

    private fun secretKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(AI_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            AI_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

enum class AiReportKind(val title: String, val instruction: String) {
    JOURNAL("Trade journal summary", "Summarize the supplied closed trades. Explain execution quality, risk taken, outcome, and one lesson per meaningful trade. Separate recorded facts from interpretations."),
    BEHAVIOR("Behavioral patterns", "Explain recurring behavioral patterns only when the supplied sample supports them. Look for revenge trading proxies, overtrading, risk escalation after losses, stop discipline and plan adherence. Never diagnose emotions or claim causation from correlation."),
    WEEKLY("Weekly performance review", "Write a concise weekly review with performance, best/worst trade, drawdown context, behavioral patterns, what worked, what did not, and up to three practical next-week process actions."),
    DISCIPLINE("Personalized risk-discipline observations", "Give personalized observations about risk discipline from the supplied journal, saved reviews and risk limits. Identify evidence, uncertainty, and one measurable process improvement. Do not recommend specific trades or leverage.")
}

/** Builds a minimized payload. Never includes exchange credentials, account identifiers or screenshot URIs. */
fun buildAiTradingContext(
    trades: List<TradeEntry>,
    reviews: Map<String, TradeReview>,
    dailyEntries: List<PnlEntry>,
    kind: AiReportKind,
    nowMillis: Long = System.currentTimeMillis()
): JSONObject {
    val sorted = trades.filter { it.pnl.isFinite() && it.entry.isFinite() && it.exit.isFinite() && it.quantity.isFinite() }
        .sortedBy { it.date }
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
    val weekStart = calendar.timeInMillis
    val weekTrades = sorted.filter { (parseAiDate(it.date)?.time ?: 0L) >= weekStart }
    val selected = if (kind == AiReportKind.WEEKLY) weekTrades else sorted
    val tradeRows = JSONArray()
    selected.takeLast(150).forEach { trade ->
        val review = reviews[trade.id]
        tradeRows.put(JSONObject()
            .put("date", trade.date.take(24))
            .put("symbol", trade.symbol.take(24))
            .put("side", trade.side.take(8))
            .put("entry", trade.entry)
            .put("exit", trade.exit)
            .put("quantity", trade.quantity)
            .put("fees", trade.fees)
            .put("estimated_net_pnl", trade.pnl)
            .put("planned_risk_usd", trade.riskUsd.takeIf { it.isFinite() && it > 0.0 } ?: JSONObject.NULL)
            .put("strategy", trade.strategy.take(80))
            .put("mistake_tag", trade.mistake.take(80))
            .put("journal_note", trade.notes.take(500))
            .put("followed_plan", review?.followedPlan ?: JSONObject.NULL)
            .put("setup_tag", review?.setupTag?.take(80) ?: "")
            .put("emotion_before_self_report", review?.emotionBefore?.take(80) ?: "")
            .put("review_note", review?.notes?.take(300) ?: ""))
    }
    val dailyRows = JSONArray()
    dailyEntries.filter { it.pnl.isFinite() && it.balance.isFinite() }.takeLast(35).forEach {
        dailyRows.put(JSONObject().put("date", it.date.take(24)).put("reported_daily_pnl", it.pnl)
            .put("opening_balance", it.balance).put("discipline_score", it.disciplineScore)
            .put("note", it.notes.take(200)))
    }
    return JSONObject().put("report_type", kind.name)
        .put("data_quality", JSONObject()
            .put("journal_trade_count", selected.size)
            .put("trades_included", minOf(selected.size, 150))
            .put("reviews_available", selected.count { reviews.containsKey(it.id) })
            .put("daily_entries_included", dailyRows.length())
            .put("pnl_basis", "Journal P&L is locally estimated from entry, exit, quantity, direction and fees; it is not exchange-reported P&L."))
        .put("trades", tradeRows).put("daily_history", dailyRows)
}

private fun parseAiDate(value: String): Date? {
    for (pattern in listOf("yyyy-MM-dd", "d/M/yyyy", "dd/MM/yyyy", "M/d/yyyy", "MM/dd/yyyy")) {
        val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val parsed = runCatching { f.parse(value.trim()) }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

private class OpenAiTradingAnalyst(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun analyze(kind: AiReportKind, context: JSONObject): String {
        val system = """You are Risk Tracker's trading journal analyst. Use only supplied data. Never invent missing metrics, trades, dates, account balances or causes. Explicitly flag small samples and incomplete coverage. Distinguish estimates from exchange-reported facts. Do not provide buy/sell signals, price predictions, or instructions to increase leverage. Give balanced, specific, non-judgmental process feedback. If evidence is insufficient, say so."""
        val requestJson = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content",
                    kind.instruction + "\n\nAnalyze this sanitized journal data:\n" + context.toString())))
            .put("max_tokens", 1200)
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "OpenAI request failed (HTTP ${response.code}). Check the API key and account access.")
            }
            val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
            return content?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("The AI response was empty.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTradingIntelligenceScreen(trades: List<TradeEntry>, dailyEntries: List<PnlEntry>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyStore = remember { OpenAiKeyStore(context) }
    val reviewStore = remember { TradeReviewStore(context) }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(AiReportKind.JOURNAL) }
    var consent by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf(context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).getString("last_report", null)) }
    var reportKind by remember { mutableStateOf(context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).getString("last_report_kind", null)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Trading Intelligence", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("On-demand analysis of your saved journal. Nothing is sent until you explicitly consent and tap Generate.")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Privacy first", fontWeight = FontWeight.SemiBold)
                Text("Only a minimized trade journal and review summary is sent to OpenAI when you request a report. Binance API credentials, screenshots and exchange account identifiers are excluded. Reports are saved locally.")
                Text("OpenAI processes submitted data under its API data policies. Do not include sensitive personal notes in your journal.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(if (hasKey) "OpenAI API key configured" else "OpenAI API key not configured",
                    fontWeight = FontWeight.SemiBold)
                Text("Stored encrypted using Android Keystore", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showKeyDialog = true }) { Text(if (hasKey) "Update" else "Add key") }
        }
        if (hasKey) TextButton(onClick = { keyStore.clear(); hasKey = false; report = null; reportKind = null }) {
            Text("Remove API key and saved AI report", color = MaterialTheme.colorScheme.error)
        }
        Text("Choose an analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        AiReportKind.values().forEach { option ->
            FilterChip(selected = kind == option, onClick = { kind = option },
                label = { Text(option.title) })
        }
        Text(when (kind) {
            AiReportKind.JOURNAL -> "Summarizes recent closed journal trades and their recorded reviews."
            AiReportKind.BEHAVIOR -> "Explains recurring patterns, using minimum-sample and uncertainty caveats."
            AiReportKind.WEEKLY -> "Reviews trades dated from the current local Monday onward."
            AiReportKind.DISCIPLINE -> "Compares recorded behavior with saved plan-adherence and risk data."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Available journal trades: ${trades.count { it.pnl.isFinite() }} · Daily records: ${dailyEntries.size}")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text("I consent to sending this selected, minimized journal data to OpenAI for this report.")
        }
        Button(onClick = {
            if (loading || !consent || !hasKey) return@Button
            loading = true; error = null
            scope.launch {
                try {
                    val contextData = buildAiTradingContext(trades, reviewStore.load(), dailyEntries, kind)
                    if (contextData.optJSONObject("data_quality")?.optInt("journal_trade_count", 0) == 0) {
                        throw IllegalStateException("There are no valid closed journal trades to analyze.")
                    }
                    val result = withContext(Dispatchers.IO) {
                        OpenAiTradingAnalyst(keyStore.read() ?: throw IllegalStateException("API key unavailable. Add it again."))
                            .analyze(kind, contextData)
                    }
                    context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
                        .putString("last_report", result).putString("last_report_kind", kind.title)
                        .putLong("last_report_at", System.currentTimeMillis()).apply()
                    report = result; reportKind = kind.title; consent = false
                } catch (e: Exception) {
                    error = e.message ?: "Unable to generate the report."
                } finally { loading = false }
            }
        }, enabled = hasKey && consent && !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Generate ${kind.title}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!report.isNullOrBlank()) {
            HorizontalDivider()
            Text("Latest report · ${reportKind ?: "AI analysis"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(report!!, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { report = null; reportKind = null
                context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit().remove("last_report").remove("last_report_kind").remove("last_report_at").apply()
            }) { Text("Delete saved report") }
        }
        Text("AI observations are educational reflections, not financial advice or proof of causation. Verify all figures against exchange records.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false; apiKey = "" },
            title = { Text("OpenAI API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your key is encrypted on this device and excluded from backups. A mobile app cannot fully conceal a user-supplied API key from a compromised device; a private server proxy is preferable for a public release.")
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { keyStore.save(apiKey.trim()) }.onSuccess { hasKey = true; error = null }
                        .onFailure { error = it.message ?: "Could not save API key." }
                    apiKey = ""; showKeyDialog = false
                }, enabled = apiKey.trim().startsWith("sk-") && apiKey.trim().length >= 20) { Text("Save securely") }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false; apiKey = "" }) { Text("Cancel") } }
        )
    }
}
