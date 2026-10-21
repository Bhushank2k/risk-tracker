package com.risktracker.app

import android.content.Intent
import android.os.Bundle
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

private val Green = Color(0xFF1B9E4B)
private val Red = Color(0xFFD33A3A)

fun Double.usd(d: Int = 2) = "$" + "%,.${d}f".format(this)
fun Double.pct(d: Int = 2) = "%.${d}f%%".format(this)
fun Double.num(d: Int = 2) = "%,.${d}f".format(this)
fun pnlColor(v: Double) = if (v > 0) Green else if (v < 0) Red else Color.Gray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        inrRate = store.inr
        setContent {
            var themeMode by remember { mutableStateOf(store.themeMode) }
            val dark = when (themeMode) { "Light" -> false; "Dark" -> true; else -> isSystemInDarkTheme() }
            val ctx = LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(store, themeMode, onThemeModeChange = { themeMode = it; store.themeMode = it }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store, themeMode: String, onThemeModeChange: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(store.loadEntries()) }
    var input by remember { mutableStateOf(store.loadInput()) }
    var syncedCount by remember { mutableIntStateOf(store.syncedCount) }
    var trades by remember { mutableStateOf(store.loadTrades()) }
    val titles = listOf("Daily P&L", "Trades", "More")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.Build, Icons.Default.Info)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Risk Tracker") }) },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = when (i) { 0 -> tab == 0; 1 -> tab == 1; else -> tab >= 2 },
                        onClick = { tab = if (i == 2) 6 else i },
                        icon = { Icon(icons[i], contentDescription = null) },
                        label = { Text(t) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> PnlScreen(entries, syncedCount, store.maxDailyLoss, store.dailyProfitTarget) {
                    entries = it
                    store.saveEntries(it)
                }
                1 -> TradeJournalScreen(trades, onAdd = { trade ->
                    trades = (trades + trade).sortedByDescending { it.date }
                    store.saveTrades(trades)
                }, onDelete = { trade ->
                    trades = trades.filterNot { it.id == trade.id }
                    store.saveTrades(trades)
                }, maxTradeRisk = store.maxTradeRisk)
                2 -> CalculatorScreen(input) {
                    input = it
                    store.saveInput(it)
                }
                3 -> LeverageScreen(input)
                4 -> SyncScreen(store, entries, syncedCount) { synced ->
                    entries = synced
                    syncedCount = synced.size
                }
                5 -> AboutScreen()
                else -> MoreScreen(store, entries, trades, themeMode, onThemeModeChange,
                    onRestore = { pair -> entries = pair.first; trades = pair.second }) { tab = it }
            }
        }
    }
}

// ---------------- Daily PNL ----------------

