package com.risktracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TradeReview(
    val tradeId: String, val followedPlan: Boolean?, val setupTag: String,
    val emotionBefore: String, val notes: String, val reviewedAt: String
)

class TradeReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("risk_tracker_reviews", Context.MODE_PRIVATE)
    fun exportJson(): String = prefs.getString("reviews_v1", "[]") ?: "[]"
    fun importJson(raw: String) { val parsed = JSONArray(raw); prefs.edit().putString("reviews_v1", parsed.toString()).apply() }
    fun load(): Map<String, TradeReview> = runCatching {
        val a = JSONArray(prefs.getString("reviews_v1", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val r = TradeReview(o.getString("tradeId"), if (o.isNull("followedPlan")) null else o.getBoolean("followedPlan"),
                o.optString("setupTag", ""), o.optString("emotionBefore", ""), o.optString("notes", ""), o.optString("reviewedAt", ""))
            r.tradeId to r
        }.toMap()
    }.getOrDefault(emptyMap())
    fun save(reviews: Map<String, TradeReview>) {
        val a = JSONArray()
        reviews.values.forEach { r -> a.put(JSONObject().put("tradeId", r.tradeId)
            .put("followedPlan", r.followedPlan ?: JSONObject.NULL).put("setupTag", r.setupTag)
            .put("emotionBefore", r.emotionBefore).put("notes", r.notes).put("reviewedAt", r.reviewedAt)) }
        prefs.edit().putString("reviews_v1", a.toString()).apply()
    }
}

private data class BinanceReviewGroup(
    val market: String, val symbol: String, val fills: Int, val realizedPnl: Double,
    val realizedCount: Int, val quoteVolume: Double, val fees: Map<String, Double>
)

private fun binanceReviewGroups(rows: List<BinanceJournalFill>): List<BinanceReviewGroup> =
    rows.groupBy { it.market to it.symbol }.map { (key, fills) ->
        val realized = fills.mapNotNull { it.realizedPnl?.takeIf { value -> value.isFinite() } }
        BinanceReviewGroup(key.first, key.second, fills.size, realized.sum(), realized.size,
            fills.sumOf { if (it.quoteAmount.isFinite()) kotlin.math.abs(it.quoteAmount) else 0.0 },
            fills.filter { it.fee.isFinite() && it.fee != 0.0 }.groupBy { it.feeAsset.ifBlank { "Unknown" } }
                .mapValues { entry -> entry.value.sumOf { it.fee } })
    }.sortedByDescending { it.realizedPnl }

