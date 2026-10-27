package com.risktracker.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal fun newlyTriggeredAlertKeys(previous: Set<String>, current: Set<String>): Set<String> =
    current - previous

object RiskAlertNotifications {
    private const val UNIQUE_WORK = "risk_alert_background_checks"
    const val CHANNEL_ID = "risk_alerts"
    private const val PREFS = "risk_alert_notification_state_v1"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RiskAlertNotificationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Risk alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications when a configured risk alert becomes active"
                }
            )
        }
    }

    internal fun post(context: Context, alert: RiskAlert) {
        if (!hasNotificationPermission(context)) return
        createChannel(context)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(alert.title)
            .setContentText(alert.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
        NotificationManagerCompat.from(context).notify(alert.key.hashCode(), notification)
    }

    internal fun readPreviouslyActive(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("active_keys", emptySet())?.toSet() ?: emptySet()

    internal fun saveActive(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("active_keys", keys.toSet()).apply()
    }
}

class RiskAlertNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
        val context = applicationContext
        val prefs = context.getSharedPreferences("risk_alert_settings_v1", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_notifications_enabled", false)) {
            RiskAlertNotifications.saveActive(context, emptySet())
            return Result.success()
        }
        if (!RiskAlertNotifications.hasNotificationPermission(context)) return Result.success()

        val store = Store(context)
        val entries = store.loadEntries()
        val trades = store.loadTrades()
        val imported = BinanceSyncData.load(context)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dailyPnl = reconcileDailyPnl(entries, imported, trades).firstOrNull { it.date == today }?.selectedPnl

        val ordered = entries.mapNotNull { entry ->
            canonicalPnlDate(entry.date)?.let { it to entry.closing }
        }.sortedBy { it.first }
        var peak = Double.NaN
        var maxDrawdown = 0.0
        ordered.forEach { (_, balance) ->
            if (balance.isFinite() && balance > 0.0) {
                peak = if (peak.isNaN()) balance else maxOf(peak, balance)
                if (peak > 0.0) maxDrawdown = maxOf(maxDrawdown, ((peak - balance) / peak * 100.0).coerceAtLeast(0.0))
            }
        }
        val consecutiveLosses = trades.mapNotNull { trade ->
            canonicalPnlDate(trade.date)?.let { it to trade.pnl }
        }.sortedByDescending { it.first }.takeWhile { it.second < 0.0 }.size
        val syncStatus = BinanceAutoJournalStore.syncStatus(context)
        val hasCredentials = BinanceCredentialStore(context).read() != null
        val now = System.currentTimeMillis()
        val staleHours = prefs.getInt("stale_hours", 24).toLong().coerceIn(1L, 720L)
        val alerts = RiskAlertEngine.evaluate(
            dailyPnl, store.maxDailyLoss, prefs.getBoolean("daily_enabled", true),
            if (ordered.isEmpty()) null else maxDrawdown,
            prefs.getFloat("drawdown_threshold", 10f).toDouble(), prefs.getBoolean("drawdown_enabled", true),
            hasCredentials, syncStatus.lastSuccess, now, staleHours * 3_600_000L,
            prefs.getBoolean("stale_enabled", true), consecutiveLosses,
            prefs.getInt("streak_limit", 3), prefs.getBoolean("streak_enabled", true)
        )

        val currentKeys = alerts.map { it.key }.toSet()
        val previousKeys = RiskAlertNotifications.readPreviouslyActive(context)
        newlyTriggeredAlertKeys(previousKeys, currentKeys).forEach { key ->
            alerts.firstOrNull { it.key == key }?.let { RiskAlertNotifications.post(context, it) }
        }
        RiskAlertNotifications.saveActive(context, currentKeys)
        Result.success()
    } catch (_: SecurityException) {
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
    }
}
