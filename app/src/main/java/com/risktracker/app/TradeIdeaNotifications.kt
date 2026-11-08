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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
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
    val riskDistance: Double = kotlin.math.abs(entry - stop), val bestPrice: Double = entry,
    val horizon: String = ""
) {
    val key: String get() = if (horizon.isBlank()) symbol else "$symbol|$horizon|$side|$setup"
}

object TradeIdeaNotifications {
    const val PREFS = "trade_idea_alerts_v1"
    private const val CHANNEL = "trade_idea_alerts"
    private const val UNIQUE_WORK = "trade_idea_market_monitor"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isReadyEnabled(context: Context): Boolean = prefs(context).getBoolean("ready_enabled", false)

    fun isEntryEnabled(context: Context): Boolean =
        prefs(context).getBoolean("entry_enabled", prefs(context).getBoolean("enabled", false))

    fun isEnabled(context: Context): Boolean = isReadyEnabled(context) || isEntryEnabled(context)

    fun setReadyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("ready_enabled", enabled).apply()
        if (!enabled) TradeIdeaReadyAlertStore.clear(context)
    }

    fun setEntryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("entry_enabled", enabled).putBoolean("enabled", enabled).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) = setEntryEnabled(context, enabled)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TradeIdeaMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        // Disable notifications only; the same periodic worker must keep refreshing idea caches.
        setEnabled(context, false)
    }

    internal fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(context: Context, symbol: String, event: String, title: String, detail: String, strategy: String = "") {
        if (!hasPermission(context)) return
        if (event == "ready" && !isReadyEnabled(context)) return
        if (event != "ready" && !isEntryEnabled(context)) return
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
        runCatching { NotificationManagerCompat.from(context).notify((symbol + strategy + event).hashCode(), notification) }
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
                    row.optDouble("bestPrice", row.getDouble("entry")), row.optString("horizon", "")
                )
                p.key to p
            }.getOrNull()
        }.toMap().toMutableMap()
    }

    fun save(context: Context, plans: Map<String, TradeIdeaAlertPlan>) {
        val rows = JSONArray()
        plans.values.forEach { p ->
            rows.put(JSONObject().put("symbol", p.symbol).put("side", p.side).put("entry", p.entry)
                .put("stop", p.stop).put("target1", p.target1).put("target2", p.target2).put("setup", p.setup)
                .put("target1Reached", p.target1Reached).put("riskDistance", p.riskDistance).put("bestPrice", p.bestPrice)
                .put("horizon", p.horizon))
        }
        context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, rows.toString()).apply()
    }
}

class TradeIdeaMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            BinanceMarketScanner.initialize(context)
            TradeIdeaScanHealthStore.started(context)

            // Refresh the same Trade Ideas snapshot used by the foreground screen.
            val allIdeas = BinanceMarketScanner.scan(progress = { })
            val scanCompletedAt = System.currentTimeMillis()
            TradeIdeaCache.save(context, scanCompletedAt, allIdeas)
            TradeIdeaScanHealthStore.succeeded(context)

            val alertIdeas = allIdeas

            // Scanning and cache refresh do not require notification permission or opt-in.
            // Only entry/exit alerts and paper-plan management are gated by the user's alert switch.
            val readyAlertsEnabled = TradeIdeaNotifications.isReadyEnabled(context)
            val entryAlertsEnabled = TradeIdeaNotifications.isEntryEnabled(context)
            if (!TradeIdeaNotifications.hasPermission(context) || (!readyAlertsEnabled && !entryAlertsEnabled)) {
                return Result.success()
            }

            if (readyAlertsEnabled) {
                val readyIdeas = alertIdeas.filter { isReadyAlertCandidate(it) }
                val currentReadyKeys = readyIdeas.map { alertKey(it) }.toSet()
                val previousReadyKeys = TradeIdeaReadyAlertStore.load(context)
                val stillAlerted = previousReadyKeys.intersect(currentReadyKeys)
                val newlyReady = readyIdeas.filter { alertKey(it) !in stillAlerted }
                    .sortedByDescending { it.score }
                    .take(3)
                newlyReady.forEach { idea ->
                    TradeIdeaNotifications.notify(
                        context, idea.symbol, "ready",
                        "READY setup approaching: ${idea.symbol}",
                        "${idea.side} setup qualified · score ${idea.score}/100 · price ~${idea.last} · planned entry ~${idea.entry} · stop ~${idea.stop}. Waiting for entry confirmation; no order placed.",
                        idea.horizon + "|" + idea.setup
                    )
                }
                TradeIdeaReadyAlertStore.save(context, stillAlerted + newlyReady.map { alertKey(it) })
            } else {
                TradeIdeaReadyAlertStore.clear(context)
            }

            if (!entryAlertsEnabled) return Result.success()

            val plans = TradeIdeaAlertPlanStore.load(context)
            val entrySlots = (6 - plans.size).coerceAtLeast(0)
            alertIdeas.filter {
                it.entryTriggered && it.score >= 75 && it.volumeRatio >= 1.2 &&
                    (it.fundingRate == null || kotlin.math.abs(it.fundingRate) <= 0.001) &&
                    (it.oiChangePct == null || it.oiChangePct > -1.0) &&
                    it.side in setOf("LONG", "SHORT") && "${it.symbol}|${it.horizon}|${it.side}|${it.setup}" !in plans
            }.sortedByDescending { it.score }.take(entrySlots).forEach { idea ->
                val strategyKey = "${idea.symbol}|${idea.horizon}|${idea.side}|${idea.setup}"
                plans[strategyKey] = TradeIdeaAlertPlan(
                    idea.symbol, idea.side, idea.entry, idea.stop, idea.target1, idea.target2, idea.setup,
                    horizon = idea.horizon
                )
                TradeIdeaPaperJournal.recordEntry(context, idea.symbol, idea.setup, idea.side, idea.entry, idea.stop, strategyKey)
                TradeIdeaNotifications.notify(
                    context, idea.symbol, "entry", "${idea.side} setup confirmed: ${idea.symbol}",
                    "Entry trigger ~${idea.entry}; stop ~${idea.stop}; target 1 ~${idea.target1}; target 2 ~${idea.target2}. Checklist score ${idea.score}/100. Alert only; no order placed.",
                    idea.horizon + "|" + idea.setup
                )
            }

            val currentIdeas = allIdeas.associateBy { "${it.symbol}|${it.horizon}" }
            plans.toMap().forEach { (planKey, plan) ->
                val symbol = plan.symbol
                val current = if (plan.horizon.isBlank()) allIdeas.firstOrNull { it.symbol == symbol }
                    else currentIdeas["${plan.symbol}|${plan.horizon}"]
                if (current != null && current.side != "WAIT" && current.side != plan.side) {
                    TradeIdeaNotifications.notify(
                        context, symbol, "invalid", "Setup invalidated: $symbol",
                        "Multi-timeframe direction no longer agrees with the original ${plan.side} setup (${plan.horizon}). Reassess manually; no order was changed.",
                        plan.horizon + "|" + plan.setup
                    )
                    TradeIdeaPaperJournal.recordExit(context, symbol, current.last, "INVALIDATED", plan.riskDistance, if (plan.horizon.isBlank()) "" else planKey)
                    plans.remove(planKey)
                    return@forEach
                }
                val price = BinanceMarketScanner.latestPrice(symbol) ?: return@forEach
                val managedPlan = if (plan.target1Reached && plan.riskDistance > 0.0) {
                    val best = if (plan.side == "LONG") maxOf(plan.bestPrice, price) else minOf(plan.bestPrice, price)
                    val candidateStop = if (plan.side == "LONG") maxOf(plan.entry, best - plan.riskDistance)
                        else minOf(plan.entry, best + plan.riskDistance)
                    val tightenedStop = if (plan.side == "LONG") maxOf(plan.stop, candidateStop)
                        else minOf(plan.stop, candidateStop)
                    plan.copy(stop = tightenedStop, bestPrice = best).also { plans[planKey] = it }
                } else plan
                val stopHit = if (managedPlan.side == "LONG") price <= managedPlan.stop else price >= managedPlan.stop
                val target1Hit = if (managedPlan.side == "LONG") price >= managedPlan.target1 else price <= managedPlan.target1
                val target2Hit = if (managedPlan.side == "LONG") price >= managedPlan.target2 else price <= managedPlan.target2
                when {
                    stopHit -> {
                        val stopLabel = if (managedPlan.target1Reached) "Breakeven/trailing stop reached" else "Initial stop reached"
                        TradeIdeaNotifications.notify(context, symbol, "stop", "$stopLabel: $symbol",
                            "Latest price ~${price}; managed stop reference ~${managedPlan.stop}. Review any real position immediately.", plan.horizon + "|" + plan.setup)
                        TradeIdeaPaperJournal.recordExit(context, symbol, managedPlan.stop,
                            if (managedPlan.target1Reached) "TRAILING_STOP" else "STOP", managedPlan.riskDistance, if (plan.horizon.isBlank()) "" else planKey)
                        plans.remove(planKey)
                    }
                    target2Hit -> {
                        TradeIdeaNotifications.notify(context, symbol, "target2", "Target 2 reached: $symbol",
                            "Latest price ~${price}; target 2 reference ~${managedPlan.target2}. The paper plan books 50% at 2R and closes the remainder at 3R (2.5R total). No order placed.", plan.horizon + "|" + plan.setup)
                        TradeIdeaPaperJournal.recordExit(context, symbol, managedPlan.target2, "TARGET2", managedPlan.riskDistance, if (plan.horizon.isBlank()) "" else planKey)
                        plans.remove(planKey)
                    }
                    !managedPlan.target1Reached && target1Hit -> {
                        TradeIdeaPaperJournal.recordPartial(context, symbol, strategyKey = if (plan.horizon.isBlank()) "" else planKey)
                        TradeIdeaNotifications.notify(context, symbol, "target1", "Target 1 reached: $symbol",
                            "Latest price ~${price}; 50% scale-out reference ~${managedPlan.target1} (2R). The remaining plan's stop moves to breakeven, then trails 1R behind the best observed price. No order placed.", plan.horizon + "|" + plan.setup)
                        plans[planKey] = managedPlan.copy(target1Reached = true, stop = managedPlan.entry, bestPrice = price)
                    }
                }
            }
            TradeIdeaAlertPlanStore.save(context, plans)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TradeIdeaScanHealthStore.failed(context, e.message ?: "Background market scan failed")
            Result.retry()
        }
    }
}

