package com.ledger.app.capture

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledger.app.data.Prefs
import java.util.concurrent.TimeUnit

/** Runs every ~15 minutes: makes sure the notification listener is bound (some phones drop it after an
 *  update or when the app is killed), re-registers a force-stopped accessibility reader (ReaderHealth), sweeps the SMS inbox for anything it missed, and flushes the queue. */
class MaintenanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.isLoggedIn(applicationContext)) return Result.success()
        ensureListenerBound(applicationContext)
        try { ReaderHealth.healAll(applicationContext) } catch (_: Exception) {}
        SmsInboxScanner.scan(applicationContext)
        if (CaptureQueue.size(applicationContext) > 0) UploadWorker.enqueue(applicationContext)
        DailyReminder.maybeNotify(applicationContext)
        DailyReminder.maybeRemind(applicationContext)
        DailyReminder.maybeNotifyUpdate(applicationContext)
        return Result.success()
    }

    companion object {
        fun ensureListenerBound(c: Context) {
            val granted = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
            if (granted) {
                try {
                    NotificationListenerService.requestRebind(ComponentName(c, PaymentListenerService::class.java))
                } catch (_: Exception) {
                }
            }
        }

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c.applicationContext)
                .enqueueUniquePeriodicWork("ledger-maintenance", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Called when the app opens: do the same work right away. */
        fun runNow(c: Context) {
            ensureListenerBound(c)
            ReaderHealth.healInBackground(c)
            Thread {
                SmsInboxScanner.scan(c)
                if (CaptureQueue.size(c) > 0) UploadWorker.enqueue(c)
            }.start()
        }
    }
}