@Composable
fun TradeReviewScreen(
    trades: List<TradeEntry>,
    exchangeFills: List<BinanceJournalFill> = emptyList(),
    syncStatus: BinanceJournalSyncStatus = BinanceJournalSyncStatus(),
    onSyncExchange: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { TradeReviewStore(context.applicationContext) }
    var reviews by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<TradeEntry?>(null) }
    var market by remember { mutableStateOf("All") }
    val fills = exchangeFills.filter { market == "All" || it.market == market }
    val groups = remember(fills) { binanceReviewGroups(fills) }
    val futures = fills.filter { it.market == "USDⓈ-M Futures" }
    val realized = futures.mapNotNull { it.realizedPnl?.takeIf { value -> value.isFinite() } }
    val fees = futures.filter { it.fee.isFinite() && it.fee != 0.0 }
        .groupBy { it.feeAsset.ifBlank { "Unknown" } }.mapValues { entry -> entry.value.sumOf { it.fee } }
    val reviewed = trades.count { reviews.containsKey(it.id) }
    val planAnswers = trades.mapNotNull { reviews[it.id]?.followedPlan }
    val planRate = if (planAnswers.isEmpty()) null else planAnswers.count { it }.toDouble() * 100.0 / planAnswers.size

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Trade reviews", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Manual lessons plus read-only Binance execution analysis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Binance review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onSyncExchange) { Text("Sync now") }
                    }
                    Text(syncStatus.state, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(syncStatus.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(exchangeFills.size.toString() + " imported fills · " + syncStatus.symbolsAttempted + " symbols checked · " + syncStatus.symbolsFailed + " symbol errors",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("All", "Spot", "USDⓈ-M Futures").forEach { value ->
                            FilterChip(selected = market == value, onClick = { market = value }, label = { Text(value) })
                        }
                    }
                    if (market != "Spot") {
                        Text("Futures realized P&L: " + if (realized.isEmpty()) "Unavailable" else realized.sum().usd(),
                            fontWeight = FontWeight.SemiBold,
                            color = if (realized.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        Text(realized.size.toString() + " fills with realized P&L · " + futures.size + " Futures fills",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (fees.isNotEmpty()) Text("Fees: " + fees.entries.joinToString(" · ") { it.value.num(4) + " " + it.key }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (market != "USDⓈ-M Futures") {
                        val spot = fills.filter { it.market == "Spot" }
                        Text("Spot activity: " + spot.size + " fills · quote turnover " +
                            spot.sumOf { if (it.quoteAmount.isFinite()) kotlin.math.abs(it.quoteAmount) else 0.0 }.usd(),
                            style = MaterialTheme.typography.bodySmall)
                        val spotFees = spot.filter { it.fee.isFinite() && it.fee != 0.0 }
                            .groupBy { it.feeAsset.ifBlank { "Unknown" } }.mapValues { entry -> entry.value.sumOf { it.fee } }
                        if (spotFees.isNotEmpty()) Text("Spot fees: " + spotFees.entries.joinToString(" · ") { it.value.num(4) + " " + it.key }, style = MaterialTheme.typography.bodySmall)
                        Text("Spot fills are activity, not closed-trade P&L. Fees stay in their original assets; no conversion is assumed.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (groups.isNotEmpty()) {
            item { Text("Symbol breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(groups, key = { it.market + ":" + it.symbol }) { group ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(group.symbol, fontWeight = FontWeight.Bold)
                            Text(group.market, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (group.market == "USDⓈ-M Futures") {
                            Text(if (group.realizedCount == 0) "Realized P&L unavailable" else "Realized P&L: " + group.realizedPnl.usd(),
                                color = if (group.realizedCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            Text(group.realizedCount.toString() + "/" + group.fills + " fills include realized P&L", style = MaterialTheme.typography.bodySmall)
                        } else Text("Spot quote turnover: " + group.quoteVolume.usd() + " · " + group.fills + " fills", style = MaterialTheme.typography.bodySmall)
                        if (group.fees.isNotEmpty()) Text("Fees: " + group.fees.entries.joinToString(" · ") { it.value.num(4) + " " + it.key }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else item {
            Text("No Binance fills for this filter yet. Connect a read-only API key in Binance Sync, then sync or import history.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Your review habits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(reviewed.toString() + " of " + trades.size + " manual trades reviewed")
                    Text("Plan-follow rate: " + (planRate?.pct(0) ?: "Not enough reviews"))
                    Text("Only explicit Yes/No answers are counted.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Manual journal reviews", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (trades.isEmpty()) item { Text("Add a closed trade in the Journal before reviewing it.") }
        items(trades, key = { it.id }) { trade ->
            val review = reviews[trade.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(trade.symbol + " · " + trade.side + " · " + trade.date, fontWeight = FontWeight.Bold)
                    Text("P&L estimate: " + trade.pnl.usd())
                    Text(if (review == null) "Not reviewed" else "Reviewed " + review.reviewedAt)
                    if (review != null) {
                        Text("Plan followed: " + (review.followedPlan?.let { if (it) "Yes" else "No" } ?: "Not recorded"))
                        if (review.notes.isNotBlank()) Text(review.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { selected = trade }) { Text(if (review == null) "Review trade" else "Edit review") }
                }
            }
        }
    }
    selected?.let { trade ->
        val existing = reviews[trade.id]
        var followed by remember(trade.id) { mutableStateOf(existing?.followedPlan) }
        var setup by remember(trade.id) { mutableStateOf(existing?.setupTag ?: trade.strategy) }
        var emotion by remember(trade.id) { mutableStateOf(existing?.emotionBefore ?: "") }
        var notes by remember(trade.id) { mutableStateOf(existing?.notes ?: "") }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Review " + trade.symbol) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trade result: " + trade.pnl.usd())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = followed == true, onClick = { followed = true }, label = { Text("Followed plan") })
                        FilterChip(selected = followed == false, onClick = { followed = false }, label = { Text("Broke plan") })
                    }
                    OutlinedTextField(setup, { setup = it }, label = { Text("Setup / strategy") }, singleLine = true)
                    OutlinedTextField(emotion, { emotion = it }, label = { Text("State before trade (optional)") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it }, label = { Text("Lesson learned") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val review = TradeReview(trade.id, followed, setup.trim(), emotion.trim(), notes.trim(),
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                    reviews = reviews + (trade.id to review)
                    store.save(reviews)
                    selected = null
                }) { Text("Save review") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }
        )
    }
}
