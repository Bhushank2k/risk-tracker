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
import androidx.compose.ui.draw.border
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
    val titles = listOf("Home", "Journal", "Insights", "Tools")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.Build, Icons.Default.Info, Icons.Default.Menu)
    val menuGroups = listOf(
        "TRACKING" to listOf("Dashboard" to 0, "Trade journal" to 1, "Performance insights" to 8, "Trade reviews" to 14),
        "TRADING TOOLS" to listOf("Position calculator" to 2, "Leverage & liquidation" to 3, "Trade Risk Gate" to 11, "Behavioral intelligence" to 12, "Risk Scenario Lab" to 13),
        "IMPORTS & REPORTS" to listOf("Google Sheets sync" to 4, "Binance trade history" to 6, "Live trades" to 16, "Crypto tax report" to 9, "Ledger audit" to 15),
        "GOAL PLANNING" to listOf("Target" to 10),
        "APP" to listOf("Settings & backup" to 7, "About Risk Tracker" to 5)
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
                navigationIcon = {
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
                0 -> PnlScreen(entries, syncedCount, store.maxDailyLoss, store.dailyProfitTarget, importedPnl) {
                    entries = it
                    store.saveEntries(it)
                }
                1 -> TradeJournalScreen(trades, onAdd = { trade ->
                    trades = (trades + trade).sortedByDescending { it.date }
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
                6 -> BinanceSyncScreen(onSynced = { importedPnl = BinanceSyncData.load(context) }, onJournalImported = { exchangeFills = BinanceAutoJournalStore.load(context) })
                8 -> InsightsScreen(entries, trades.size)
                9 -> CryptoTaxReportScreen()
                10 -> TargetScreen()
                11 -> TradeRiskGateScreen(TradeRiskSettings(
                    maxTradeRisk = store.maxTradeRisk,
                    maxDailyLoss = store.maxDailyLoss,
                    dailyPnl = pnlEntryForDate(entries)?.pnl ?: 0.0,
                    maxTradesPerDay = 10,
                    tradesTakenToday = trades.count { it.date == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
                ))
                12 -> BehavioralInsightsScreen(trades)
                13 -> RiskScenarioLabScreen()
                14 -> TradeReviewScreen(trades)
                15 -> LedgerAuditScreen()
                16 -> LiveTradesScreen()
                else -> MoreScreen(store, entries, trades, themeMode, onThemeModeChange,
                    onRestore = { pair -> entries = pair.first; trades = pair.second }) { tab = it }
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
fun PnlScreen(entries: List<PnlEntry>, syncedCount: Int, maxDailyLoss: Double, dailyProfitTarget: Double, importedPnl: List<BinanceDailyResult> = emptyList(), onChange: (List<PnlEntry>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var liveBinanceTrades by remember { mutableStateOf<List<BinanceActivityItem>>(emptyList()) }
    var liveBinanceBalance by remember { mutableStateOf<BinancePortfolioBalance?>(null) }
    var liveBinanceFuturesBalance by remember { mutableStateOf<BinanceFuturesAccountBalance?>(null) }
    var liveBinanceLoading by remember { mutableStateOf(false) }
    var liveBinanceStatus by remember { mutableStateOf("Connect Binance in Binance trade history to load live fills.") }
    var showAdd by remember { mutableStateOf(false) }
    val stats = computeStats(entries)
    val livePortfolioBalance = if (liveBinanceBalance != null && liveBinanceFuturesBalance != null)
        liveBinanceBalance!!.valueUsdt + liveBinanceFuturesBalance!!.totalEquity else null
    // Merge manual ledger and synced Binance P&L by date.
    val manualByDate = entries.associateBy { it.date }
    val importedByDate = importedPnl.associateBy { it.date }
    val combinedDates = (manualByDate.keys + importedByDate.keys).toSortedSet()
    val combinedDailyPnl = combinedDates.associateWith { date ->
        (manualByDate[date]?.pnl ?: 0.0) + (importedByDate[date]?.total ?: 0.0)
    }
    val combinedNetPnl = combinedDailyPnl.values.sum()
    val latestPnlDate = combinedDailyPnl.keys.maxOrNull().orEmpty().ifBlank { "No recorded entries" }
    val latestRecordedPnl = combinedDailyPnl[latestPnlDate] ?: 0.0
    val todayEntry = pnlEntryForDate(entries)
    val todayPnl = todayEntry?.pnl ?: 0.0
    val lossLimitUsed = if (maxDailyLoss > 0.0) (-todayPnl / maxDailyLoss).coerceIn(0.0, 1.0).toFloat() else 0f
    val pdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val document = PdfDocument()
            try {
                entries.chunked(40).ifEmpty { listOf(emptyList()) }.forEachIndexed { pageIndex, rows ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                    val paint = Paint().apply { textSize = 16f; color = android.graphics.Color.BLACK }
                    val canvas = page.canvas
                    canvas.drawText("Risk Tracker - Daily P&L Report", 36f, 42f, paint)
                    paint.textSize = 10f
                    canvas.drawText("Net P&L: " + stats.net.usd() + "    Win rate: " + stats.winRate.pct(1) + "    Recorded days: " + entries.size, 36f, 66f, paint)
                    canvas.drawText("Date                         Opening Balance            Daily P&L            Closing Balance       Discipline", 36f, 94f, paint)
                    rows.forEachIndexed { index, e -> canvas.drawText(e.date.padEnd(18) + " " + e.balance.usd().padEnd(20) + " " + e.pnl.usd().padEnd(18) + " " + e.closing.usd().padEnd(20) + " " + e.disciplineScore + "/5", 36f, (116 + index * 17).toFloat(), paint) }
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
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("PORTFOLIO BALANCE", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        val displayedBalance = livePortfolioBalance ?: stats.currentBalance
                        Text(displayedBalance.usd(), style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text((if (livePortfolioBalance != null) "Binance Spot + Futures equity · " else "Manual ledger · ") + "₹" + (displayedBalance * inrRate).num(0),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Latest recorded P&L · $latestPnlDate", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                Text((if (latestRecordedPnl > 0) "+" else "") + latestRecordedPnl.usd(),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    color = if (latestRecordedPnl < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Combined net P&L", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                Text(combinedNetPnl.usd(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    color = if (combinedNetPnl < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Latest entry vs loss limit", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(if (maxDailyLoss > 0) "${(lossLimitUsed * 100).toInt()}% used" else "Not set",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxWidth(lossLimitUsed).height(7.dp)
                                .background(if (lossLimitUsed >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
                        }
                        Text("${stats.winDays} winning days · ${stats.lossDays} losing days · win rate ${stats.winRate.pct(0)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                val suggestion = when {
                    maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss ->
                        "Your recorded daily loss has reached the configured limit (" + todayPnl.usd() + " of " + maxDailyLoss.usd() + "). Consider stopping for the day and reviewing your plan."
                    maxDailyLoss > 0.0 && todayPnl <= -maxDailyLoss * 0.75 ->
                        "You've used " + ((-todayPnl / maxDailyLoss) * 100).toInt() + "% of your daily loss limit. Consider reducing risk or pausing before taking another trade."
                    dailyProfitTarget > 0.0 && todayPnl >= dailyProfitTarget ->
                        "Your recorded daily profit target is reached (" + todayPnl.usd() + " of " + dailyProfitTarget.usd() + "). Consider protecting gains and sticking to your plan."
                    todayPnl > 0.0 ->
                        "Today's recorded P&L is " + todayPnl.usd() + ". Keep position risk within your saved limits and avoid increasing size just because you're up."
                    else ->
                        "No daily profit or loss threshold has been reached from today's manual ledger entry. Keep planned risk within your saved limits and verify synced exchange data separately."
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Today's suggestion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Based on today's manual P&L and your configured limits; advisory only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Live Binance transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Spot + USDⓈ-M Futures balances · read-only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(enabled = !liveBinanceLoading, onClick = {
                                liveBinanceLoading = true
                                liveBinanceStatus = "Refreshing from Binance…"
                                scope.launch {
                                    try {
                                        val fetched = withContext(Dispatchers.IO) {
                                            val pair = BinanceCredentialStore(ctx).read() ?: error("Add read-only API credentials in Binance trade history first.")
                                            val api = BinanceApi(pair.first, pair.second)
                                             Triple(api.spotPortfolioBalance(), api.recentSpotTransactions(), api.futuresAccountBalance())
                                        }
                                        liveBinanceBalance = fetched.first
                                         liveBinanceTrades = fetched.second
                                         liveBinanceFuturesBalance = fetched.third
                                         liveBinanceStatus = "Spot ${fetched.first.valueUsdt.usd()} USDT estimated + Futures equity ${fetched.third.totalEquity.usd()} USDT · ${fetched.second.size} recent Spot fills" + if (fetched.first.unpricedAssets > 0) " · ${fetched.first.unpricedAssets} Spot asset(s) unpriced and excluded" else ""
                                    } catch (e: Exception) { liveBinanceStatus = e.message ?: "Could not refresh Binance activity." }
                                    finally { liveBinanceLoading = false }
                                }
                            }) { Text(if (liveBinanceLoading) "Loading…" else "Refresh") }
                        }
                        Text(liveBinanceStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        liveBinanceBalance?.let { balance ->
                             Text("Spot account: ${balance.assetsHeld} assets with positive balances · unpriced assets excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                         }
                         liveBinanceFuturesBalance?.let { balance ->
                             HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                             Text("USDⓈ-M FUTURES ACCOUNT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                 Text("Wallet balance", style = MaterialTheme.typography.bodySmall)
                                 Text(balance.walletBalance.usd(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                             }
                             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                 Text("Unrealized P&L", style = MaterialTheme.typography.bodySmall)
                                 Text((if (balance.unrealizedPnl > 0) "+" else "") + balance.unrealizedPnl.usd(), style = MaterialTheme.typography.bodySmall,
                                     color = if (balance.unrealizedPnl < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                             }
                             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                 Text("Total Futures equity", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                 Text(balance.totalEquity.usd(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                             }
                             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                 Text("Available to trade", style = MaterialTheme.typography.bodySmall)
                                 Text(balance.availableBalance.usd(), style = MaterialTheme.typography.bodySmall)
                             }
                             if (balance.assets.isNotEmpty()) {
                                 Text("Futures assets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                 balance.assets.forEach { asset ->
                                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                         Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                             Text(asset.asset, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                             Text("Wallet ${asset.walletBalance.num(6)} · Available ${asset.availableBalance.num(6)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                         }
                                         Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                             Text(asset.marginBalance.num(6), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                             Text("UPnL ${if (asset.unrealizedPnl > 0) "+" else ""}${asset.unrealizedPnl.num(4)}", style = MaterialTheme.typography.labelSmall,
                                                 color = if (asset.unrealizedPnl < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                         }
                                     }
                                 }
                             }
                             Text("Last refreshed: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(balance.fetchedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                         }
                         liveBinanceTrades.take(8).forEach { trade ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(trade.symbol, fontWeight = FontWeight.SemiBold)
                                        Text(trade.side, color = if (trade.side == "BUY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text("${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(trade.time))} · Qty ${trade.quantity.num(6)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Fee ${trade.fee.num(6)} ${trade.feeAsset}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(trade.quoteAmount.usd(), fontWeight = FontWeight.SemiBold)
                                    Text("@ ${trade.price.num(4)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("Manual + synced Binance: " + combinedNetPnl.usd(), fontWeight = FontWeight.Bold, color = pnlColor(combinedNetPnl))
                            Text("Spot FIFO: " + importedPnl.sumOf { it.spotPnl }.usd() + " · Futures net: " + importedPnl.sumOf { it.futuresPnl }.usd(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Each date adds manual P&L and synced Binance Spot/Futures P&L. If the same trades were entered manually and synced, that date can be double-counted; remove duplicate manual records if needed. Portfolio balance remains based on the live Binance Spot estimate when refreshed, otherwise the manual ledger.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            combinedDailyPnl.entries.toList().takeLast(30).reversed().forEach { row ->
                                val manual = manualByDate[row.key]?.pnl ?: 0.0
                                val synced = importedByDate[row.key]?.total ?: 0.0
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(row.key, style = MaterialTheme.typography.bodySmall)
                                        Text("Manual " + manual.usd() + " · Synced " + synced.usd(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(row.value.usd(), color = pnlColor(row.value), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            if (todayEntry?.pnl?.let { it >= dailyProfitTarget } == true) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text("Daily profit target reached: " + todayEntry!!.pnl.usd() + " of " + dailyProfitTarget.usd() + ". Protect your gains and follow your plan.",
                            Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
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
        Text("Trading tools", style = MaterialTheme.typography.titleMedium)
        MoreDestinationCard("Position calculator", "Position size, risk and target") { onNavigate(2) }
        MoreDestinationCard("Leverage & liquidation", "Compare leverage risk") { onNavigate(3) }
        Text("Imports & reports", style = MaterialTheme.typography.titleMedium)
        MoreDestinationCard("Google Sheets sync", "Sync Daily P&L only") { onNavigate(4) }
        MoreDestinationCard("Binance trade history", "Read-only Spot & Futures history") { onNavigate(6) }
        MoreDestinationCard("Crypto tax report", "Review imported history by Indian financial year") { onNavigate(9) }
        Text("About", style = MaterialTheme.typography.titleMedium)
        MoreDestinationCard("About Risk Tracker", "App details and risk disclaimer") { onNavigate(5) }
        HorizontalDivider()
        Text("Performance summaries", style = MaterialTheme.typography.titleMedium)
        Text("Weekly report · last 7 recorded days", style = MaterialTheme.typography.titleMedium)
        ReportSummary(weekly)
        Text("Monthly report · current month", style = MaterialTheme.typography.titleMedium)
        ReportSummary(monthly)
        HorizontalDivider()
        Text("App settings", style = MaterialTheme.typography.titleMedium)
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
