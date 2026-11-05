package com.risktracker.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class TradeIdeaAlertPlan(
    val symbol: String, val side: String, val entry: Double, val stop: Double,
    val target1: Double, val target2: Double, val setup: String, val target1Reached: Boolean = false,
    val riskDistance: Double = kotlin.math.abs(entry - stop), val bestPrice: Double = entry
)

object TradeIdeaNotifications {
    const val PREFS = "trade_idea_alerts_v1"
    private const val CHANNEL = "trade_idea_alerts"
    private const val UNIQUE_WORK = "trade_idea_market_monitor"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TradeIdeaMonitorWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }

    internal fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(context: Context, symbol: String, event: String, title: String, detail: String) {
        if (!hasPermission(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Trade idea entry & exit alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Conditional entry, stop and target notifications" })
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(context, 7301, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }.build()
        runCatching { NotificationManagerCompat.from(context).notify((symbol + event).hashCode(), notification) }
    }
}

private object TradeIdeaAlertPlanStore {
    private const val KEY = "active_plans"

    fun load(context: Context): MutableMap<String, TradeIdeaAlertPlan> {
        val json = runCatching {
            JSONArray(context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        }.getOrElse { JSONArray() }
        return (0 until json.length()).mapNotNull { i ->
            runCatching {
                val row = json.getJSONObject(i)
                val p = TradeIdeaAlertPlan(
                    row.getString("symbol"), row.getString("side"), row.getDouble("entry"),
                    row.getDouble("stop"), row.getDouble("target1"), row.getDouble("target2"),
                    row.optString("setup", "Trend continuation"), row.optBoolean("target1Reached", false),
                    row.optDouble("riskDistance", kotlin.math.abs(row.getDouble("entry") - row.getDouble("stop"))),
                    row.optDouble("bestPrice", row.getDouble("entry"))
                )
                p.symbol to p
            }.getOrNull()
        }.toMap().toMutableMap()
    }

    fun save(context: Context, plans: Map<String, TradeIdeaAlertPlan>) {
        val rows = JSONArray()
        plans.values.forEach { p ->
            rows.put(JSONObject().put("symbol", p.symbol).put("side", p.side).put("entry", p.entry)
                .put("stop", p.stop).put("target1", p.target1).put("target2", p.target2).put("setup", p.setup)
                .put("target1Reached", p.target1Reached).put("riskDistance", p.riskDistance).put("bestPrice", p.bestPrice))
        }
        context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, rows.toString()).apply()
    }
}

class TradeIdeaMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!TradeIdeaNotifications.isEnabled(context) || !TradeIdeaNotifications.hasPermission(context)) return Result.success()
        return try {
            val ideas = BinanceMarketScanner.scan { }
            val plans = TradeIdeaAlertPlanStore.load(context)
            ideas.filter {
                it.entryTriggered && it.score >= 75 && it.volumeRatio >= 1.2 &&
                    (it.fundingRate == null || kotlin.math.abs(it.fundingRate) <= 0.001) &&
                    (it.oiChangePct == null || it.oiChangePct > -1.0) &&
                    it.side in setOf("LONG", "SHORT") && it.symbol !in plans
            }.sortedByDescending { it.score }.take((3 - plans.size).coerceAtLeast(0)).forEach { idea ->
                plans[idea.symbol] = TradeIdeaAlertPlan(
                    idea.symbol, idea.side, idea.entry, idea.stop, idea.target1, idea.target2, idea.setup
                )
                TradeIdeaPaperJournal.recordEntry(context, idea.symbol, idea.setup, idea.side, idea.entry, idea.stop)
                TradeIdeaNotifications.notify(
                    context, idea.symbol, "entry", "${idea.side} setup confirmed: ${idea.symbol}",
                    "15M breakout confirmed with volume. Entry trigger ~${idea.entry}; stop ~${idea.stop}; target 1 ~${idea.target1}; target 2 ~${idea.target2}. Checklist score ${idea.score}/100. Alert only; no order placed."
                )
            }

            val currentIdeas = ideas.associateBy { it.symbol }
            plans.toMap().forEach { (symbol, plan) ->
                val current = currentIdeas[symbol]
                if (current != null && current.side != "WAIT" && current.side != plan.side) {
                    TradeIdeaNotifications.notify(
                        context, symbol, "invalid", "Setup invalidated: $symbol",
                        "Multi-timeframe direction no longer agrees with the original ${plan.side} setup. Reassess manually; no order was changed."
                    )
                    TradeIdeaPaperJournal.recordExit(context, symbol, current.last, "INVALIDATED", plan.riskDistance)
                    plans.remove(symbol)
                    return@forEach
                }
                val price = BinanceMarketScanner.latestPrice(symbol) ?: return@forEach
                // After the 50% scale-out at 1.5R, protect the remainder at breakeven
                // and trail by 1R from the best price observed by this worker.
                val managedPlan = if (plan.target1Reached && plan.riskDistance > 0.0) {
                    val best = if (plan.side == "LONG") maxOf(plan.bestPrice, price) else minOf(plan.bestPrice, price)
                    val candidateStop = if (plan.side == "LONG") {
                        maxOf(plan.entry, best - plan.riskDistance)
                    } else {
                        minOf(plan.entry, best + plan.riskDistance)
                    }
                    val tightenedStop = if (plan.side == "LONG") maxOf(plan.stop, candidateStop)
                        else minOf(plan.stop, candidateStop)
                    plan.copy(stop = tightenedStop, bestPrice = best).also { plans[symbol] = it }
                } else plan
                val stopHit = if (managedPlan.side == "LONG") price <= managedPlan.stop else price >= managedPlan.stop
                val target1Hit = if (managedPlan.side == "LONG") price >= managedPlan.target1 else price <= managedPlan.target1
                val target2Hit = if (managedPlan.side == "LONG") price >= managedPlan.target2 else price <= managedPlan.target2
                when {
                    stopHit -> {
                        val stopLabel = if (managedPlan.target1Reached) "Breakeven/trailing stop reached" else "Initial stop reached"
                        TradeIdeaNotifications.notify(context, symbol, "stop", "$stopLabel: $symbol",
                            "Latest price ~${price}; managed stop reference ~${managedPlan.stop}. Review any real position immediately.")
                        TradeIdeaPaperJournal.recordExit(context, symbol, managedPlan.stop,
                            if (managedPlan.target1Reached) "TRAILING_STOP" else "STOP", managedPlan.riskDistance)
                        plans.remove(symbol)
                    }
                    target2Hit -> {
                        TradeIdeaNotifications.notify(context, symbol, "target2", "Target 2 reached: $symbol",
                            "Latest price ~${price}; target 2 reference ~${managedPlan.target2}. The paper plan books 50% at 1.5R and closes the remainder at 2.5R (2.0R total). No order placed.")
                        TradeIdeaPaperJournal.recordExit(context, symbol, managedPlan.target2, "TARGET2", managedPlan.riskDistance)
                        plans.remove(symbol)
                    }
                    !managedPlan.target1Reached && target1Hit -> {
                        TradeIdeaPaperJournal.recordPartial(context, symbol)
                        TradeIdeaNotifications.notify(context, symbol, "target1", "Target 1 reached: $symbol",
                            "Latest price ~${price}; 50% scale-out reference ~${managedPlan.target1} (1.5R). The remaining plan's stop moves to breakeven, then trails 1R behind the best observed price. No order placed.")
                        plans[symbol] = managedPlan.copy(target1Reached = true, stop = managedPlan.entry, bestPrice = price)
                    }
                }
            }
            TradeIdeaAlertPlanStore.save(context, plans)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
