package com.risktracker.app

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material.icons.filled.List
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
import java.util.Locale

private val Green = Color(0xFF1B9E4B)
private val Red = Color(0xFFD33A3A)

fun Double.usd(d: Int = 2) = "$" + "%,.${d}f".format(this)
fun Double.pct(d: Int = 2) = "%.${d}f%%".format(this)
fun Double.num(d: Int = 2) = "%,.${d}f".format(this)
fun pnlColor(v: Double) = if (v > 0) Green else if (v < 0) Red else Color.Gray

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        inrRate = store.inr
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialExpressiveTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(store) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store) {
    var tab by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(store.loadEntries()) }
    var input by remember { mutableStateOf(store.loadInput()) }
    var syncedCount by remember { mutableIntStateOf(store.syncedCount) }
    var trades by remember { mutableStateOf(store.loadTrades()) }
    val titles = listOf("Daily PNL", "Journal", "Calculator", "Leverage", "Sync", "About")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.List, Icons.Default.Build, Icons.Default.Warning,
        Icons.Default.Refresh, Icons.Default.Info)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Risk Tracker") }) },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = null) },
                        label = { Text(t) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> PnlScreen(entries, syncedCount) {
                    entries = it
                    store.saveEntries(it)
                }
                1 -> TradeJournalScreen(trades, onAdd = { trade ->
                    trades = (trades + trade).sortedByDescending { it.date }
                    store.saveTrades(trades)
                }, onDelete = { trade ->
                    trades = trades.filterNot { it.id == trade.id }
                    store.saveTrades(trades)
                })
                2 -> CalculatorScreen(input) {
                    input = it
                    store.saveInput(it)
                }
                3 -> LeverageScreen(input)
                4 -> SyncScreen(store, entries, syncedCount) { synced ->
                    entries = synced
                    syncedCount = synced.size
                }
                else -> AboutScreen()
            }
        }
    }
}

// ---------------- Daily PNL ----------------

@Composable
fun PnlScreen(entries: List<PnlEntry>, syncedCount: Int, onChange: (List<PnlEntry>) -> Unit) {
    val ctx = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    val stats = computeStats(entries)

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
            item { BalanceChart(entries) }
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
                OutlinedTextField(
                    pnl, { pnl = it }, label = { Text("Daily PNL (USD, negative for loss)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(enabled = b != null && p != null && date.isNotBlank(),
                onClick = { onAdd(PnlEntry(date.trim(), b!!, p!!)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------- Sync ----------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            LoadingIndicator()
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
