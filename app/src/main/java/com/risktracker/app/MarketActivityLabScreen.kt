package com.risktracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MarketActivityLabScreen() {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("market_activity_lab_v1",0)}
    var config by remember { mutableStateOf(MarketActivityPreferences.loadConfig(context)) }
    var symbolsDraft by remember { mutableStateOf(config.symbols.joinToString(", ")) }
    var configStatus by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(MarketActivityPreferences.loadEvents(context)) }
    val symbols=config.symbols
    var interval by remember{mutableStateOf(MarketInterval.M15)}
    var rows by remember{mutableStateOf<List<ActivityAnalysis>>(emptyList())}
    var tickerMap by remember{mutableStateOf<Map<String,MarketTicker>>(emptyMap())}
    var selected by remember{mutableStateOf("BTCUSDT")}
    var watch by remember{mutableStateOf(prefs.getStringSet("watchlist",setOf("BTCUSDT","ETHUSDT"))?.toSet()?:setOf("BTCUSDT","ETHUSDT"))}
    var loading by remember{mutableStateOf(false)}
    var errors by remember{mutableStateOf<List<String>>(emptyList())}
    var updated by remember{mutableLongStateOf(0L)}
    var replay by remember{mutableStateOf<MarketActivityEngine.ReplayStats?>(null)}
    var chartCandles by remember{mutableStateOf<List<MarketCandle>>(emptyList())}
    val scope=rememberCoroutineScope()
    fun refresh(){
        if(loading)return
        loading=true
        scope.launch{
            tickerMap=runCatching{MarketActivityDataSource.tickers()}.getOrDefault(emptyMap())
            val result=symbols.map{sym->async{sym to runCatching{MarketActivityEngine.analyze(sym,interval,MarketActivityDataSource.candles(sym,interval),config.thresholds)}.getOrNull()}}.awaitAll()
            result.mapNotNull{it.second}.forEach{MarketActivityPreferences.recordAnalysis(context,it)}
            events=MarketActivityPreferences.loadEvents(context)
            rows=result.mapNotNull{it.second}.filter{it.score.total>=config.minimumScore};errors=result.filter{it.second==null}.map{it.first}
            if(rows.none{it.symbol==selected}&&rows.isNotEmpty())selected=rows.first().symbol
            updated=System.currentTimeMillis()
            loading=false
        }
    }
    LaunchedEffect(interval){refresh()}
    LaunchedEffect(selected,interval){
        val loaded=runCatching{MarketActivityDataSource.candles(selected,interval)}.getOrDefault(emptyList())
        chartCandles=loaded
        replay=if(loaded.isNotEmpty()) MarketActivityEngine.replay(loaded) else null
    }
    val a=rows.firstOrNull{it.symbol==selected}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text("Market Activity Lab",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("Early movement detection · historical research · paper-only",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Public Binance USD-M Futures data · read-only · no orders",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Scanner settings",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(value=symbolsDraft,onValueChange={symbolsDraft=it},modifier=Modifier.fillMaxWidth(),label={Text("Binance USD-M symbols")},supportingText={Text("Comma-separated · up to 40 pairs")},minLines=2)
            Text("Range compression · %.1f%%".format(config.thresholds.rangeCompressionPercent),style=MaterialTheme.typography.bodySmall)
            Slider(value=config.thresholds.rangeCompressionPercent.toFloat(),onValueChange={config=config.copy(thresholds=config.thresholds.copy(rangeCompressionPercent=it.toDouble()))},valueRange=0.5f..8f)
            Text("WATCH relative volume · %.1f×".format(config.thresholds.relativeVolumeWatch),style=MaterialTheme.typography.bodySmall)
            Slider(value=config.thresholds.relativeVolumeWatch.toFloat(),onValueChange={config=config.copy(thresholds=config.thresholds.copy(relativeVolumeWatch=it.toDouble()))},valueRange=1f..5f)
            Text("Breakout relative volume · %.1f×".format(config.thresholds.relativeVolumeBreakout),style=MaterialTheme.typography.bodySmall)
            Slider(value=config.thresholds.relativeVolumeBreakout.toFloat(),onValueChange={config=config.copy(thresholds=config.thresholds.copy(relativeVolumeBreakout=it.toDouble()))},valueRange=1f..5f)
            Text("Minimum scanner score · ${config.minimumScore}/100",style=MaterialTheme.typography.bodySmall)
            Slider(value=config.minimumScore.toFloat(),onValueChange={config=config.copy(minimumScore=it.toInt())},valueRange=0f..80f,steps=15)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                Button(onClick={
                    runCatching {
                        val next=config.copy(symbols=MarketActivityPreferences.parseSymbols(symbolsDraft))
                        MarketActivityPreferences.saveConfig(context,next)
                        config=next
                        if(selected !in next.symbols) selected=next.symbols.first()
                        configStatus="Settings saved."
                        refresh()
                    }.onFailure{configStatus=it.message ?: "Unable to save settings."}
                }){Text("Save & scan")}
            }
            if(configStatus.isNotBlank())Text(configStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Market scanner",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);TextButton(onClick={refresh()},enabled=!loading){Text(if(loading)"Loading…" else "Refresh")}}
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){MarketInterval.values().forEach{tf->FilterChip(selected=interval==tf,onClick={interval=tf},label={Text(tf.label)})}}
            if(updated>0)Text("Updated "+SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(updated)),style=MaterialTheme.typography.labelSmall)
            if(loading&&rows.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
            if(errors.isNotEmpty())Text("Unavailable: "+errors.joinToString()+". Missing values are not estimated.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            rows.sortedByDescending{it.score.total}.forEach{x->
                Row(Modifier.fillMaxWidth().clickable{selected=x.symbol}.padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Column(Modifier.weight(1f)){Text(x.symbol,fontWeight=FontWeight.SemiBold);val t=tickerMap[x.symbol];Text((t?.let{String.format(Locale.US,"%.2f%% · Quote vol %.0f",it.changePercent,it.quoteVolume)}?:"24h ticker unavailable")+" · "+x.state.name.replace('_',' '),style=MaterialTheme.typography.labelSmall,color=activityStateColor(x.state))}
                    Text("%.5f".format(tickerMap[x.symbol]?.lastPrice?:x.last.close),style=MaterialTheme.typography.bodySmall)
                    Text(x.score.total.toString()+"/100",fontWeight=FontWeight.Bold)
                    Text(if(watch.contains(x.symbol))"★" else "☆",Modifier.clickable{watch=if(watch.contains(x.symbol))watch-x.symbol else watch+x.symbol;prefs.edit().putStringSet("watchlist",watch).apply()},color=MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
            if(rows.isEmpty()&&!loading)Text("No candle data loaded. Check connection and refresh.",style=MaterialTheme.typography.bodySmall)
        }}}
        item {
            Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Setup & alert history",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    TextButton(onClick={MarketActivityPreferences.clearEvents(context);events=emptyList()}) { Text("Clear") }
                }
                Text("Transitions are saved on this device. Invalidation is based on a completed candle closing back through the setup range; this is an in-app history, not a push notification.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(events.isEmpty()) Text("No setup transitions recorded yet.",style=MaterialTheme.typography.bodySmall)
                events.take(12).forEach { event ->
                    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(event.symbol+" · "+event.interval.label,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                            Text(event.state.name.replace('_',' '),color=activityStateColor(event.state),style=MaterialTheme.typography.labelSmall)
                        }
                        Text(event.direction.name+" · "+event.message+" · "+String.format(Locale.US,"%.5f",event.price),style=MaterialTheme.typography.bodySmall)
                        Text(SimpleDateFormat("MMM d, HH:mm",Locale.getDefault()).format(Date(event.createdAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            } }
        }
        if(a!=null){
            item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(a.symbol+" · "+interval.label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Current reference  "+(tickerMap[a.symbol]?.lastPrice?:a.last.close),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text("24h change  "+(tickerMap[a.symbol]?.let{"%.2f%%".format(it.changePercent)}?:"Unavailable")+" · Quote volume  "+(tickerMap[a.symbol]?.let{"%.0f USDT".format(it.quoteVolume)}?:"Unavailable"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Last completed candle · "+SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(a.last.closeTime)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Candlesticks + volume · recent 60 bars",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                val candles=chartCandles
                if(candles.isNotEmpty())Canvas(Modifier.fillMaxWidth().height(170.dp)){
                    val ds=candles.takeLast(60);if(ds.isNotEmpty()){val low=ds.minOf{it.low};val high=ds.maxOf{it.high};val span=(high-low).takeIf{it>0}?:1.0;val step=size.width/ds.size
                        ds.forEachIndexed{i,c->val x=step*(i+.5f);val y1=size.height*.05f+((high-c.high)/span).toFloat()*size.height*.72f;val y2=size.height*.05f+((high-c.low)/span).toFloat()*size.height*.72f;val yo=size.height*.05f+((high-c.open)/span).toFloat()*size.height*.72f;val yc=size.height*.05f+((high-c.close)/span).toFloat()*size.height*.72f;val col=if(c.close>=c.open)Color(0xFF35B77A)else Color(0xFFE16B70);drawLine(col,Offset(x,y1),Offset(x,y2),1.5f);drawLine(col,Offset(x,yo),Offset(x,yc),maxOf(3f,step*.55f));val maxVol=ds.maxOf{it.volume}.takeIf{it>0}?:1.0;drawLine(col.copy(alpha=.35f),Offset(x,size.height),Offset(x,size.height-(c.volume/maxVol).toFloat()*size.height*.16f),maxOf(2f,step*.5f))}
                    }
                }else Text("Chart data unavailable.",style=MaterialTheme.typography.bodySmall)
                Text("Activity score  "+a.score.total+"/100",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                val scores=listOf("Relative volume anomaly" to a.score.relativeVolume,"Volatility expansion" to a.score.volatilityExpansion,"Range compression" to a.score.rangeCompression,"Bollinger behavior" to a.score.bollingerBehavior,"EMA alignment" to a.score.emaAlignment,"MACD momentum" to a.score.macdMomentum,"RSI confirmation" to a.score.rsiConfirmation)
                scores.forEach{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(it.first,style=MaterialTheme.typography.bodySmall);Text(it.second.toString(),fontWeight=FontWeight.SemiBold)}}
                HorizontalDivider()
                Text("Indicators",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                val i=a.indicators
                val values=listOf("EMA 9 / 20 / 50 / 200" to listOf(i.ema9,i.ema20,i.ema50,i.ema200).joinToString(" / "){v->v?.let{"%.4f".format(it)}?:"—"},"RSI 14" to (i.rsi14?.let{"%.1f".format(it)}?:"—"),"MACD / signal" to (i.macd?.let{"%.4f".format(it)}?:"—")+" / "+(i.macdSignal?.let{"%.4f".format(it)}?:"—"),"Bollinger width" to (i.bbWidthPercent?.let{"%.2f%%".format(it)}?:"—"),"ATR 14" to (i.atr14?.let{"%.5f".format(it)}?:"—"),"VWAP (20 bars)" to (i.vwap?.let{"%.5f".format(it)}?:"—"),"Historical volatility (20 bars)" to (i.historicalVolatility?.let{"%.2f%%".format(it)}?:"—"),"Relative volume" to (i.relativeVolume?.let{"%.2f×".format(it)}?:"—"))
                values.forEach{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(it.first,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(it.second,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium)}}
                a.explanation.forEach{Text("• "+it,style=MaterialTheme.typography.bodySmall)}
            }}}
            item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Historical replay · selected pair",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                val r=replay
                if(r==null)Text("Replay unavailable.",style=MaterialTheme.typography.bodySmall)else{
                    Text("Setups: "+r.setups+" · Positive after costs: "+r.successful+" · Negative: "+r.failed,style=MaterialTheme.typography.bodySmall)
                    Text("False-positive rate: "+(r.falsePositiveRate?.let{"%.1f%%".format(it)}?:"Insufficient sample"),style=MaterialTheme.typography.bodySmall)
                    Text("Average subsequent return: "+(r.averageReturnPercent?.let{"%.3f%%".format(it)}?:"—"),style=MaterialTheme.typography.bodySmall)
                    Text("Max adverse / favorable: "+(r.maxAdversePercent?.let{"%.2f%%".format(it)}?:"—")+" / "+(r.maxFavorablePercent?.let{"%.2f%%".format(it)}?:"—"),style=MaterialTheme.typography.bodySmall)
                }
                Text("Causal replay enters at the next candle open, exits after 12 candles and deducts configurable-in-code fee/slippage assumptions. Small samples are not predictive evidence.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}}
        }
        item{Text("WATCH is an observed developing anomaly, not a price forecast. Confirmed historical breakout means a completed candle closed outside the previous 20-candle range with configured relative-volume support. Bullish and bearish observations are both supported. Setup transitions and invalidations are stored locally; they do not trigger push notifications.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
private fun activityStateColor(s:ActivityState)=when(s){ActivityState.WATCH->Color(0xFFE5AC52);ActivityState.CONFIRMED_HISTORICAL_BREAKOUT->Color(0xFF35B77A);ActivityState.INVALIDATED->Color(0xFFE16B70);ActivityState.NO_SETUP->Color.Gray}
