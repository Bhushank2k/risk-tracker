package com.risktracker.app

import android.content.Intent
import android.os.Bundle
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val scheme = if (dark) {
                darkColorScheme(
                    primary = Color(0xFF7DE2B8),
                    onPrimary = Color(0xFF06271A),
                    secondary = Color(0xFF91C9FF),
                    background = Color(0xFF0B1110),
                    surface = Color(0xFF111A18),
                    surfaceVariant = Color(0xFF202B28),
                    primaryContainer = Color(0xFF183D2E),
                    onPrimaryContainer = Color(0xFFD6F8E7),
                    error = Color(0xFFFF7B78)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF087F5B),
                    onPrimary = Color.White,
                    secondary = Color(0xFF285F8F),
                    background = Color(0xFFF5F8F6),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFE8EFEB),
                    primaryContainer = Color(0xFFD6F8E7),
                    onPrimaryContainer = Color(0xFF123D2C),
                    error = Color(0xFFB3261E)
                )
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
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var importedPnl by remember { mutableStateOf(BinanceSyncData.load(context)) }
    var entries by remember { mutableStateOf(store.loadEntries()) }
    var input by remember { mutableStateOf(store.loadInput()) }
    var syncedCount by remember { mutableIntStateOf(store.syncedCount) }
    var trades by remember { mutableStateOf(store.loadTrades()) }
    val todayPnlDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val todayReconciledPnl = remember(entries, importedPnl, trades, todayPnlDate) {
        reconcileDailyPnl(entries, importedPnl, trades).firstOrNull { it.date == todayPnlDate }
    }
    var exchangeFills by remember { mutableStateOf(BinanceAutoJournalStore.load(context)) }
    val appScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (BinanceCredentialStore(context).read() != null) {
            BinanceJournalWorker.schedule(context)
            runCatching {
                withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) }
                exchangeFills = BinanceAutoJournalStore.load(context)
            }
        }
    }
    val titles = listOf("Overview", "Journal", "Insights", "Tools")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.Build, Icons.Default.Info, Icons.Default.Menu)
    // Keep the bottom bar focused on frequent destinations; expose every specialist screen here.
    // Destination indices map to the existing screen router below.
    val menuGroups = listOf(
        "OVERVIEW & JOURNAL" to listOf(
            "Overview" to 0,
            "Trade journal" to 1
        ),
        "ANALYTICS & REVIEWS" to listOf(
            "Performance analytics" to 8,
            "Behavioral insights" to 12,
            "Post-trade reviews" to 14,
            "Ledger audit" to 15
        ),
        "RISK WORKSPACE" to listOf(
            "Position size calculator" to 2,
            "Leverage reference" to 3,
            "Trade Risk Gate" to 11,
            "Target planner" to 10,
            "Risk alerts" to 19,
            "Risk Scenario Lab" to 13
        ),
        "EXCHANGE & DATA" to listOf(
            "Binance sync" to 6,
            "Sync health" to 18,
            "Binance statements" to 17,
            "Live trades (read-only)" to 16,
            "CSV / Sheets sync" to 4,
            "Crypto tax report" to 9
        ),
        "APP" to listOf(
            "Tools & settings" to 7,
            "About Risk Tracker" to 5
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Risk Tracker", fontWeight = FontWeight.SemiBold)
                        Text("Trade with a plan", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(24.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        tonalElevation = 8.dp,
                        shadowElevation = 16.dp,
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(24.dp)
                        )
                    ) {
                        menuGroups.forEachIndexed { groupIndex, (group, items) ->
                            if (groupIndex > 0) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                            Text(
                                group,
                                modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            items.forEach { (label, destination) ->
                                DropdownMenuItem(
                                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { menuExpanded = false; tab = destination },
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                },

            )
        },
        bottomBar = {
            val navShape = RoundedCornerShape(26.dp)
            NavigationBar(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clip(navShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        navShape
                    ),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                tonalElevation = 0.dp,
            ) {
                titles.forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = when (i) { 0 -> tab == 0; 1 -> tab == 1; 2 -> tab == 8; else -> tab !in setOf(0, 1, 8) },
                        onClick = { tab = when (i) { 0 -> 0; 1 -> 1; 2 -> 8; else -> 7 } },
                        icon = { Icon(icons[i], contentDescription = null) },
                        label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> PnlScreen(entries, syncedCount, store.maxDailyLoss, importedPnl, trades, onNavigate = { tab = it }, onImportedPnlChange = { importedPnl = it }) {
                    entries = it
                    store.saveEntries(it)
                }
                1 -> TradeJournalScreen(trades, onAdd = { trade ->
                    trades = (trades + trade).sortedByDescending { it.date }
                    store.saveTrades(trades)
                }, onUpdate = { updated ->
                    trades = trades.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.date }
                    store.saveTrades(trades)
                }, onDelete = { trade ->
                    trades = trades.filterNot { it.id == trade.id }
                    store.saveTrades(trades)
                }, maxTradeRisk = store.maxTradeRisk, exchangeFills = exchangeFills, onSyncExchange = {
                    if (BinanceCredentialStore(context).read() != null) {
                        appScope.launch {
                            runCatching { withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) } }
                            exchangeFills = BinanceAutoJournalStore.load(context)
                        }
                    }
                })
                2 -> CombinedTradingCalculatorScreen(input) {
                    input = it
                    store.saveInput(it)
                }
                3 -> LeverageScreen(input)
                4 -> SyncScreen(store, entries, syncedCount) { synced ->
                    entries = synced
                    syncedCount = synced.size
                }
                5 -> AboutScreen()
                6 -> BinanceSyncScreen(onSynced = { importedPnl = BinanceSyncData.load(context) }, onJournalImported = { exchangeFills = BinanceAutoJournalStore.load(context) })
                8 -> InsightsScreen(entries, trades, importedPnl, onNavigate = { tab = it })
                9 -> CryptoTaxReportScreen()
                10 -> TargetScreen()
                11 -> TradeRiskGateScreen(TradeRiskSettings(
                    maxTradeRisk = store.maxTradeRisk,
                    maxDailyLoss = store.maxDailyLoss,
                    dailyPnl = todayReconciledPnl?.selectedPnl ?: 0.0,
                    maxTradesPerDay = 10,
                    tradesTakenToday = trades.count { canonicalPnlDate(it.date) == todayPnlDate },
                    dailyPnlKnown = todayReconciledPnl != null
                ))
                12 -> BehavioralInsightsScreen(trades)
                13 -> RiskScenarioLabScreen()
                14 -> TradeReviewScreen(trades)
                15 -> LedgerAuditScreen()
                16 -> LiveTradesScreen()
                17 -> BinanceStatementsScreen(onBack = { tab = 6 })
                18 -> BinanceSyncHealthScreen()
                19 -> RiskAlertsScreen(entries, trades, todayReconciledPnl?.selectedPnl, store.maxDailyLoss)
                else -> MoreScreen(store, entries, trades, themeMode, onThemeModeChange,
                    onRestore = { pair ->
                        entries = pair.first
                        trades = pair.second
                        importedPnl = BinanceSyncData.load(context)
                    }) { tab = it }
            }
        }
    }
}