@Composable
fun PnlScreen(entries: List<PnlEntry>, syncedCount: Int, maxDailyLoss: Double, dailyProfitTarget: Double, onChange: (List<PnlEntry>) -> Unit) {
    val ctx = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    val stats = computeStats(entries)
    val pdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                val paint = Paint().apply { textSize = 16f; color = android.graphics.Color.BLACK }
                val canvas = page.canvas
                canvas.drawText("Risk Tracker - Daily P&L Report", 36f, 42f, paint)
                paint.textSize = 10f
                canvas.drawText("Net P&L: " + stats.net.usd() + "    Win rate: " + stats.winRate.pct(1) + "    Recorded days: " + entries.size, 36f, 66f, paint)
                canvas.drawText("Date                         Opening Balance            Daily P&L            Closing Balance       Discipline", 36f, 94f, paint)
                entries.takeLast(38).forEachIndexed { index, e -> canvas.drawText(e.date.padEnd(18) + " " + e.balance.usd().padEnd(20) + " " + e.pnl.usd().padEnd(18) + " " + e.closing.usd().padEnd(20) + " " + e.disciplineScore + "/5", 36f, (116 + index * 17).toFloat(), paint) }
                document.finishPage(page)
                ctx.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
            } catch (_: Exception) { } finally { document.close() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Current balance", style = MaterialTheme.typography.labelMedium)
                        Text(stats.currentBalance.usd(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("₹" + (stats.currentBalance * inrRate).num(0),
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Total win", stats.totalWin.usd(), Green)
                            Stat("Total loss", stats.totalLoss.usd(), Red)
                            Stat("Net", stats.net.usd(), pnlColor(stats.net))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${stats.winDays}W / ${stats.lossDays}L  •  win rate ${stats.winRate.pct(0)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (entries.lastOrNull()?.pnl?.let { it >= dailyProfitTarget } == true) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text("Daily profit target reached: " + entries.last().pnl.usd() + " of " + dailyProfitTarget.usd() + ". Protect your gains and follow your plan.",
                            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (entries.lastOrNull()?.pnl?.let { it <= -maxDailyLoss } == true) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text("Daily loss limit reached: " + entries.last().pnl.usd() + " loss meets/exceeds your " + maxDailyLoss.usd() + " limit. Consider stopping for the day.",
                            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item { BalanceChart(entries) }
            item { EquityDrawdownCard(entries) }
            item { PnlCalendar(entries) }
            item { DailyInsights(entries) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "Daily PNL")
                            putExtra(Intent.EXTRA_TEXT, entriesToCsv(entries, inrRate))
                        }
                        ctx.startActivity(Intent.createChooser(send, "Export CSV"))
                    }) { Text("Export CSV") }
                    TextButton(onClick = { pdfLauncher.launch("risk-tracker-daily-pnl.pdf") }) { Text("Export PDF") }
                }
            }
            items(entries.indices.reversed().toList()) { idx ->
                val e = entries[idx]
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("#${idx + 1}  ${e.date}", fontWeight = FontWeight.Medium)
                            Text("Bal ${e.balance.usd()}  →  ${e.closing.usd()}",
                                style = MaterialTheme.typography.bodySmall)
                            if (e.disciplineScore > 0) Text("Discipline: ${e.disciplineScore}/5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (e.pnl > 0) "+" else "") + e.pnl.usd(),
                                color = pnlColor(e.pnl), fontWeight = FontWeight.Bold)
                            Text(e.wlPercent.pct() + "  •  ₹" + (e.pnl * inrRate).num(0),
                                color = pnlColor(e.pnl), style = MaterialTheme.typography.bodySmall)
                        }
                        // Days already in the sheet are removed in the sheet itself.
                        if (syncedCount < 0 || idx >= syncedCount) {
                            IconButton(onClick = { onChange(entries.toMutableList().also { it.removeAt(idx) }) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add day") }
    }

    if (showAdd) AddEntryDialog(entries.lastOrNull(), onDismiss = { showAdd = false }) {
        onChange(entries + it)
        showAdd = false
    }
}

@Composable
fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BalanceChart(entries: List<PnlEntry>) {
    if (entries.size < 2) return
    val points = entries.map { it.closing }
    val lo = points.min()
    val hi = points.max()
    val line = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Balance over time", style = MaterialTheme.typography.labelMedium)
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
                val range = (hi - lo).takeIf { it > 0 } ?: 1.0
                val path = Path()
                points.forEachIndexed { i, v ->
                    val x = size.width * i / (points.size - 1)
                    val y = (size.height * (1 - (v - lo) / range)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(width = 4f))
                drawLine(Color.LightGray, Offset(0f, size.height), Offset(size.width, size.height))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low ${lo.usd()}", style = MaterialTheme.typography.labelSmall)
                Text("High ${hi.usd()}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun AddEntryDialog(last: PnlEntry?, onDismiss: () -> Unit, onAdd: (PnlEntry) -> Unit) {
    val today = remember { SimpleDateFormat("d/M/yyyy", Locale.getDefault()).format(Date()) }
    var date by remember { mutableStateOf(today) }
    var balance by remember { mutableStateOf(last?.closing?.let { "%.2f".format(it) } ?: "") }
    var pnl by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var discipline by remember { mutableIntStateOf(3) }
    val b = balance.toDoubleOrNull()
    val p = pnl.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true)
                OutlinedTextField(
                    balance, { balance = it }, label = { Text("Opening balance (USD)") },
                    supportingText = { Text("Change it if you deposited or withdrew") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pnl, onValueChange = { pnl = it }, label = { Text("Daily P&L (USD)") },
                        supportingText = { Text("Enter profit or loss") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedButton(onClick = { pnl = when { pnl.startsWith("-") -> pnl.removePrefix("-"); pnl.isNotBlank() -> "-$pnl"; else -> "-" } }) { Text("−", fontSize = 22.sp) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Daily notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Discipline score: $discipline / 5")
                Slider(value = discipline.toFloat(), onValueChange = { discipline = it.toInt().coerceIn(0, 5) }, valueRange = 0f..5f, steps = 4)
            }
        },
        confirmButton = {
            TextButton(enabled = b != null && p != null && date.isNotBlank(),
                onClick = { onAdd(PnlEntry(date.trim(), b!!, p!!, notes.trim(), discipline)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun EquityDrawdownCard(entries: List<PnlEntry>) {
    if (entries.isEmpty()) return
    val ordered = entries.sortedBy { reportDate(it.date) ?: Date(0) }
    var peak = ordered.first().closing
    var maxDd = 0.0
    ordered.forEach { e -> peak = maxOf(peak, e.closing); maxDd = maxOf(maxDd, peak - e.closing) }
    val currentPeak = ordered.lastOrNull()?.let { last -> ordered.takeWhile { it.date != last.date }.fold(0.0) { p, e -> maxOf(p, e.closing) }.let { maxOf(it, last.closing) } } ?: 0.0
    val current = ordered.last().closing
    val drawdown = (currentPeak - current).coerceAtLeast(0.0)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Equity & drawdown", style = MaterialTheme.typography.titleMedium)
        Text("Current balance: " + current.usd() + " · Peak-to-current drawdown: " + drawdown.usd(), color = if (drawdown > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text("Maximum historical drawdown: " + maxDd.usd(), style = MaterialTheme.typography.bodySmall)
        Text("Drawdown compares recorded closing balances with the highest prior closing balance; deposits/withdrawals may affect it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

private fun reportDate(value: String): Date? {
    listOf("d/M/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "M/d/yyyy").forEach { pattern ->
        try { return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) } catch (_: Exception) { }
    }
    return null
}

@Composable
fun PnlCalendar(entries: List<PnlEntry>) {
    if (entries.isEmpty()) return
    val parsed = entries.mapNotNull { e -> reportDate(e.date)?.let { it to e } }
    if (parsed.isEmpty()) return
    val latest = parsed.maxBy { it.first.time }.first
    val cal = Calendar.getInstance().apply { time = latest; set(Calendar.DAY_OF_MONTH, 1) }
    val monthTitle = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val startOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val byDay = parsed.associate { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.first) to it.second }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("P&L calendar · " + monthTitle, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        val cells = List(startOffset) { 0 } + (1..days).toList()
        cells.chunked(7).forEach { week -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (week + List(7 - week.size) { 0 }).forEach { day ->
                val key = if (day > 0) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { time = latest; set(Calendar.DAY_OF_MONTH, day) }.time) else ""
                val e = byDay[key]
                val bg = when { day == 0 -> MaterialTheme.colorScheme.surface; e == null -> MaterialTheme.colorScheme.surfaceVariant; e.pnl > 0 -> Color(0xFFDDF3E4); e.pnl < 0 -> Color(0xFFF8DEDE); else -> MaterialTheme.colorScheme.surfaceVariant }
                Column(Modifier.weight(1f).padding(vertical = 2.dp).background(bg, MaterialTheme.shapes.small).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (day == 0) " " else day.toString(), style = MaterialTheme.typography.labelSmall, color = Color.Black)
                    if (e != null) Text(if (e.pnl > 0) "+" + e.pnl.num(0) else e.pnl.num(0), style = MaterialTheme.typography.labelSmall, color = Color.Black, maxLines = 1)
                }
            }
        } }
        Text("Green = profit · Red = loss · Empty = no recorded result", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
fun DailyInsights(entries: List<PnlEntry>) {
    if (entries.isEmpty()) return
    val ordered = entries.sortedBy { reportDate(it.date) ?: Date(0) }
    var currentStreak = 0
    var streakSign = 0
    var bestWinStreak = 0
    var bestLossStreak = 0
    ordered.forEach { e ->
        val sign = when { e.pnl > 0 -> 1; e.pnl < 0 -> -1; else -> 0 }
        if (sign == 0) { currentStreak = 0; streakSign = 0 } else if (sign == streakSign) currentStreak++ else { currentStreak = 1; streakSign = sign }
        if (sign > 0) bestWinStreak = maxOf(bestWinStreak, currentStreak)
        if (sign < 0) bestLossStreak = maxOf(bestLossStreak, currentStreak)
    }
    val weekdayTotals = ordered.mapNotNull { e -> reportDate(e.date)?.let { Calendar.getInstance().apply { time = it }.get(Calendar.DAY_OF_WEEK) to e.pnl } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    val bestWeekday = weekdayTotals.maxByOrNull { it.value }
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Streaks & weekday insights", style = MaterialTheme.typography.titleMedium)
        Text("Current streak: " + if (streakSign == 0) "No active win/loss streak" else "$currentStreak consecutive ${if (streakSign > 0) "winning" else "losing"} days")
        Text("Longest winning streak: $bestWinStreak days · Longest losing streak: $bestLossStreak days")
        Text("Best recorded weekday: " + (bestWeekday?.let { names[it.key - 1] + " (" + it.value.usd() + ")" } ?: "Not enough data"))
        Text("Weekday totals combine all recorded years and may reflect deposits/withdrawals in balances, not just trading skill.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

// ---------------- Sync ----------------

@Composable
fun SyncScreen(store: Store, entries: List<PnlEntry>, syncedCount: Int, onSynced: (List<PnlEntry>) -> Unit) {
    var url by remember { mutableStateOf(store.webUrl) }
    var token by remember { mutableStateOf(store.token) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pending = if (syncedCount < 0) emptyList() else entries.drop(syncedCount)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Sheet sync", style = MaterialTheme.typography.titleMedium)
        Text("Syncs the Daily_PNL tab. New days you add here are uploaded; days in the sheet are downloaded.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, label = { Text("Web app URL") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("Token") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Text(
            if (syncedCount < 0) "First sync replaces the data on this phone with the sheet's data."
            else "${pending.size} new day(s) waiting to upload.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            onClick = {
                store.webUrl = url.trim()
                store.token = token.trim()
                busy = true
                status = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { SyncClient.sync(url.trim(), token.trim(), pending) }
                        store.saveEntries(r.entries)
                        store.syncedCount = r.entries.size
                        store.inr = r.inr
                        inrRate = r.inr
                        onSynced(r.entries)
                        status = "Synced. ${r.entries.size} days now match the sheet."
                    } catch (e: Exception) {
                        status = "Sync failed: ${e.message}"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "Syncing…" else "Sync now") }
        if (busy) {
            CircularProgressIndicator()
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("One-time setup", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. In your sheet: Extensions → Apps Script, paste sheet-sync/Code.gs and set TOKEN.\n" +
                "2. Deploy → New deployment → Web app, Execute as Me, access Anyone.\n" +
                "3. Paste the Web app URL and the same token above.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun MoreScreen(store: Store, entries: List<PnlEntry>, trades: List<TradeEntry>, themeMode: String, onThemeModeChange: (String) -> Unit, onRestore: (Pair<List<PnlEntry>, List<TradeEntry>>) -> Unit, onNavigate: (Int) -> Unit) {
    val context = LocalContext.current
    var dailyLimit by remember { mutableStateOf(store.maxDailyLoss.toString()) }
    var tradeLimit by remember { mutableStateOf(store.maxTradeRisk.toString()) }
    var profitTarget by remember { mutableStateOf(store.dailyProfitTarget.toString()) }
    var backupMessage by remember { mutableStateOf("") }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportBackup()) }; backupMessage = "Backup exported." } catch (e: Exception) { backupMessage = "Export failed: " + (e.message ?: "unknown error") }
    }
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Could not read file")
                onRestore(store.importBackup(raw))
                backupMessage = "Backup restored. Existing records were replaced by the selected backup."
            } catch (e: Exception) {
                backupMessage = "Restore failed: " + (e.message ?: "invalid backup")
            }
        }
    }
    val weekly = entries.filter { it.date.takeLast(4).isNotEmpty() }.takeLast(7)
    val monthKey = java.text.SimpleDateFormat("yyyy-MM", Locale.US)
    val monthly = entries.filter { e -> try { val date = parseReportDate(e.date); monthKey.format(date) == monthKey.format(Date()) } catch (_: Exception) { false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tools & settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        listOf(Triple("Position calculator", "Position size, risk and target", 2), Triple("Leverage & liquidation", "Compare leverage risk", 3), Triple("Google Sheets sync", "Sync Daily P&L only", 4), Triple("About Risk Tracker", "App details and risk disclaimer", 5)).forEach { (title, subtitle, destination) ->
            Card(onClick = { onNavigate(destination) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) } }
        }
        HorizontalDivider()
        Text("Weekly report · last 7 recorded days", style = MaterialTheme.typography.titleMedium)
        ReportSummary(weekly)
        Text("Monthly report · current month", style = MaterialTheme.typography.titleMedium)
        ReportSummary(monthly)
        HorizontalDivider()
        Text("Risk limits", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(dailyLimit, { dailyLimit = it }, label = { Text("Maximum daily loss (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(tradeLimit, { tradeLimit = it }, label = { Text("Maximum planned risk per trade (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(profitTarget, { profitTarget = it }, label = { Text("Daily profit target (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Button(onClick = { val d = dailyLimit.toDoubleOrNull(); val t = tradeLimit.toDoubleOrNull(); val p = profitTarget.toDoubleOrNull(); if (d != null && t != null && p != null && d > 0 && t > 0 && p > 0) { store.maxDailyLoss = d; store.maxTradeRisk = t; store.dailyProfitTarget = p; backupMessage = "Daily targets and risk limits saved." } else backupMessage = "Enter positive numbers for all targets and limits." }, modifier = Modifier.fillMaxWidth()) { Text("Save targets & risk limits") }
        Text("Limits trigger warnings; they do not block exchange activity or execute trades.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { exportPicker.launch("risk-tracker-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export all records (JSON)") }
        OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
        if (backupMessage.isNotBlank()) Text(backupMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("System", "Light", "Dark").forEachIndexed { index, mode -> SegmentedButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(mode) } } }
        Text("Daily P&L is one net result per day and remains separate from individual trades. Weekly and monthly reports use Daily P&L records. Trade journal statistics and filters use closed trades only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun parseReportDate(value: String): Date {
    val formats = listOf("yyyy-MM-dd", "d/M/yyyy", "M/d/yyyy", "dd/MM/yyyy")
    formats.forEach { pattern -> try { return java.text.SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)!! } catch (_: Exception) {} }
    throw IllegalArgumentException("Unrecognized date: " + value)
}

@Composable
private fun ReportSummary(rows: List<PnlEntry>) {
    val net = rows.sumOf { it.pnl }
    val decisive = rows.filter { it.pnl != 0.0 }
    val wins = decisive.count { it.pnl > 0 }
    val best = rows.maxByOrNull { it.pnl }
    val worst = rows.minByOrNull { it.pnl }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Net P&L: " + net.usd(), color = pnlColor(net), fontWeight = FontWeight.Bold)
        Text("Win rate: " + (if (decisive.isEmpty()) 0.0 else wins * 100.0 / decisive.size).pct(1))
        Text("Best day: " + (best?.date ?: "—") + " · " + (best?.pnl?.usd() ?: "—"))
        Text("Worst day: " + (worst?.date ?: "—") + " · " + (worst?.pnl?.usd() ?: "—"))
        Text("Return on opening balance: " + (if ((rows.firstOrNull()?.balance ?: 0.0) > 0) (net / rows.first().balance * 100).pct(2) else "—"))
    } }
}
// ---------------- About ----------------

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Risk Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Crypto futures trading tracker: daily PNL, position sizing and leverage risk in one place.",
            style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutRow("Developer", "Bhushank2k")
                AboutRow("Version", version)
            }
        }
        Warn("Not financial advice. Futures trading carries a high risk of loss. Verify every number against your exchange.")
    }
}

@Composable
fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- Calculator ----------------

@Composable
fun CalculatorScreen(input: CalcInput, onChange: (CalcInput) -> Unit) {
    // Text state is kept as strings so partially typed numbers (e.g. "2.") are not rewritten.
    var balance by remember { mutableStateOf(input.balance.toString()) }
    var risk by remember { mutableStateOf(input.riskPercent.toString()) }
    var entry by remember { mutableStateOf(input.entry.toString()) }
    var sl by remember { mutableStateOf(input.stopLoss.toString()) }
    var tp by remember { mutableStateOf(input.target.toString()) }
    var lev by remember { mutableStateOf(input.leverage.toString()) }
    var mmr by remember { mutableStateOf(input.mmrPercent.toString()) }

    val parsed = CalcInput(
        balance.toDoubleOrNull() ?: 0.0, risk.toDoubleOrNull() ?: 0.0, entry.toDoubleOrNull() ?: 0.0,
        sl.toDoubleOrNull() ?: 0.0, tp.toDoubleOrNull() ?: 0.0, lev.toDoubleOrNull() ?: 0.0,
        mmr.toDoubleOrNull() ?: 0.0
    )
    LaunchedEffect(parsed) { if (parsed != input) onChange(parsed) }

    val long = calcSide(parsed, true)
    val short = calcSide(parsed, false)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account & risk settings", style = MaterialTheme.typography.titleMedium)
        Field("Account balance (USD)", balance) { balance = it }
        Field("Risk per trade (%)", risk) { risk = it }
        Field("Entry price (USD)", entry) { entry = it }
        Field("Stop loss price (USD)", sl) { sl = it }
        Field("Target price (USD)", tp) { tp = it }
        Field("Leverage (x)", lev) { lev = it }
        Field("Maintenance margin rate (%)", mmr) { mmr = it }

        Spacer(Modifier.height(8.dp))
        Text("Position metrics", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                ResultRow("", "Long", "Short", header = true)
                ResultRow("Max risk", long.riskAmount.usd(), short.riskAmount.usd())
                ResultRow("SL distance", long.slDistancePercent.pct(), short.slDistancePercent.pct())
                ResultRow("Position size", long.positionSize.usd(), short.positionSize.usd())
                ResultRow("Tokens", long.tokens.num(4), short.tokens.num(4))
                ResultRow("Margin", long.margin.usd(), short.margin.usd())
                ResultRow("Liq. (simple)", long.liqSimple.usd(), short.liqSimple.usd())
                ResultRow("Liq. (with MMR)", long.liqExact.usd(), short.liqExact.usd())
                ResultRow("Target TP", long.tpPercent.pct(), short.tpPercent.pct())
                ResultRow("Profit", long.profit.usd(), short.profit.usd())
                ResultRow("ROI", long.roiPercent.pct(0), short.roiPercent.pct(0))
                ResultRow("R:R", long.rr.num(2), short.rr.num(2))
            }
        }
        long.message?.let { Warn("Long: $it") }
        short.message?.let { Warn("Short: $it") }
        Text("Warnings mean that side's numbers don't describe a valid trade for your entry, stop and target.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
fun ResultRow(label: String, a: String, b: String, header: Boolean = false) {
    val w = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1.3f), fontWeight = FontWeight.Medium)
        Text(a, Modifier.weight(1f), fontWeight = w, color = if (header) Green else Color.Unspecified)
        Text(b, Modifier.weight(1f), fontWeight = w, color = if (header) Red else Color.Unspecified)
    }
}

@Composable
fun Warn(text: String) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(10.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- Leverage matrix ----------------

@Composable
fun LeverageScreen(input: CalcInput) {
    val rows = leverageMatrix(input.entry)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Leverage risk & liquidation boundaries", style = MaterialTheme.typography.titleMedium)
        Text("Based on the entry price from the Calculator tab: ${input.entry.usd()}",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Lev", Modifier.weight(0.6f), fontWeight = FontWeight.Bold)
                    Text("Max move", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("Long liq", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Green)
                    Text("Short liq", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Red)
                }
                rows.forEach { r ->
                    val current = r.lev.toDouble() == input.leverage
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(vertical = 6.dp)
                    ) {
                        Text("${r.lev}x", Modifier.weight(0.6f), fontWeight = FontWeight.Medium)
                        Text(r.movePercent.pct(), Modifier.weight(1f))
                        Text(r.longLiq.usd(), Modifier.weight(1f))
                        Text(r.shortLiq.usd(), Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Your current leverage (${input.leverage.num(0)}x) is highlighted when it matches a row.",
            style = MaterialTheme.typography.bodySmall)
    }
}
