package com.risktracker.app
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private object BinanceReversalKlineSource {
 fun fetch(input:String,now:Long=System.currentTimeMillis()):List<ReversalCandle>{
  val symbol=input.trim().uppercase(Locale.US);require(symbol.matches(Regex("[A-Z0-9]{5,20}"))){"Enter a valid Binance symbol, e.g. BTCUSDT."}
  val c=URL("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=15m&limit=500").openConnection() as HttpURLConnection
  c.connectTimeout=12000;c.readTimeout=16000;c.requestMethod="GET"
  try{
   val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}
   if(code !in 200..299){val msg=runCatching{JSONObject(body).optString("msg")}.getOrDefault("request failed");throw IllegalStateException("Binance Futures ($code): $msg")}
   val arr=JSONArray(body)
   val rows=(0 until arr.length()).mapNotNull{i->val a=arr.optJSONArray(i)?:return@mapNotNull null;if(a.length()<7)return@mapNotNull null
    ReversalCandle(a.optLong(0,-1),a.optLong(6,-1),a.optString(1).toDoubleOrNull()?:Double.NaN,a.optString(2).toDoubleOrNull()?:Double.NaN,a.optString(3).toDoubleOrNull()?:Double.NaN,a.optString(4).toDoubleOrNull()?:Double.NaN,a.optString(5).toDoubleOrNull()?:Double.NaN)
   }.filter{it.closeTime in 1 until now}
   require(validReversalCandles(rows)){"Binance returned invalid or out-of-order candles."};return rows
  }finally{c.disconnect()}
 }
}
@Composable fun ConfirmedReversalSignalsScreen(){
 var symbol by remember{mutableStateOf("BTCUSDT")};var candles by remember{mutableStateOf(emptyList<ReversalCandle>())};var signals by remember{mutableStateOf(emptyList<ConfirmedReversalSignal>())}
 var stats by remember{mutableStateOf(ReversalBacktestStats())};var loading by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var updated by remember{mutableStateOf<Long?>(null)}
 val scope=rememberCoroutineScope()
 fun refresh(){if(loading)return;loading=true;error=null;scope.launch{try{
  val data=withContext(Dispatchers.IO){BinanceReversalKlineSource.fetch(symbol)};candles=data
  signals=ConfirmedReversalEngine.detect(symbol,data).takeLast(20).reversed()
  stats=withContext(Dispatchers.Default){ConfirmedReversalEngine.backtest(data)};updated=System.currentTimeMillis()
  if(data.size<60)error="Insufficient closed 15m candle history."
 }catch(e:Exception){error=e.message?:"Unable to load Binance Futures candles."}finally{loading=false}}}
 LaunchedEffect(Unit){refresh()}
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("Confirmed Reversal Signals",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
   Text("Binance USDⓈ-M Futures · 15m · BUY + SHORT",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   OutlinedTextField(symbol,{symbol=it.uppercase(Locale.US).filter(Char::isLetterOrDigit).take(20)},Modifier.fillMaxWidth(),label={Text("Futures symbol")},supportingText={Text("Example: BTCUSDT · public data only")},singleLine=true,keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Characters))
   Button(::refresh,Modifier.fillMaxWidth(),enabled=!loading&&symbol.length>=5){if(loading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Text("Refresh 15m signals")}
   if(loading)Text("Loading closed Binance candles…",style=MaterialTheme.typography.bodySmall)
   error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
   updated?.let{val fmt=remember{SimpleDateFormat("dd MMM yyyy HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}};Text("Updated ${fmt.format(Date(it))} UTC · ${candles.size} closed candles",style=MaterialTheme.typography.labelSmall)}
  }}
  item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
   Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text("Historical replay · ${candles.size} candles",fontWeight=FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Trades",stats.trades.toString());Metric("Win rate","%.1f%%".format(Locale.US,stats.winRatePct));Metric("Avg R","%.2fR".format(Locale.US,stats.averageR))}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Profit factor",stats.profitFactor?.let{"%.2f".format(Locale.US,it)}?:"—");Metric("Max DD","%.2fR".format(Locale.US,stats.maxDrawdownR));Metric("Wins","${stats.wins}/${stats.trades}")}
    Text("Replay uses next-candle open entries, 2R target, stop-first same-bar resolution and estimated 12 bps round-trip costs. Not a forecast.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }}
  if(signals.isEmpty()&&!loading)item{Card{Column(Modifier.padding(16.dp)){Text("No confirmed reversal signals",fontWeight=FontWeight.SemiBold);Text("No setup passed the confirmation rules in this sample. No signal is forced.",style=MaterialTheme.typography.bodySmall)}}}
  items(signals,key={"${it.symbol}-${it.confirmedAt}-${it.side}"}){s->Card(shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${s.symbol} · ${s.side}",fontWeight=FontWeight.Bold,color=if(s.side=="BUY")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error);Text("${s.score}/100",fontWeight=FontWeight.SemiBold)}
   Text("Entry ${s.entry} · Stop ${s.stop} · Target ${s.target}",style=MaterialTheme.typography.bodySmall)
   Text("Confirmed ${SimpleDateFormat("dd MMM HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(s.confirmedAt))} UTC",style=MaterialTheme.typography.labelSmall)
   s.reasons.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
  }}}
  item{Spacer(Modifier.height(16.dp));Text("Method: 3-left / 3-right confirmed pivots, RSI divergence or extreme momentum, rejection wick and a closed-candle break of the pivot candle. Advisory only; no orders are placed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }
}
@Composable private fun Metric(label:String,value:String){Column{Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)}}
