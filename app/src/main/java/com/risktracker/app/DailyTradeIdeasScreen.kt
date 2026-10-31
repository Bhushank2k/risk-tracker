package com.risktracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class ScanTicker(val symbol: String, val quoteVolume: Double, val changePct: Double, val last: Double)
private data class ScanCandle(val high: Double, val low: Double, val close: Double, val volume: Double, val closeTime: Long)
internal data class TradeIdea(
    val symbol: String, val side: String, val score: Int, val volume: Double, val change: Double,
    val last: Double, val entry: Double, val stop: Double, val target1: Double, val target2: Double,
    val entryTriggered: Boolean,
    val rsi: Double, val volumeRatio: Double, val bias4h: String, val bias1h: String,
    val setup: String, val reasons: List<String>, val warning: String, val horizon: String
)

internal object BinanceMarketScanner {
    private const val BASE = "https://fapi.binance.com"

    fun latestPrice(symbol: String): Double? = runCatching {\n        val row = JSONObject(get(\"/fapi/v1/ticker/price?symbol=\" + symbol))\n        row.optString(\"price\").toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }\n    }.getOrNull()\n\n    private fun get(path: String): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 16000
        c.requestMethod = "GET"
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("msg") }.getOrDefault("request failed")
                throw IllegalStateException("Binance HTTP " + code + ": " + message)
            }
            body
        } finally { c.disconnect() }
    }

    private fun candles(symbol: String, interval: String, limit: Int): List<ScanCandle> {
        val raw = JSONArray(get("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit))
        val now = System.currentTimeMillis()
        return (0 until raw.length()).mapNotNull { i ->
            val a = raw.optJSONArray(i) ?: return@mapNotNull null
            runCatching { ScanCandle(a.getString(2).toDouble(), a.getString(3).toDouble(),
                a.getString(4).toDouble(), a.getString(5).toDouble(), a.getLong(6)) }.getOrNull()
        }.filter { it.closeTime < now && it.close > 0.0 }
    }

    private fun ema(v: List<Double>, p: Int): Double? {
        if (v.size < p) return null
        val alpha = 2.0 / (p + 1.0)
        var e = v.take(p).average()
        v.drop(p).forEach { e = it * alpha + e * (1.0 - alpha) }
        return e.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rsi(v: List<Double>): Double? {
        if (v.size < 15) return null
        val changes = v.zipWithNext { a, b -> b - a }.takeLast(14)
        val gain = changes.filter { it > 0 }.sum() / 14.0
        val loss = -changes.filter { it < 0 }.sum() / 14.0
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }

    private fun atr(rows: List<ScanCandle>): Double? {
        if (rows.size < 16) return null
        val ranges = rows.zipWithNext { p, c -> maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close)) }.takeLast(14)
        return ranges.average().takeIf { it > 0.0 && it.isFinite() }
    }

    private fun vwap(rows: List<ScanCandle>): Double? {
        val s = rows.takeLast(24)
        val volume = s.sumOf { it.volume }
        return if (volume <= 0) null else s.sumOf { it.close * it.volume } / volume
    }

    suspend fun scan(progress: (String) -> Unit): List<TradeIdea> = withContext(Dispatchers.IO) {
        progress("Loading active USDT perpetual contracts…")
        val info = JSONObject(get("/fapi/v1/exchangeInfo")).optJSONArray("symbols") ?: JSONArray()
        val allowed = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter {
            it.optString("status") == "TRADING" && it.optString("contractType") == "PERPETUAL" && it.optString("quoteAsset") == "USDT"
        }.map { it.optString("symbol") }.toSet()
        progress("Ranking eligible contracts by 24-hour traded value…")
        val all = JSONArray(get("/fapi/v1/ticker/24hr"))
        val candidates = (0 until all.length()).mapNotNull { i ->
            val x = all.optJSONObject(i) ?: return@mapNotNull null
            val symbol = x.optString("symbol")
            val quote = x.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            val last = x.optString("lastPrice").toDoubleOrNull() ?: 0.0
            if (symbol !in allowed || quote < 5_000_000.0 || last <= 0.0) null
            else ScanTicker(symbol, quote, x.optString("priceChangePercent").toDoubleOrNull() ?: 0.0, last)
        }.sortedByDescending { it.quoteVolume }.take(24)
        if (candidates.isEmpty()) throw IllegalStateException("No liquid USDT perpetual pairs were returned.")
        val gate = Semaphore(4)
        progress("Analysing 4H, 1H and 15M candles for " + candidates.size + " liquid pairs…")
        coroutineScope {
            candidates.map { ticker -> async {
                gate.withPermit {
                    runCatching {
                        val h4 = candles(ticker.symbol, "4h", 90)
                        val h1 = candles(ticker.symbol, "1h", 90)
                        val m15 = candles(ticker.symbol, "15m", 70)
                        evaluate(ticker, h4, h1, m15)
                    }.getOrNull()
                }
            } }.awaitAll().filterNotNull().sortedByDescending { it.score }
        }
    }

    private fun evaluate(t: ScanTicker, h4: List<ScanCandle>, h1: List<ScanCandle>, m: List<ScanCandle>): TradeIdea? {
        if (h4.size < 55 || h1.size < 25 || m.size < 25) return null
        val c4 = h4.map { it.close }; val c1 = h1.map { it.close }; val cm = m.map { it.close }
        val e4f = ema(c4, 9) ?: return null; val e4s = ema(c4, 21) ?: return null; val e4b = ema(c4, 50) ?: return null
        val e1f = ema(c1, 9) ?: return null; val e1s = ema(c1, 21) ?: return null
        val emf = ema(cm, 9) ?: return null; val ems = ema(cm, 21) ?: return null
        val vwap = vwap(m) ?: return null; val rsi = rsi(cm) ?: return null; val atr = atr(m) ?: return null
        val last = m.last()
        val averageVolume = m.dropLast(1).takeLast(20).map { it.volume }.average()
        val volumeRatio = if (averageVolume > 0) last.volume / averageVolume else 0.0
        val bull4 = h4.last().close > e4b && e4f > e4s && e4s > e4b
        val bear4 = h4.last().close < e4b && e4f < e4s && e4s < e4b
        val bull1 = h1.last().close > e1s && e1f > e1s
        val bear1 = h1.last().close < e1s && e1f < e1s
        val bull15 = last.close > vwap && emf > ems
        val bear15 = last.close < vwap && emf < ems
        val side = when { bull4 && bull1 && bull15 -> "LONG"; bear4 && bear1 && bear15 -> "SHORT"; else -> "WAIT" }
        val reasons = mutableListOf<String>()
        if (side == "WAIT") {
            reasons.add("4H, 1H and 15M trend conditions are not all aligned")
            if (volumeRatio < 1.0) reasons.add("15M volume is below its recent average")
        } else {
            reasons.add("4H, 1H and 15M trend direction agree")
            reasons.add(if (side == "LONG") "Price is above 15M VWAP" else "Price is below 15M VWAP")
            if (volumeRatio >= 1.2) reasons.add("15M volume is " + "%.2f".format(Locale.US, volumeRatio) + "× its prior-20-bar average")
        }
        var score = if (side == "WAIT") 15 else 60
        if (side != "WAIT") {
            score += if (volumeRatio >= 1.2) 15 else if (volumeRatio >= 0.8) 7 else 0
            if (rsi in 45.0..68.0 || rsi in 32.0..55.0) score += 10
            score += if (t.quoteVolume >= 100_000_000) 10 else if (t.quoteVolume >= 20_000_000) 7 else 4
            if (t.changePct * (if (side == "LONG") 1 else -1) > 0) score += 5
        } else if (volumeRatio >= 1.2) score += 5
        score = score.coerceIn(0, 100)
        val trigger = if (side == "LONG") m.dropLast(1).takeLast(5).maxOf { it.high }
            else if (side == "SHORT") m.dropLast(1).takeLast(5).minOf { it.low } else t.last
        val previousClose = m[m.lastIndex - 1].close\n        val entryTriggered = when (side) {\n            \"LONG\" -> last.close > trigger && previousClose <= trigger && volumeRatio >= 1.2\n            \"SHORT\" -> last.close < trigger && previousClose >= trigger && volumeRatio >= 1.2\n            else -> false\n        }\n        val entry = if (side == \"LONG\" || side == \"SHORT\") trigger else t.last
        val distance = atr * 1.5
        val stop = if (side == "LONG") entry - distance else if (side == "SHORT") entry + distance else 0.0
        val sign = if (side == "LONG") 1.0 else -1.0
        val t1 = if (side == "WAIT") 0.0 else entry + sign * distance * 1.5
        val t2 = if (side == "WAIT") 0.0 else entry + sign * distance * 2.5
        val warning = when {
            side == "WAIT" -> "No trade. Wait for alignment and a 15M close through the trigger with volume."
            side == "LONG" && rsi > 70 -> "RSI is overbought; do not chase. Wait for a pullback."
            side == "SHORT" && rsi < 30 -> "RSI is oversold; do not chase. Wait for a bounce."
            volumeRatio < 0.8 -> "Volume confirmation is weak. Keep this on watch until volume expands."
            else -> "Conditional only: wait for a fresh 15M close through the trigger. Check spread and funding."
        }
        val horizon = if ((bull4 || bear4) && (bull1 || bear1)) "Swing bias" else "Intraday / scalp"
        return TradeIdea(t.symbol, side, score, t.quoteVolume, t.changePct, t.last, entry, stop, t1, t2,
            rsi, volumeRatio, if (bull4) "Bullish" else if (bear4) "Bearish" else "Mixed",
            if (bull1) "Bullish" else if (bear1) "Bearish" else "Mixed",
            if (side == "WAIT") "Trend alignment watch" else if (volumeRatio >= 1.2) "Trend continuation with volume" else "Trend pullback",
            reasons, warning, horizon)
    }
}

@Composable
fun DailyTradeIdeasScreen() {
    var ideas by remember { mutableStateOf<List<TradeIdea>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Refresh to scan live Binance USDT-M perpetual markets. No API key is required.") }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var filter by remember { mutableStateOf("All") }
    val scope = rememberCoroutineScope()
    fun refresh() {
        if (loading) return
        loading = true; error = null; status = "Connecting to Binance public market data…"
        scope.launch {
            try {
                ideas = BinanceMarketScanner.scan { status = it }
                updatedAt = System.currentTimeMillis()
                status = "Scan complete. " + ideas.count { it.side != "WAIT" && it.score >= 65 } + " qualified directional ideas from " + ideas.size + " analysed pairs."
                if (ideas.isEmpty()) error = "No complete readings returned. Retry the scan."
            } catch (e: Exception) {
                error = e.message ?: "Market scan failed. Check your connection and retry."
                status = "Scan could not be completed."
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val visible = ideas.filter {
        when (filter) {
            "Scalp" -> it.horizon == "Intraday / scalp"
            "Swing" -> it.horizon == "Swing bias"
            "Long" -> it.side == "LONG"
            "Short" -> it.side == "SHORT"
            "Watch" -> it.side == "WAIT" || it.score < 65
            else -> true
        }
    }.sortedWith(compareByDescending<TradeIdea> { it.side != "WAIT" && it.score >= 65 }.thenByDescending { it.score })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("MARKET OPPORTUNITIES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Daily trade ideas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Rules-based scanner for liquid Binance USDⓈ-M USDT perpetual contracts. It ranks market conditions; it does not predict prices or place orders.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Live market scan", fontWeight = FontWeight.SemiBold)
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp))
                        Text(if (loading) "Scanning markets…" else if (updatedAt == null) "Scan markets now" else "Refresh scan")
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {\n                        Column(Modifier.weight(1f)) {\n                            Text(\"Entry & exit notifications\", fontWeight = FontWeight.SemiBold)\n                            Text(\"Background checks about every 15 minutes\", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                        }\n                        Switch(checked = alertsEnabled, onCheckedChange = { enabled ->\n                            if (!enabled) {\n                                context.getSharedPreferences(TradeIdeaNotifications.PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(\"enabled\", false).apply()\n                                TradeIdeaNotifications.cancel(context)\n                                alertsEnabled = false\n                            } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {\n                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)\n                            } else enableTradeAlerts()\n                        })\n                    }
                    updatedAt?.let { Text("Updated " + SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(it)), style = MaterialTheme.typography.labelSmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannerStat("Pairs scanned", ideas.size.toString(), Modifier.weight(1f))
                ScannerStat("Qualified", ideas.count { it.side != "WAIT" && it.score >= 65 }.toString(), Modifier.weight(1f))
                ScannerStat("Watch only", ideas.count { it.side == "WAIT" || it.score < 65 }.toString(), Modifier.weight(1f))
            }
        }
        item {
            Text("Filter results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("All", "Scalp", "Swing", "Long", "Short", "Watch").forEach {
                    FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it) })
                }
            }
        }
        if (ideas.isEmpty() && !loading) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No scan results yet", fontWeight = FontWeight.SemiBold)
                    Text("Run a scan to load current market conditions. Public Binance data is used; account credentials are not needed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(visible, key = { it.symbol }) { TradeIdeaCard(it) }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How to use these ideas", fontWeight = FontWeight.SemiBold)
                    Text("Score is a checklist score, not a probability of profit. Directional ideas require aligned 4H, 1H and 15M trends. Entry is a breakout confirmation reference, not a live order. Stop uses 1.5× 15M ATR; targets use 1.5R and 2.5R.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Risk guide: plan no more than 0.5% of equity per trade and keep leverage at or below 3×. Include fees, slippage and funding. Skip wide spreads, weak volume, stale data or unclear invalidation.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Read-only public market data only. The scanner cannot place, edit or close orders. Verify prices and contract rules on Binance before trading.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ScannerStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
private fun TradeIdeaCard(idea: TradeIdea) {
    var expanded by remember { mutableStateOf(false) }
    val qualified = idea.side != "WAIT" && idea.score >= 65
    val tint = if (!qualified) MaterialTheme.colorScheme.onSurfaceVariant else if (idea.side == "LONG") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(idea.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(idea.horizon + " · " + idea.setup, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (qualified) idea.side else "WAIT", color = tint, fontWeight = FontWeight.Bold)
                    Text("Score " + idea.score + "/100", style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Last price", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(idea.last.usd(6), fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("24h change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (idea.change >= 0) "+" else "") + idea.change.pct(), color = if (idea.change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("24h quote volume", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$" + "%,.0f".format(Locale.US, idea.volume), style = MaterialTheme.typography.bodySmall)
            }
            if (qualified) {
                HorizontalDivider()
                Text("Conditional plan · wait for a fresh 15M close", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                IdeaLevel("Entry trigger", idea.entry); IdeaLevel("Stop-loss reference", idea.stop)
                IdeaLevel("Target 1 · 1.5R", idea.target1); IdeaLevel("Target 2 · 2.5R", idea.target2)
            } else Text("No trade: trend alignment is incomplete or score is below qualification threshold.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) { Text(if (expanded) "Hide analysis" else "Why this result?") }
            if (expanded) {
                HorizontalDivider()
                Text("Trend: 4H " + idea.bias4h + " · 1H " + idea.bias1h, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("15M RSI " + "%.1f".format(Locale.US, idea.rsi) + " · volume " + "%.2f".format(Locale.US, idea.volumeRatio) + "× recent average", style = MaterialTheme.typography.bodySmall)
                idea.reasons.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                Text(idea.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun IdeaLevel(label: String, value: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.usd(6), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