// ---------------- Binance sync health ----------------

@Composable
fun BinanceSyncHealthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(BinanceAutoJournalStore.syncStatus(context)) }
    var fillCount by remember { mutableIntStateOf(BinanceAutoJournalStore.load(context).size) }
    var loading by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }

    fun timeLabel(value: Long): String =
        if (value <= 0L) "Not recorded" else
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))

    fun syncNow() {
        if (loading) return
        if (BinanceCredentialStore(context).read() == null) {
            status = BinanceAutoJournalStore.syncStatus(context)
            operationError = "Binance is not connected. Open Binance sync and add a read-only API key."
            return
        }
        loading = true
        operationError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) }
                status = BinanceAutoJournalStore.syncStatus(context)
                fillCount = BinanceAutoJournalStore.load(context).size
            } catch (e: Exception) {
                operationError = e.message ?: "Sync failed unexpectedly. Check connectivity and API permissions."
                status = BinanceAutoJournalStore.syncStatus(context)
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sync health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Monitor automatic Binance execution-history collection. This screen reports sync attempts; it cannot prove that the exchange has returned every historical trade.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (status.state.startsWith("Up to date")) Icons.Default.Refresh else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (status.state.startsWith("Up to date")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(status.state, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { syncNow() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (loading) "Syncing…" else "Sync now")
                    }
                    operationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Latest attempt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last attempt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(timeLabel(status.lastAttempt), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last fully successful sync", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(timeLabel(status.lastSuccess), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Saved executions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fillCount.toString(), fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Records fetched last attempt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.fillsFetched.toString())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("New records added", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.fillsInserted.toString())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Symbols attempted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.symbolsAttempted.toString())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Symbol requests failed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.symbolsFailed.toString(), color = if (status.symbolsFailed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Coverage limits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("The automatic collector checks a rolling seven-day window and a limited set of discovered or previously known symbols. A successful sync does not mean your entire account history is complete.", style = MaterialTheme.typography.bodyMedium)
                    Text("Window start: ${timeLabel(status.windowStart)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Window end: ${timeLabel(status.windowEnd)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("For older records or closed symbols that were never discovered, use Binance historical exports or CSV import and reconcile totals before relying on long-term analytics.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------- Live trades ----------------

@Composable
fun LiveTradesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liveTrades by remember { mutableStateOf<List<BinanceLiveTrade>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("Connect Binance with read-only API credentials, then refresh to load open positions and orders.") }

    fun refresh() {
        val credentials = BinanceCredentialStore(context).read()
        if (credentials == null) {
            status = "Binance is not connected. Open Binance trade history and save read-only API credentials first."
            return
        }
        loading = true
        status = "Loading open positions and orders…"
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    BinanceApi(credentials.first, credentials.second).liveTrades()
                }
                liveTrades = rows
                lastUpdated = System.currentTimeMillis()
                status = if (rows.isEmpty()) "No open Futures positions or Spot orders found." else "Loaded " + rows.size + " open positions/orders."
            } catch (e: Exception) {
                status = e.message ?: "Could not load live trades. Check API permissions and connection."
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live trades", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Read-only snapshot from Binance: open USDⓈ-M Futures positions and pending Spot orders. This does not place, edit, or close orders.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (loading) "Refreshing…" else "Refresh live trades")
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lastUpdated?.let {
                        Text("Updated " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (liveTrades.isNotEmpty()) {
            item {
                val futuresPositions = liveTrades.filter { it.market == "USDⓈ-M Futures" }
                val spotOrders = liveTrades.filter { it.market == "Spot order" }
                val unrealizedTotal = futuresPositions.sumOf { it.unrealizedPnl }
                val futuresNotional = futuresPositions.sumOf { it.quantity * it.markPrice }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("LIVE ACCOUNT SUMMARY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Open Futures positions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(futuresPositions.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Pending Spot orders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(spotOrders.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total unrealized Futures P&L", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text((if (unrealizedTotal > 0) "+" else "") + unrealizedTotal.usd(), color = pnlColor(unrealizedTotal), fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Approx. open Futures notional", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(futuresNotional.usd(), fontWeight = FontWeight.SemiBold)
                        }
                        Text("Notional is estimated as absolute position quantity × mark price; it is exposure, not account equity or margin used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (liveTrades.isEmpty() && !loading) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nothing open yet", fontWeight = FontWeight.SemiBold)
                        Text("After connecting Binance, tap Refresh. Only currently open Futures positions and unfilled Spot orders appear here; completed trades remain in trade history.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(liveTrades, key = { it.id }) { trade ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trade.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(trade.market, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(trade.side, color = if (trade.side == "LONG" || trade.side == "BUY") Green else Red, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Quantity", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(trade.quantity.num(6))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (trade.market == "Spot order") "Limit price" else "Entry price", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(trade.entryPrice.usd(4))
                    }
                    if (trade.market == "USDⓈ-M Futures") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Mark price", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trade.markPrice.usd(4))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Unrealized P&L", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trade.unrealizedPnl.usd(), color = pnlColor(trade.unrealizedPnl), fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Leverage", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trade.leverage.toString() + "×")
                        }
                        if (trade.liquidationPrice > 0.0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Liquidation price", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(trade.liquidationPrice.usd(4), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Order status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trade.status)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Daily PNL ----------------

@Composable
fun PnlScreen(entries: List<PnlEntry>, syncedCount: Int, maxDailyLoss: Double, importedPnl: List<BinanceDailyResult> = emptyList(), trades: List<TradeEntry> = emptyList(), onNavigate: (Int) -> Unit = {}, onImportedPnlChange: (List<BinanceDailyResult>) -> Unit = {}, onChange: (List<PnlEntry>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var liveBinanceTrades by remember { mutableStateOf<List<BinanceActivityItem>>(emptyList()) }
    var liveBinanceBalance by remember { mutableStateOf<BinancePortfolioBalance?>(null) }
    var liveBinanceFuturesBalance by remember { mutableStateOf<BinanceFuturesAccountBalance?>(null) }
    var liveBinanceLoading by remember { mutableStateOf(false) }
    var liveBinanceLastUpdated by remember { mutableStateOf<Long?>(null) }
    var liveBinanceStatus by remember { mutableStateOf("Connect Binance in Binance trade history to load live fills.") }
    var journalSyncStatus by remember { mutableStateOf(BinanceAutoJournalStore.syncStatus(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    fun refreshLiveAccount() {
        if (liveBinanceLoading) return
        val credentials = BinanceCredentialStore(ctx).read()
        if (credentials == null) {
            liveBinanceStatus = "Connect Binance to load account equity."
            return
        }
        liveBinanceLoading = true
        liveBinanceStatus = "Refreshing Binance balances…"
        scope.launch {
            try {
                val api = BinanceApi(credentials.first, credentials.second)
                val balances = withContext(Dispatchers.IO) {
                    api.spotPortfolioBalance() to api.futuresAccountBalance()
                }
                liveBinanceBalance = balances.first
                liveBinanceFuturesBalance = balances.second
                liveBinanceLastUpdated = System.currentTimeMillis()
                liveBinanceStatus = if (balances.first.unpricedAssets > 0) {
                    "Updated. ${balances.first.unpricedAssets} Spot asset(s) could not be priced and are excluded from the estimate."
                } else {
                    "Balances updated from Binance."
                }
            } catch (e: Exception) {
                liveBinanceStatus = e.message ?: "Could not refresh Binance balances. Check connection and read-only API permissions."
            } finally {
                liveBinanceLoading = false
            }
        }
    }
    LaunchedEffect(Unit) {
        journalSyncStatus = BinanceAutoJournalStore.syncStatus(ctx)
        refreshLiveAccount()
    }
    var dashboardRange by remember { mutableStateOf("All") }
    val stats = computeStats(entries)
    val livePortfolioBalance = if (liveBinanceBalance != null && liveBinanceFuturesBalance != null)
        liveBinanceBalance!!.valueUsdt + liveBinanceFuturesBalance!!.totalEquity else null
    // Reconcile by normalized calendar date and never add overlapping sources together.
    val reconciledDailyPnl = reconcileDailyPnl(entries, importedPnl, trades)
    val manualByDate = entries.mapNotNull { row -> canonicalPnlDate(row.date)?.let { it to row } }.toMap()
    val importedByDate = importedPnl.mapNotNull { row -> canonicalPnlDate(row.date)?.let { it to row } }.toMap()
    val combinedDailyPnl = reconciledDailyPnl.associate { it.date to it.selectedPnl }
    val combinedNetPnl = combinedDailyPnl.values.sum()
    val latestPnlDate = combinedDailyPnl.keys.maxOrNull().orEmpty().ifBlank { "No recorded entries" }
    val latestRecordedPnl = combinedDailyPnl[latestPnlDate] ?: 0.0
    val overlapRows = reconciledDailyPnl.filter { it.discrepancy != null }
    val mismatchRows = overlapRows.filter { kotlin.math.abs(it.discrepancy ?: 0.0) > 0.01 }
    val totalOverlapDifference = overlapRows.sumOf { kotlin.math.abs(it.discrepancy ?: 0.0) }
    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val todayEntry = pnlEntryForDate(entries)
    val todayReconciled = reconciledDailyPnl.firstOrNull { it.date == todayDate }
    val todayPnl = todayReconciled?.selectedPnl ?: 0.0
    val chronologicalEntries = entries.sortedBy { reportDate(it.date)?.time ?: Long.MAX_VALUE }
    val initialBalance = chronologicalEntries.firstOrNull()?.balance ?: 0.0
    val latestLedgerEntry = chronologicalEntries.lastOrNull()
    // Only show a daily target when that date has an explicitly recorded opening balance.
    // Carrying yesterday's calculated close forward can disguise missing exchange snapshots.
    val tableDailyRows = reconciledDailyPnl.map { day ->
        val manual = manualByDate[day.date]
        Triple(day, manual?.balance ?: 0.0, manual)
    }
    val todayOpeningBalance = todayEntry?.balance ?: 0.0
    val todayTarget = (todayOpeningBalance * 0.02).coerceAtLeast(0.0)
    val todayTargetComplete = todayOpeningBalance > 0.0 && todayReconciled != null && todayPnl >= todayTarget
    val lossLimitUsed = if (maxDailyLoss > 0.0) (-todayPnl / maxDailyLoss).coerceIn(0.0, 1.0).toFloat() else 0f
    val pdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val document = PdfDocument()
            try {
                reconciledDailyPnl.chunked(40).ifEmpty { listOf(emptyList()) }.forEachIndexed { pageIndex, rows ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                    val paint = Paint().apply { textSize = 16f; color = android.graphics.Color.BLACK }
                    val canvas = page.canvas
                    canvas.drawText("Risk Tracker - Reconciled Daily P&L", 36f, 42f, paint)
                    paint.textSize = 10f
                    canvas.drawText("Combined net P&L: " + combinedNetPnl.usd() + "    Source priority: Binance, manual fallback, journal", 36f, 66f, paint)
                    canvas.drawText("Date             Opening balance       Daily P&L          Closing balance        Source", 36f, 94f, paint)
                    rows.forEachIndexed { index, day ->
                        val opening = tableDailyRows.firstOrNull { it.first.date == day.date }?.second ?: 0.0
                        val closing = if (opening > 0.0) (opening + day.selectedPnl).usd() else "Not set"
                        val source = when (day.source) {
                            DailyPnlSource.BINANCE -> "Binance"
                            DailyPnlSource.MANUAL -> "Manual"
                            DailyPnlSource.JOURNAL -> "Journal"
                        }
                        val openingText = if (opening > 0.0) opening.usd() else "Not set"
                        canvas.drawText(day.date.padEnd(17) + openingText.padEnd(22) + day.selectedPnl.usd().padEnd(19) + closing.padEnd(22) + source,
                            36f, (116 + index * 17).toFloat(), paint)
                    }
                    canvas.drawText("Page " + (pageIndex + 1), 520f, 820f, paint)
                    document.finishPage(page)
                }
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
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("TODAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f), fontWeight = FontWeight.Bold)
                                Text("Net P&L", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                                Text(if (todayReconciled == null) "NO DATA" else when {
                                    maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss -> "LIMIT HIT"
                                    maxDailyLoss > 0.0 && todayPnl <= -(maxDailyLoss * 0.8) -> "NEAR LIMIT"
                                    else -> "TRACKING"
                                }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    color = if (todayReconciled != null && maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Text(if (todayReconciled == null) "—" else (if (todayPnl > 0.0) "+" else "") + todayPnl.usd(),
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            color = if (todayReconciled == null) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else if (todayPnl < 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(if (todayReconciled == null) "No reconciled P&L recorded for today. Add a daily result or sync exchange data."
                            else "Source: ${todayReconciled.source.name.lowercase().replaceFirstChar { it.uppercase() }} · fees and funding depend on the selected source",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Winning days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                Text(stats.winDays.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Losing days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                Text(stats.lossDays.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Win-day rate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                Text(stats.winRate.pct(0), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Binance account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    liveBinanceLastUpdated?.let { "Updated " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it)) } ?: "No live balance snapshot",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { refreshLiveAccount() }, enabled = !liveBinanceLoading) {
                                if (liveBinanceLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (liveBinanceLoading) "Syncing" else "Refresh")
                            }
                        }
                        Text(
                            if (livePortfolioBalance != null) livePortfolioBalance.usd() else "Unavailable",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Estimated Spot value + USDⓈ-M Futures equity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Binance net P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (importedPnl.isEmpty()) "Unavailable" else (if (importedPnl.sumOf { it.total } > 0.0) "+" else "") + importedPnl.sumOf { it.total }.usd(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (importedPnl.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else pnlColor(importedPnl.sumOf { it.total })
                                )
                                Text("From imported Binance daily history", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Starting capital", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (initialBalance > 0.0) initialBalance.usd() else "Unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (initialBalance > 0.0) "First recorded ledger balance" else "Requires reconciled opening balance and transfers",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(liveBinanceStatus, style = MaterialTheme.typography.bodySmall, color = if (liveBinanceStatus.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if ((liveBinanceBalance?.unpricedAssets ?: 0) > 0) {
                            Text("${liveBinanceBalance?.unpricedAssets} Spot asset(s) excluded from the valuation.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Daily loss budget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    maxDailyLoss <= 0.0 -> "Set a limit in Tools & settings to track your risk budget."
                                    todayReconciled == null -> "Waiting for today's P&L data; usage is not estimated."
                                    else -> "${((-todayPnl).coerceAtLeast(0.0) / maxDailyLoss * 100).toInt()}% of your configured limit used"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(when {
                                maxDailyLoss <= 0.0 -> "Not set"
                                todayReconciled == null -> "Unknown"
                                else -> (maxDailyLoss + todayPnl).coerceIn(0.0, maxDailyLoss).usd()
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = if (todayReconciled != null && maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        if (maxDailyLoss > 0.0 && todayReconciled != null) {
                            LinearProgressIndicator(progress = { lossLimitUsed }, modifier = Modifier.fillMaxWidth(),
                                color = if (lossLimitUsed >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        if (todayReconciled != null && maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss) {
                            Text("Daily loss limit reached. Pause and review your plan before taking another trade.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        } else if (todayReconciled != null && maxDailyLoss > 0.0 && todayPnl <= -(maxDailyLoss * 0.8)) {
                            Text("Caution: at least 80% of your daily loss budget has been used.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Advisory only: this uses recorded P&L and does not enforce a broker-side stop.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Exchange sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(journalSyncStatus.state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { onNavigate(18) }) { Text("Details") }
                        }
                        Text(journalSyncStatus.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Last successful sync: " + if (journalSyncStatus.lastSuccess > 0L) SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(journalSyncStatus.lastSuccess)) else "Not recorded",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Recent journal trades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Estimated realized P&L after entered fees", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onNavigate(1) }) { Text("View all") }
                        }
                        if (trades.isEmpty()) {
                            Text("No journal trades yet. Log a trade to start building your history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { onNavigate(1) }) { Text("Open trade journal") }
                        } else {
                            trades.sortedByDescending { reportDate(it.date)?.time ?: Long.MIN_VALUE }.take(5).forEach { trade ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(trade.symbol.ifBlank { "Trade" }, fontWeight = FontWeight.SemiBold)
                                            Text(trade.side.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(trade.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(if (trade.pnl.isFinite()) (if (trade.pnl > 0.0) "+" else "") + trade.pnl.usd() else "Unavailable",
                                        fontWeight = FontWeight.SemiBold, color = if (!trade.pnl.isFinite()) MaterialTheme.colorScheme.onSurfaceVariant else pnlColor(trade.pnl))
                                }
                            }
                        }
                    }
                }
            }
            if (combinedDailyPnl.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Combined daily P&L", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Reconciled net P&L: " + combinedNetPnl.usd(), fontWeight = FontWeight.Bold, color = pnlColor(combinedNetPnl))
                            Text("Manual days: ${reconciledDailyPnl.count { it.source == DailyPnlSource.MANUAL }} · Binance-only: ${reconciledDailyPnl.count { it.source == DailyPnlSource.BINANCE }} · Journal-only: ${reconciledDailyPnl.count { it.source == DailyPnlSource.JOURNAL }}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Binance daily P&L takes precedence on overlapping dates; manual records are fallback, then closed journal trades fill remaining dates. Overlaps are never added twice and differences are flagged below. Live Spot estimate + Futures equity is shown separately from historical/manual ledger balances.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (mismatchRows.isNotEmpty()) {
                                Text("${mismatchRows.size} overlapping dates differ by more than \$0.01; total absolute difference: ${totalOverlapDifference.usd()}. Review the values below before treating the combined total as final.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            combinedDailyPnl.entries.toList().takeLast(30).reversed().forEach { row ->
                                val manual = manualByDate[row.key]?.pnl
                                val synced = importedByDate[row.key]?.total
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(row.key, style = MaterialTheme.typography.bodySmall)
                                        Text("Manual " + (manual?.usd() ?: "—") + " · Binance " + (synced?.usd() ?: "—"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(row.value.usd(), color = pnlColor(row.value), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            if (todayEntry?.pnl?.let { it <= -maxDailyLoss } == true) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text("Daily loss limit reached: " + todayEntry!!.pnl.usd() + " loss meets/exceeds your " + maxDailyLoss.usd() + " limit. Consider stopping for the day.",
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
                        val csvQuote: (String) -> String = { value -> "\"" + value.replace("\"", "\"\"") + "\"" }
                        val reconciledCsv = buildString {
                            appendLine("Date,Opening Balance,Daily P&L,Daily Return %,Closing Balance,Target 2%,Target Status,Source")
                            tableDailyRows.forEach { (day, openingBalance, _) ->
                                val hasOpening = openingBalance > 0.0
                                val target = openingBalance * 0.02
                                val source = when (day.source) {
                                    DailyPnlSource.BINANCE -> "Binance"
                                    DailyPnlSource.MANUAL -> "Manual"
                                    DailyPnlSource.JOURNAL -> "Journal"
                                }
                                val closing = if (hasOpening) (openingBalance + day.selectedPnl).toString() else "Not set"
                                val opening = if (hasOpening) openingBalance.toString() else "Not set"
                                val returnPct = if (hasOpening) (day.selectedPnl / openingBalance * 100.0).toString() else "Not set"
                                val targetText = if (hasOpening) target.toString() else "Not set"
                                val targetStatus = when {
                                    !hasOpening -> "Opening balance needed"
                                    day.selectedPnl >= target -> "Target complete"
                                    else -> "Target incomplete"
                                }
                                appendLine(listOf(day.date, opening, day.selectedPnl.toString(), returnPct, closing, targetText, targetStatus, source).joinToString(",") { csvQuote(it) })
                            }
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "Reconciled Daily P&L")
                            putExtra(Intent.EXTRA_TEXT, reconciledCsv)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Export CSV"))
                    }) { Text("Export CSV") }
                    TextButton(onClick = { pdfLauncher.launch("risk-tracker-daily-pnl.pdf") }) { Text("Export PDF") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Daily P&L history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("7D", "30D", "All").forEach { option ->
                                FilterChip(selected = dashboardRange == option, onClick = { dashboardRange = option }, label = { Text(option) })
                            }
                        }
                        Text("Filters apply to the daily table below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.horizontalScroll(rememberScrollState()).width(1000.dp)) {
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Date", Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Initial Bal", Modifier.width(115.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Daily P&L", Modifier.width(115.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("W/L %", Modifier.width(85.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Today Bal", Modifier.width(115.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Target 2%", Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Target status", Modifier.width(145.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Source", Modifier.width(105.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                        tableDailyRows.asReversed().filter { row ->
                            if (dashboardRange == "All") true else {
                                val latest = tableDailyRows.lastOrNull()?.first?.date?.let { reportDate(it)?.time }
                                val rowTime = reportDate(row.first.date)?.time
                                val days = if (dashboardRange == "7D") 7 else 30
                                latest != null && rowTime != null && rowTime >= latest - days * 24L * 60L * 60L * 1000L
                            }
                        }.forEach { (day, openingBalance, manualEntry) ->
                            val dailyPnl = day.selectedPnl
                            val dailyTarget = (openingBalance * 0.02).coerceAtLeast(0.0)
                            val hasOpeningBalance = openingBalance > 0.0
                            val completed = hasOpeningBalance && dailyPnl >= dailyTarget
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(day.date, Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall)
                                Text(if (hasOpeningBalance) openingBalance.usd() else "Not set", Modifier.width(115.dp), style = MaterialTheme.typography.bodySmall)
                                Text((if (dailyPnl > 0) "+" else "") + dailyPnl.usd(), Modifier.width(115.dp),
                                    style = MaterialTheme.typography.bodySmall, color = pnlColor(dailyPnl), fontWeight = FontWeight.SemiBold)
                                Text(if (hasOpeningBalance) (dailyPnl / openingBalance * 100).pct(2) else "—", Modifier.width(85.dp), style = MaterialTheme.typography.bodySmall, color = if (hasOpeningBalance) pnlColor(dailyPnl) else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (hasOpeningBalance) (openingBalance + dailyPnl).usd() else "Not set", Modifier.width(115.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text(if (hasOpeningBalance) dailyTarget.usd() else "—", Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.width(145.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when { !hasOpeningBalance -> "Set opening bal"; completed -> "Target complete"; else -> "Target incomplete" },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (completed) MaterialTheme.colorScheme.primary else if (hasOpeningBalance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (manualEntry != null) {
                                        IconButton(onClick = {
                                            onChange(entries.filterNot { it === manualEntry })
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete manual daily P&L row")
                                        }
                                    }
                                }
                                Text(
                                    when (day.source) {
                                        DailyPnlSource.MANUAL -> "Manual"
                                        DailyPnlSource.BINANCE -> "Binance"
                                        DailyPnlSource.JOURNAL -> "Journal"
                                    },
                                    Modifier.width(105.dp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            manualEntry?.takeIf { it.disciplineScore > 0 || it.notes.isNotBlank() }?.let { e ->
                                Text(
                                    listOf(if (e.disciplineScore > 0) "Discipline ${e.disciplineScore}/5" else "", e.notes).filter { it.isNotBlank() }.joinToString(" · "),
                                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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

    if (showAdd) AddEntryDialog(
        entries.maxByOrNull { reportDate(it.date)?.time ?: Long.MIN_VALUE },
        onDismiss = { showAdd = false }
    ) {
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
    var metric by remember { mutableStateOf("Balance") }
    var range by remember { mutableStateOf("All") }
    val datedEntries = entries.sortedBy { reportDate(it.date)?.time ?: Long.MIN_VALUE }
    var cumulative = 0.0
    val allPoints = datedEntries.map { entry ->
        cumulative += entry.pnl
        entry to if (metric == "Balance") entry.closing else cumulative
    }
    val latestDate = datedEntries.mapNotNull { reportDate(it.date) }.maxByOrNull { it.time }
    val rangeDays = when (range) { "1W" -> 7; "1M" -> 30; "3M" -> 90; else -> null }
    val filteredPoints = if (rangeDays == null || latestDate == null) allPoints else {
        val cutoff = latestDate.time - rangeDays * 24L * 60L * 60L * 1000L
        allPoints.filter { (entry, _) -> reportDate(entry.date)?.time?.let { it >= cutoff } == true }
            .ifEmpty { allPoints }
    }
    val points = filteredPoints.map { it.second }
    val lo = points.minOrNull() ?: 0.0
    val hi = points.maxOrNull() ?: 0.0
    val line = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (metric == "Balance") "Balance trend" else "Cumulative net P&L",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Balance", "Net P&L").forEach { option ->
                    FilterChip(selected = metric == option, onClick = { metric = option }, label = { Text(option) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1W", "1M", "3M", "All").forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option) })
                }
            }
            Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                val rangeValue = (hi - lo).takeIf { it > 0 } ?: 1.0
                val path = Path()
                points.forEachIndexed { i, value ->
                    val x = size.width * i / (points.size - 1).coerceAtLeast(1)
                    val y = (size.height * (1 - (value - lo) / rangeValue)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(width = 4f))
                drawLine(Color.LightGray.copy(alpha = 0.5f), Offset(0f, size.height), Offset(size.width, size.height))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low ${lo.usd()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("High ${hi.usd()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (range == "All") "All recorded entries" else "Window is based on the latest saved entry date",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    var backupMessage by remember { mutableStateOf("") }
    var advancedToolsExpanded by remember { mutableStateOf(false) }
    var reportsExpanded by remember { mutableStateOf(false) }
    var integrationsExpanded by remember { mutableStateOf(false) }
    var pendingBackupRaw by remember { mutableStateOf<String?>(null) }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportBackup()) }; backupMessage = "Backup exported." } catch (e: Exception) { backupMessage = "Export failed: " + (e.message ?: "unknown error") }
    }
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Could not read file")
                pendingBackupRaw = raw
                backupMessage = "Backup file loaded. Confirm below before replacing current journal and daily records."
            } catch (e: Exception) {
                backupMessage = "Restore failed: " + (e.message ?: "invalid backup")
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tools & settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Quick tools", style = MaterialTheme.typography.titleMedium)
        MoreDestinationCard("Position & leverage calculator", "Calculate position size, stop-loss risk and liquidation estimates") { onNavigate(2) }
        MoreDestinationCard("Trade Risk Gate", "Review planned risk and daily limits before a trade") { onNavigate(11) }
        Text("Binance & data", style = MaterialTheme.typography.titleMedium)
        MoreDestinationCard("Binance data & imports", "API sync, CSV imports and account-history recovery") { onNavigate(6) }
        MoreDestinationCard("Ledger audit & reconciliation", "Check coverage, duplicates and daily totals") { onNavigate(15) }
        OutlinedButton(onClick = { reportsExpanded = !reportsExpanded }, modifier = Modifier.fillMaxWidth()) {
            Text((if (reportsExpanded) "▾ " else "▸ ") + "Reports & exchange tools")
        }
        if (reportsExpanded) {
            MoreDestinationCard("Account statements", "Deposits, withdrawals, transfers and Futures income") { onNavigate(17) }
            MoreDestinationCard("Live positions & orders", "Read-only view of open positions and pending orders") { onNavigate(16) }
            MoreDestinationCard("Crypto tax report", "Review imported history by Indian financial year") { onNavigate(9) }
        }
        OutlinedButton(onClick = { advancedToolsExpanded = !advancedToolsExpanded }, modifier = Modifier.fillMaxWidth()) {
            Text((if (advancedToolsExpanded) "▾ " else "▸ ") + "Advanced tools")
        }
        if (advancedToolsExpanded) {
            MoreDestinationCard("Target planner", "Optional balance and goal planning") { onNavigate(10) }
            MoreDestinationCard("Behavioral intelligence", "Explore patterns and recurring mistakes") { onNavigate(12) }
            MoreDestinationCard("Risk Scenario Lab", "Simulate loss streaks and risk scenarios") { onNavigate(13) }
            MoreDestinationCard("Trade reviews", "Review closed trades and plan adherence") { onNavigate(14) }
        }
        OutlinedButton(onClick = { integrationsExpanded = !integrationsExpanded }, modifier = Modifier.fillMaxWidth()) {
            Text((if (integrationsExpanded) "▾ " else "▸ ") + "Optional integrations")
        }
        if (integrationsExpanded) {
            MoreDestinationCard("Google Sheets sync", "Optional sync for Daily P&L") { onNavigate(4) }
        }
        MoreDestinationCard("About Risk Tracker", "App details and risk disclaimer") { onNavigate(5) }
        HorizontalDivider()
        Text("App settings", style = MaterialTheme.typography.titleMedium)
        Text("Risk limits", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(dailyLimit, { dailyLimit = it }, label = { Text("Maximum daily loss (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(tradeLimit, { tradeLimit = it }, label = { Text("Maximum planned risk per trade (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("Daily profit target is fixed at 2% of the applicable opening balance and is calculated in the Daily P&L dashboard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { val d = dailyLimit.toDoubleOrNull(); val t = tradeLimit.toDoubleOrNull(); if (d != null && t != null && d > 0 && t > 0) { store.maxDailyLoss = d; store.maxTradeRisk = t; backupMessage = "Risk limits saved. The daily profit target remains fixed at 2%." } else backupMessage = "Enter positive numbers for both risk limits." }, modifier = Modifier.fillMaxWidth()) { Text("Save risk limits") }
        Text("Limits trigger warnings; they do not block exchange activity or execute trades.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
        Text("Exports daily P&L, journal trades, saved trade plans/reviews, risk targets, and imported daily Binance P&L. API credentials, raw exchange fills/statements, and external screenshot files are not included; keep original exchange exports separately.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { exportPicker.launch("risk-tracker-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export journal & settings (JSON)") }
        OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose backup to restore") }
        if (backupMessage.isNotBlank()) Text(backupMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        if (pendingBackupRaw != null) {
            AlertDialog(
                onDismissRequest = { pendingBackupRaw = null },
                title = { Text("Replace current records?") },
                text = { Text("Restoring replaces your current daily P&L and journal entries, and may replace saved trade plans, reviews, and risk limits. Export a backup of the current data first if you may need it.") },
                confirmButton = {
                    TextButton(onClick = {
                        val raw = pendingBackupRaw
                        pendingBackupRaw = null
                        if (raw != null) {
                            try {
                                onRestore(store.importBackup(raw))
                                backupMessage = "Backup restored. The selected backup replaced current daily P&L and journal entries."
                            } catch (e: Exception) {
                                backupMessage = "Restore failed: " + (e.message ?: "invalid backup")
                            }
                        }
                    }) { Text("Replace records") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBackupRaw = null; backupMessage = "Restore cancelled. Current records were unchanged." }) { Text("Cancel") }
                }
            )
        }
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("System", "Light", "Dark").forEachIndexed { index, mode -> SegmentedButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(mode) } } }
        Text("Daily P&L is one net result per day and remains separate from individual trades. Weekly and monthly reports use Daily P&L records. Trade journal statistics and filters use closed trades only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MoreDestinationCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
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
        val validSides = listOf(long, short).filter { it.message == null }
        val suggestion = when {
            parsed.riskPercent > 2.0 ->
                "Your selected risk is " + parsed.riskPercent.pct(1) + " of balance. Consider lowering it to 1–2% if that matches your trading plan."
            validSides.isEmpty() ->
                "These inputs do not currently produce a valid Long or Short setup. Review entry, stop-loss, target, leverage and balance before relying on the estimates."
            validSides.map { it.rr }.filter { it.isFinite() && it > 0.0 }.minOrNull()?.let { it < 1.0 } == true ->
                "At least one valid side has a reward-to-risk ratio below 1:1. Review whether the target justifies the planned risk before trading."
            validSides.all { it.margin > parsed.balance * 0.8 } ->
                "The estimated margin uses more than 80% of your account balance. Consider reducing position size or leverage to leave a buffer for fees and price movement."
            else ->
                "Your current inputs estimate " + parsed.balance.usd() + " balance with " + parsed.riskPercent.pct(1) + " risk per trade. Recheck fees, slippage, funding and exchange margin rules before using this estimate."
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Suggestion from current inputs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Updates with your inputs. Advisory only; liquidation and position-size values are estimates.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
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
fun CombinedTradingCalculatorScreen(input: CalcInput, onChange: (CalcInput) -> Unit) {
    var selectedTool by remember { mutableStateOf("Position sizing") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Position sizing", "Leverage & liquidation").forEach { tool ->
                FilterChip(selected = selectedTool == tool, onClick = { selectedTool = tool }, label = { Text(tool) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedTool == "Position sizing") CalculatorScreen(input, onChange) else LeverageScreen(input)
        }
    }
}
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
