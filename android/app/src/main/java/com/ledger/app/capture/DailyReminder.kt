package com.ledger.app.capture

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
import com.ledger.app.MainActivity
import com.ledger.app.R
import com.ledger.app.data.ApiClient
import com.ledger.app.data.AppUpdater
import com.ledger.app.data.Prefs
import java.time.LocalDate
import java.time.LocalTime

/** One notification a day with the server's morning analysis ("昨天花了..., 本账期还能花..."). It rides on the
 *  15-minute maintenance job, so there is no extra background service. */
object DailyReminder {
    private const val FILE = "ledger_capture"
    private const val KEY_LAST = "last_daily_notice"
    private const val CHANNEL = "daily_report"

    fun canNotify(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Bills due by the end of the month (rent, parking, the two servers...): one notification a day from 5 days before. */
    suspend fun maybeRemind(c: Context) {
        if (!Prefs.isLoggedIn(c) || !canNotify(c)) return
        val now = LocalTime.now()
        if (now.hour < 9 || now.hour >= 22) return
        val prefs = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("last_bill_notice", null) == today) return
        val bills = try { ApiClient.create(c).billReminders() } catch (_: Exception) { return }
        if (bills.isEmpty()) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("bills", "账单提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = bills.joinToString("\n") { "· ${it.name} ¥${it.amount.removeSuffix(".00")}" + (if (it.days_left == 0) "(今天)" else "(还剩${it.days_left}天)") }
        val n = NotificationCompat.Builder(c, "bills")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("月底前要交 ${bills.size} 笔")
            .setContentText(bills.joinToString("、") { it.name })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(7002, n)
        prefs.edit().putString("last_bill_notice", today).apply()
    }

    suspend fun maybeNotify(c: Context) {
        if (!Prefs.isLoggedIn(c) || !canNotify(c)) return
        val now = LocalTime.now()
        if (now.hour < 8 || now.hour >= 22) return
        val prefs = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST, null) == today) return

        val report = try {
            ApiClient.create(c).latestReport()
        } catch (e: java.io.IOException) {
            if (Prefs.LAN_URL.isBlank()) return
            try { ApiClient.create(c, Prefs.LAN_URL).latestReport() } catch (_: Exception) { return }
        } catch (_: Exception) {
            return
        }
        if (report.report_date != today) return      // this morning's analysis is not written yet

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "每日分析", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = (report.alerts + report.subscriptions).firstOrNull() ?: report.insights.firstOrNull() ?: ""
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("今日记账分析")
            .setContentText(report.headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(report.headline + if (detail.isNotEmpty()) "\n$detail" else ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(7001, n)
        prefs.edit().putString(KEY_LAST, today).apply()
    }

    /** A new build is sitting on the server (published with publish.py): tell the user instead of hoping they
     *  remember to open "我的→检查更新" themselves. Fires once per version - no daily nagging once seen. */
    suspend fun maybeNotifyUpdate(c: Context) {
        if (!Prefs.isLoggedIn(c) || !canNotify(c)) return
        val info = try { AppUpdater.check(c) } catch (_: Exception) { return } ?: return
        val prefs = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getInt("last_update_notified_code", 0) >= info.versionCode) return

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("app_update", "新版本提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, "app_update")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("记账有新版本 ${info.versionName}")
            .setContentText(info.notes.ifBlank { "点击打开更新" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes.ifBlank { "点击打开更新" }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(7003, n)
        prefs.edit().putInt("last_update_notified_code", info.versionCode).apply()
    }
}
