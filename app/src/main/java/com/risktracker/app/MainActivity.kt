package com.risktracker.app

import android.content.Intent
import android.os.Bundle
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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

fun Double.usd(d: Int = 2) = "$" + "%,.${d}f".format(this)
fun Double.pct(d: Int = 2) = "%.${d}f%%".format(this)
fun Double.num(d: Int = 2) = "%,.${d}f".format(this)

/** Theme-aware color for a P&L value; neutral gray when it is zero. */
@Composable
@ReadOnlyComposable
fun pnlColor(v: Double): Color = if (v > 0) profitColor() else if (v < 0) lossColor() else Color.Gray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BinanceRestGuard.configure(applicationContext)
        val store = Store(applicationContext)
        store.clearManualTradesOnce()
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
            MaterialTheme(colorScheme = scheme, shapes = RiskTrackerShapes, typography = RiskTrackerTypography) {
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
    var input by remember { mutableStateOf(store.loadInput()) }
    var trades by remember { mutableStateOf(store.loadTrades()) }
    val todayPnlDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    // Daily P&L comes only from imported Binance records; the manual ledger is not used.
    val todayReconciledPnl = remember(importedPnl, todayPnlDate) {
        reconcileDailyPnl(emptyList(), importedPnl, emptyList()).firstOrNull { it.date == todayPnlDate }
    }
    var exchangeFills by remember { mutableStateOf(BinanceAutoJournalStore.load(context)) }
    var exchangeSyncStatus by remember { mutableStateOf(BinanceAutoJournalStore.syncStatus(context)) }
    val appScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (BinanceCredentialStore(context).read() != null) {
            BinanceJournalWorker.schedule(context)
            runCatching {
                withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) }
                exchangeFills = BinanceAutoJournalStore.load(context)
                exchangeSyncStatus = BinanceAutoJournalStore.syncStatus(context)
            }
        }
    }
    LaunchedEffect(tab) {
        val lastAttempt = BinanceAutoJournalStore.syncStatus(context).lastAttempt
        if (tab == 20 && BinanceCredentialStore(context).read() != null &&
            shouldSyncOnOpen(lastAttempt, System.currentTimeMillis())) {
            appScope.launch {
                runCatching { withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) } }
                exchangeFills = BinanceAutoJournalStore.load(context)
                exchangeSyncStatus = BinanceAutoJournalStore.syncStatus(context)
            }
        }
    }
    val titles = listOf("Overview", "Journal", "Analytics", "Ideas")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.Build, Icons.Default.Info, Icons.Default.Refresh)
    // Keep the bottom bar focused on frequent destinations; expose every specialist screen here.
    // Destination indices map to the existing screen router below.
    val menuGroups = listOf(
        "HOME" to listOf(
            "Overview" to 0,
            "Trade Journal" to 1
        ),
        "MARKET OPPORTUNITIES" to listOf(
            "Daily Trade Ideas" to 21
        ),
        "ANALYTICS" to listOf(
            "Trading Analytics" to 20,
            "P&L Calendar" to 23,
            "Post-trade reviews" to 14,
            "Ledger audit" to 15
        ),
        "AI INTELLIGENCE" to listOf(
            "AI Trading Intelligence" to 24
        ),
        "RISK MANAGEMENT" to listOf(
            "Position & leverage calculator" to 2,
            "Trade Risk Gate" to 11,
            "Risk alerts" to 19,
            "Risk Scenario Lab" to 13,
            "Target Planner" to 22
        ),
        "BINANCE & DATA" to listOf(
            "Binance Sync" to 6,
            "Sync health" to 18,
            "Live positions & orders" to 16,
            "Account activity" to 17
        ),
        "REPORTS & APP" to listOf(
            "Crypto tax report" to 9,
            "Settings" to 7,
            "About Risk Tracker" to 5
        )
    )

    val activeNavIndex = when (tab) { 0 -> 0; 1 -> 1; 20 -> 2; 21 -> 3; else -> -1 }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Text("RT", color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                        }
                        Text("Risk Tracker", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "All destinations")
                    }
                }
            )
        },
        bottomBar = {
            val navShape = RoundedCornerShape(24.dp)
            NavigationBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp).clip(navShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f), navShape),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                titles.forEachIndexed { i, title ->
                    val selected = activeNavIndex == i
                    NavigationBarItem(selected = selected,
                        onClick = { when (i) { 0 -> tab = 0; 1 -> tab = 1; 2 -> tab = 20; 3 -> tab = 21 } },
                        icon = { Icon(icons[i], contentDescription = null, modifier = Modifier.size(if (selected) 23.dp else 21.dp)) },
                        label = { Text(title, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "screen-transition"
            ) { destination ->
                when (destination) {
                0 -> PnlScreen(store.maxDailyLoss, importedPnl, trades, onNavigate = { tab = it }, onImportedPnlChange = { importedPnl = it })
                1 -> TradeJournalScreen(exchangeFills)
                2 -> CombinedTradingCalculatorScreen(
                    input = input,
                    accountEquity = BinanceAccountSnapshotStore(context).load()?.let { it.spot.valueUsdt + it.futures.totalEquity },
                    onChange = {
                        input = it
                        store.saveInput(it)
                    }
                )
                5 -> AboutScreen()
                6 -> BinanceSyncScreen(onSynced = { importedPnl = BinanceSyncData.load(context) }, onJournalImported = { exchangeFills = BinanceAutoJournalStore.load(context) }, onNavigate = { tab = it })
                9 -> CryptoTaxReportScreen()
                11 -> TradeRiskGateScreen(TradeRiskSettings(
                    maxTradeRisk = store.maxTradeRisk,
                    maxDailyLoss = store.maxDailyLoss,
                    dailyPnl = todayReconciledPnl?.selectedPnl ?: 0.0,
                    maxTradesPerDay = 10,
                    tradesTakenToday = trades.count { canonicalPnlDate(it.date) == todayPnlDate },
                    dailyPnlKnown = todayReconciledPnl != null
                ))
                13 -> RiskScenarioLabScreen()
                14 -> TradeReviewScreen(trades, exchangeFills, exchangeSyncStatus, onSyncExchange = {
                    if (BinanceCredentialStore(context).read() != null) {
                        appScope.launch {
                            runCatching { withContext(Dispatchers.IO) { BinanceAutoJournalRunner.sync(context) } }
                            exchangeFills = BinanceAutoJournalStore.load(context)
                            exchangeSyncStatus = BinanceAutoJournalStore.syncStatus(context)
                        }
                    }
                })
                15 -> LedgerAuditScreen()
                16 -> LiveTradesScreen()
                17 -> BinanceStatementsScreen(onBack = { tab = 6 })
                18 -> BinanceSyncHealthScreen()
                19 -> RiskAlertsScreen(trades, todayReconciledPnl?.selectedPnl, store.maxDailyLoss)
                20 -> TradingAnalyticsScreen(exchangeFills, exchangeSyncStatus, importedDailyPnl = importedPnl)
                21 -> DailyTradeIdeasScreen()
                22 -> TargetScreen()
                23 -> PnlCalendarScreen(trades, exchangeFills)
                24 -> AiTradingIntelligenceScreen(trades, exchangeFills, store.maxTradeRisk, store.maxDailyLoss)
                else -> MoreScreen(store, trades, themeMode, onThemeModeChange,
                    onRestore = { restoredTrades ->
                        trades = restoredTrades
                        importedPnl = BinanceSyncData.load(context)
                    }) { tab = it }
                }
            }
        }
    }
    if (menuExpanded) {
        ModalBottomSheet(
            onDismissRequest = { menuExpanded = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)
                    .padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Explore Risk Tracker", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("All tools and destinations", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    menuGroups.forEachIndexed { groupIndex, (group, destinations) ->
                        item(key = "group-$groupIndex") {
                            if (groupIndex > 0) HorizontalDivider(
                                Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                            Text(group, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        items(destinations, key = { it.second }) { (label, destination) ->
                            NavigationDrawerItem(
                                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                selected = tab == destination,
                                onClick = { tab = destination; menuExpanded = false },
                                icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
                }
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
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sync health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (status.state.startsWith("Up to date")) Icons.Default.Refresh else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (status.state.startsWith("Up to date")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(status.state, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
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
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Latest attempt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
    }
}

// ---------------- Live trades ----------------

@Composable
fun LiveTradesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liveTrades by remember { mutableStateOf<List<BinanceLiveTrade>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun refresh() {
        if (loading) return
        val credentials = BinanceCredentialStore(context).read()
        if (credentials == null) {
            status = "Binance is not connected. Open Binance trade history and save read-only API credentials first."
            return
        }
        loading = true
        status = ""
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    BinanceApi(credentials.first, credentials.second).liveTrades()
                }
                liveTrades = rows
                status = ""
            } catch (e: Exception) {
                status = e.message ?: "Could not load live trades. Check API permissions and connection."
            } finally {
                loading = false
            }
        }
    }

    
    val streamSymbols = liveTrades
        .filter { it.market == "USDⓈ-M Futures" }
        .map { it.symbol }
        .distinct()
        .sorted()
    LaunchedEffect(Unit) {
        refresh()
        // Account/position snapshots are fetched once on entry; live Futures marks use WebSocket.
        // Refresh is user initiated so this screen cannot create a permanent REST polling loop.
    }
    DisposableEffect(streamSymbols) {
        val stream = BinanceMarkPriceStream(
            symbols = streamSymbols,
            onMarkPrice = { symbol, price ->
                liveTrades = liveTrades.map { trade ->
                    if (trade.market == "USDⓈ-M Futures" && trade.symbol.equals(symbol, ignoreCase = true)) {
                        trade.withMarkPrice(price)
                    } else trade
                }
            },
            onStatus = {}
        )
        stream.start()
        onDispose { stream.close() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Live trades", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                    }
                    if (status.contains("not connected", true) || status.contains("Could not", true) || status.contains("failed", true)) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                RiskCard(Modifier.fillMaxWidth()) {
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
                    }
                }
            }
        }
        items(liveTrades, key = { it.id }) { trade ->
            RiskCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trade.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(trade.market, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(trade.side, color = if (trade.side == "LONG" || trade.side == "BUY") profitColor() else lossColor(), fontWeight = FontWeight.Bold)
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
fun PnlScreen(
    maxDailyLoss: Double,
    importedPnl: List<BinanceDailyResult> = emptyList(),
    trades: List<TradeEntry> = emptyList(),
    onNavigate: (Int) -> Unit = {},
    onImportedPnlChange: (List<BinanceDailyResult>) -> Unit = {}
) {
    RiskOverviewScreen(
        importedPnl = importedPnl,
        trades = trades,
        maxDailyLoss = maxDailyLoss,
        onNavigate = onNavigate
    )
}

@Composable
fun MoreScreen(store: Store, trades: List<TradeEntry>, themeMode: String, onThemeModeChange: (String) -> Unit, onRestore: (List<TradeEntry>) -> Unit, onNavigate: (Int) -> Unit) {
    val context = LocalContext.current
    var dailyLimit by remember { mutableStateOf(store.maxDailyLoss.toString()) }
    var tradeLimit by remember { mutableStateOf(store.maxTradeRisk.toString()) }
    var backupMessage by remember { mutableStateOf("") }
    var tradingExpanded by remember { mutableStateOf(true) }
    var riskExpanded by remember { mutableStateOf(false) }
    var exchangeExpanded by remember { mutableStateOf(false) }
    var reportsExpanded by remember { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your trading tools, exchange data and reports in one place.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RiskCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("QUICK ACCESS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoreQuickTile("Trading\nAnalytics", "P&L and stats", Modifier.weight(1f)) { onNavigate(20) }
                    MoreQuickTile("Trade\nJournal", "External trades & Binance positions", Modifier.weight(1f)) { onNavigate(1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoreQuickTile("Position\nCalculator", "Size and leverage", Modifier.weight(1f)) { onNavigate(2) }
                    MoreQuickTile("Binance\nSync", "Exchange connection", Modifier.weight(1f)) { onNavigate(6) }
                }
            }
        }
        MoreMenuSection("Analytics", "Execution results and trade quality", tradingExpanded, { tradingExpanded = !tradingExpanded }) {
            MoreDestinationCard("Trading Analytics", "Volume, realized P&L, fees and pair performance") { onNavigate(20) }
            MoreDestinationCard("P&L Calendar", "Daily realized results with manual and Binance Futures sources") { onNavigate(23) }
            MoreDestinationCard("Post-trade reviews", "Review closed trades and plan adherence") { onNavigate(14) }
            MoreDestinationCard("Ledger audit", "Check imported history, duplicates and daily totals") { onNavigate(15) }
        }
        MoreMenuSection("Risk workspace", "Plan position size and control trading risk", riskExpanded, { riskExpanded = !riskExpanded }) {
            MoreDestinationCard("Position & leverage calculator", "Calculate position size, stop-loss risk and liquidation estimates") { onNavigate(2) }
            MoreDestinationCard("Trade Risk Gate", "Review planned risk and daily limits before a trade") { onNavigate(11) }
            MoreDestinationCard("Risk alerts", "Review daily loss limits and risk warnings") { onNavigate(19) }
            MoreDestinationCard("Risk Scenario Lab", "Simulate loss streaks and risk scenarios") { onNavigate(13) }
            MoreDestinationCard("Target Planner", "Plan a balance goal and required daily net-profit pace") { onNavigate(22) }
        }
        MoreMenuSection("Exchange & data", "Binance connection, imports and reconciliation", exchangeExpanded, { exchangeExpanded = !exchangeExpanded }) {
            MoreDestinationCard("Binance data & imports", "Connect read-only API access and import account history") { onNavigate(6) }
            MoreDestinationCard("Sync health", "Review automatic sync status and coverage") { onNavigate(18) }
            MoreDestinationCard("Live positions & orders", "Read-only view of open positions and pending orders") { onNavigate(16) }
            MoreDestinationCard("Account activity", "Recent deposits, withdrawals, transfers and Futures income") { onNavigate(17) }
        }
        MoreMenuSection("Reports & app", "Tax summaries, app information and account records", reportsExpanded, { reportsExpanded = !reportsExpanded }) {
            MoreDestinationCard("Crypto tax report", "Review imported history by Indian financial year") { onNavigate(9) }
            MoreDestinationCard("About Risk Tracker", "App version, developer and important disclosures") { onNavigate(5) }
        }
        HorizontalDivider()
        Text("Risk limits", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(dailyLimit, { dailyLimit = it }, label = { Text("Maximum daily loss (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(tradeLimit, { tradeLimit = it }, label = { Text("Maximum planned risk per trade (USD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Button(onClick = { val d = dailyLimit.toDoubleOrNull(); val t = tradeLimit.toDoubleOrNull(); if (d != null && t != null && d > 0 && t > 0) { store.maxDailyLoss = d; store.maxTradeRisk = t; backupMessage = "Risk limits saved." } else backupMessage = "Enter positive numbers for both risk limits." }, modifier = Modifier.fillMaxWidth()) { Text("Save risk limits") }
        HorizontalDivider()
        Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
        Text("Exports journal trades, saved trade plans/reviews, risk targets, and imported daily Binance P&L. API credentials, raw exchange fills/statements, and external screenshot files are not included; keep original exchange exports separately.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { exportPicker.launch("risk-tracker-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export journal & settings (JSON)") }
        OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose backup to restore") }
        if (backupMessage.isNotBlank()) Text(backupMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        if (pendingBackupRaw != null) {
            AlertDialog(
                onDismissRequest = { pendingBackupRaw = null },
                title = { Text("Replace current records?") },
                text = { Text("Restoring replaces your current journal entries, and may replace saved trade plans, reviews, and risk limits. Export a backup of the current data first if you may need it.") },
                confirmButton = {
                    TextButton(onClick = {
                        val raw = pendingBackupRaw
                        pendingBackupRaw = null
                        if (raw != null) {
                            try {
                                onRestore(store.importBackup(raw))
                                backupMessage = "Backup restored. The selected backup replaced current journal entries."
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
    }
}

@Composable
private fun MoreMenuSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    RiskCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) {
                    Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun MoreQuickTile(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    RiskCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MoreDestinationCard(title: String, subtitle: String, onClick: () -> Unit) {
    RiskCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------- P&L calendar ----------------

@Composable
fun PnlCalendarScreen(trades: List<TradeEntry>, exchangeFills: List<BinanceJournalFill>) {
    var visibleYear by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var visibleMonth by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var selectedDate by remember { mutableStateOf(dateFormatter.format(Date())) }
    val monthKey = String.format(Locale.US, "%04d-%02d", visibleYear, visibleMonth + 1)
    val monthCalendar = Calendar.getInstance().apply { set(visibleYear, visibleMonth, 1) }
    val monthTitle = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(monthCalendar.time)
    fun changeMonth(delta: Int) {
        val next = Calendar.getInstance().apply { set(visibleYear, visibleMonth, 1); add(Calendar.MONTH, delta) }
        visibleYear = next.get(Calendar.YEAR)
        visibleMonth = next.get(Calendar.MONTH)
        selectedDate = dateFormatter.format(next.time)
    }
    val manualByDate = remember(trades) {
        trades.filter { it.pnl.isFinite() }.mapNotNull { trade ->
            canonicalPnlDate(trade.date)?.let { it to trade }
        }.groupBy({ it.first }, { it.second })
    }
    val futuresByDate = remember(exchangeFills) {
        exchangeFills.filter { it.market.contains("Futures", true) && it.realizedPnl?.isFinite() == true && it.time > 0L }
            .mapNotNull { fill -> runCatching { dateFormatter.format(Date(fill.time)) to fill }.getOrNull() }
            .groupBy({ it.first }, { it.second })
    }
    val manualMonth = manualByDate.filterKeys { it.startsWith(monthKey) }
    val futuresMonth = futuresByDate.filterKeys { it.startsWith(monthKey) }
    val manualTotal = manualMonth.values.flatten().sumOf { it.pnl }
    val futuresTotal = futuresMonth.values.flatten().sumOf { it.realizedPnl ?: 0.0 }
    val manualWinDays = manualMonth.count { (_, rows) -> rows.sumOf { it.pnl } > 0.0 }
    val futuresWinDays = futuresMonth.count { (_, rows) -> rows.sumOf { it.realizedPnl ?: 0.0 } > 0.0 }
    val offset = (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val dayCount = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val calendarCells = List<Int?>(offset) { null } + (1..dayCount).map { it }
    val selectedManual = manualByDate[selectedDate].orEmpty()
    val selectedFutures = futuresByDate[selectedDate].orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("P&L Calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Closed-trade results by local calendar day", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskCard(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Manual journal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(manualTotal.usd(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = pnlColor(manualTotal))
                    Text("$manualWinDays profitable days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RiskCard(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Binance Futures", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(futuresTotal.usd(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = pnlColor(futuresTotal))
                    Text("$futuresWinDays profitable days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(monthTitle,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { changeMonth(-1) }) { Text("‹") }
                        TextButton(onClick = {
                            val today = Calendar.getInstance()
                            visibleYear = today.get(Calendar.YEAR)
                            visibleMonth = today.get(Calendar.MONTH)
                            selectedDate = dateFormatter.format(today.time)
                        }) { Text("Today") }
                        TextButton(onClick = { changeMonth(1) }) { Text("›") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                        Text(day, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                calendarCells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.weight(1f).height(62.dp))
                            } else {
                                val date = Calendar.getInstance().apply {
                                    set(visibleYear, visibleMonth, day, 0, 0, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val key = dateFormatter.format(date.time)
                                val manualPnl = manualByDate[key].orEmpty().sumOf { it.pnl }
                                val futuresPnl = futuresByDate[key].orEmpty().sumOf { it.realizedPnl ?: 0.0 }
                                val hasManual = manualByDate[key].orEmpty().isNotEmpty()
                                val hasFutures = futuresByDate[key].orEmpty().isNotEmpty()
                                val dayPnl = manualPnl + futuresPnl
                                val hasData = hasManual || hasFutures
                                val cellColor = when {
                                    !hasData -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    dayPnl > 0.0 -> profitColor().copy(alpha = 0.14f)
                                    dayPnl < 0.0 -> lossColor().copy(alpha = 0.14f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                Surface(onClick = { selectedDate = key },
                                    modifier = Modifier.weight(1f).height(62.dp),
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (selectedDate == key) MaterialTheme.colorScheme.primaryContainer else cellColor,
                                    border = if (selectedDate == key) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(day.toString(), style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selectedDate == key) FontWeight.Bold else FontWeight.Normal)
                                        if (hasData) {
                                            if (hasManual) Text("M " + manualPnl.num(0), style = MaterialTheme.typography.labelSmall,
                                                color = pnlColor(manualPnl), maxLines = 1)
                                            if (hasFutures) Text("F " + futuresPnl.num(0), style = MaterialTheme.typography.labelSmall,
                                                color = pnlColor(futuresPnl), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f).height(62.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("M · Manual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("F · Binance Futures", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(runCatching { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(dateFormatter.parse(selectedDate) ?: Date()) }.getOrDefault(selectedDate),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (selectedManual.isEmpty() && selectedFutures.isEmpty()) {
                    Text("No closed-trade P&L records for this date.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selectedManual.isNotEmpty()) {
                    Text("Manual journal · \${selectedManual.size} trades", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    selectedManual.forEach { trade ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(trade.symbol, fontWeight = FontWeight.SemiBold)
                                Text(trade.side + " · " + trade.date, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(trade.pnl.usd(), color = pnlColor(trade.pnl), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (selectedFutures.isNotEmpty()) {
                    if (selectedManual.isNotEmpty()) HorizontalDivider()
                    Text("Binance Futures · \${selectedFutures.size} executions with realized P&L",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    selectedFutures.sortedBy { it.time }.forEach { fill ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fill.symbol, fontWeight = FontWeight.SemiBold)
                                Text(fill.side + " · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fill.time)),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((fill.realizedPnl ?: 0.0).usd(), color = pnlColor(fill.realizedPnl ?: 0.0), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Text("Sources are shown separately to avoid silently double-counting manual entries against Binance fills. Manual values use the saved journal P&L. Binance values include only Futures executions with exchange-reported realized P&L; fee-asset conversion, Spot cost basis and funding are not added here. Empty dates mean no records were found, not zero profit.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        RiskCard(Modifier.fillMaxWidth()) {
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
fun CalculatorScreen(input: CalcInput, accountEquity: Double? = null, onChange: (CalcInput) -> Unit) {
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
        if (accountEquity != null && accountEquity.isFinite() && accountEquity > 0.0) {
            RiskCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Binance account equity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(accountEquity.usd(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { balance = accountEquity.toString() }) { Text("Use balance") }
                }
            }
        }
        Field("Account balance (USD)", balance) { balance = it }
        Field("Risk per trade (%)", risk) { risk = it }
        Field("Entry price (USD)", entry) { entry = it }
        Field("Stop loss price (USD)", sl) { sl = it }
        Field("Target price (USD)", tp) { tp = it }
        Field("Leverage (x)", lev) { lev = it }
        Field("Maintenance margin rate (%)", mmr) { mmr = it }

        Spacer(Modifier.height(8.dp))
        Text("Position metrics", style = MaterialTheme.typography.titleMedium)
        RiskCard(Modifier.fillMaxWidth()) {
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
        RiskCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Suggestion from current inputs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        long.message?.let { Warn("Long: $it") }
        short.message?.let { Warn("Short: $it") }
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
        Text(a, Modifier.weight(1f), fontWeight = w, color = if (header) profitColor() else Color.Unspecified)
        Text(b, Modifier.weight(1f), fontWeight = w, color = if (header) lossColor() else Color.Unspecified)
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
fun CombinedTradingCalculatorScreen(input: CalcInput, accountEquity: Double? = null, onChange: (CalcInput) -> Unit) {
    var selectedTool by remember { mutableStateOf("Position sizing") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Position sizing", "Leverage & liquidation").forEach { tool ->
                FilterChip(selected = selectedTool == tool, onClick = { selectedTool = tool }, label = { Text(tool) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedTool == "Position sizing") CalculatorScreen(input, accountEquity, onChange) else LeverageScreen(input)
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
        RiskCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Lev", Modifier.weight(0.6f), fontWeight = FontWeight.Bold)
                    Text("Max move", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("Long liq", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = profitColor())
                    Text("Short liq", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = lossColor())
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