private object TradeIdeaReadyAlertStore {
    private const val KEY = "active_ready_alerts"

    fun load(context: Context): Set<String> = runCatching {
        JSONArray(context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    }.getOrElse { JSONArray() }.let { rows ->
        (0 until rows.length()).map { rows.optString(it) }.filter { it.isNotBlank() }.toSet()
    }

    fun save(context: Context, keys: Set<String>) {
        val rows = JSONArray().apply { keys.sorted().forEach { put(it) } }
        context.getSharedPreferences(TradeIdeaNotifications.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, rows.toString()).apply()
    }

    fun clear(context: Context) = save(context, emptySet())
}

private fun alertKey(idea: TradeIdea): String =
    "${idea.symbol}|${idea.horizon}|${idea.side}|${idea.setup}"

private fun isReadyAlertCandidate(idea: TradeIdea): Boolean {
    if (idea.side !in setOf("LONG", "SHORT") || idea.entryTriggered || idea.score < TradeIdeasV2.MIN_SCORE) return false
    if (!idea.entry.isFinite() || !idea.stop.isFinite() || !idea.target1.isFinite() ||
        !idea.target2.isFinite() || !idea.last.isFinite() ||
        idea.entry <= 0.0 || idea.stop <= 0.0 || idea.target1 <= 0.0 || idea.target2 <= 0.0) return false
    val geometryValid = if (idea.side == "LONG") {
        idea.stop < idea.entry && idea.target1 > idea.entry && idea.target2 > idea.target1
    } else {
        idea.stop > idea.entry && idea.target1 < idea.entry && idea.target2 < idea.target1
    }
    val warning = idea.warning.lowercase(java.util.Locale.US)
    val warningBlocksEntry = warning.startsWith("block:") ||
        listOf("overbought", "oversold", "elevated", "falling sharply", "weak").any { it in warning }
    if (!geometryValid || warningBlocksEntry) return false
    val riskDistance = kotlin.math.abs(idea.entry - idea.stop)
    if (riskDistance <= 0.0) return false
    val approachBand = maxOf(idea.entry * 0.0015, riskDistance * 0.25)
    return if (idea.side == "LONG") {
        idea.last < idea.entry && idea.entry - idea.last <= approachBand
    } else {
        idea.last > idea.entry && idea.last - idea.entry <= approachBand
    }
}
