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
    val tradeId: String,
    val followedPlan: Boolean?,
    val setupTag: String,
    val emotionBefore: String,
    val notes: String,
    val reviewedAt: String
)

class TradeReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("risk_tracker_reviews", Context.MODE_PRIVATE)
    fun load(): Map<String, TradeReview> = runCatching {
        val a = JSONArray(prefs.getString("reviews_v1", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val review = TradeReview(o.getString("tradeId"),
                if (o.isNull("followedPlan")) null else o.getBoolean("followedPlan"),
                o.optString("setupTag", ""), o.optString("emotionBefore", ""),
                o.optString("notes", ""), o.optString("reviewedAt", ""))
            review.tradeId to review
        }.toMap()
    }.getOrDefault(emptyMap())
    fun save(reviews: Map<String, TradeReview>) {
        val a = JSONArray()
        reviews.values.forEach { r ->
            a.put(JSONObject().put("tradeId", r.tradeId)
                .put("followedPlan", r.followedPlan ?: JSONObject.NULL)
                .put("setupTag", r.setupTag).put("emotionBefore", r.emotionBefore)
                .put("notes", r.notes).put("reviewedAt", r.reviewedAt))
        }
        prefs.edit().putString("reviews_v1", a.toString()).apply()
    }
}

@Composable
fun TradeReviewScreen(trades: List<TradeEntry>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { TradeReviewStore(context.applicationContext) }
    var reviews by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<TradeEntry?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Trade reviews", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Review closed trades, record whether you followed your plan, and capture lessons. Review notes are stored locally.")
        }
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
                        FilterChip(selected == null, {}, label = { Text(" ") }, enabled = false)
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
