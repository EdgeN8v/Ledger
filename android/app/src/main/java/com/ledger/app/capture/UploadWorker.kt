package com.ledger.app.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledger.app.data.ApiClient
import com.ledger.app.data.IngestRequest
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/** Drains CaptureQueue to POST /ingest. Items leave the queue only after the server accepted them, so a
 *  failed run (offline, server down) retries with backoff and nothing is lost or double-counted -
 *  the server de-duplicates re-sends. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try { ReaderHealth.healAll(applicationContext) } catch (_: Exception) {}
        val primary = ApiClient.create(applicationContext)
        val lan = ApiClient.create(applicationContext, com.ledger.app.data.Prefs.LAN_URL)
        suspend fun send(req: IngestRequest) {
            try {
                primary.ingest(req)
            } catch (e: java.io.IOException) {
                if (com.ledger.app.data.Prefs.LAN_URL.isBlank()) throw e
                lan.ingest(req)   // same server, reached over the home network
            }
        }
        while (true) {
            val batch = CaptureQueue.peek(applicationContext, 20)
            if (batch.isEmpty()) return Result.success()
            var sent = 0
            for (item in batch) {
                try {
                    send(IngestRequest(item.source, item.title, item.text, item.postedAtMs, com.ledger.app.data.Prefs.deviceId(applicationContext), item.notifKey))
                    sent++
                } catch (e: HttpException) {
                    // 401: token expired -> keep the queue until the user logs in again. 4xx/5xx otherwise: retry later.
                    CaptureQueue.drop(applicationContext, sent)
                    CaptureQueue.markUploadError(applicationContext, "服务器返回 ${e.code()}")
                    return Result.retry()
                } catch (e: Exception) {
                    CaptureQueue.drop(applicationContext, sent)
                    CaptureQueue.markUploadError(applicationContext, e.message ?: "网络不通")
                    return Result.retry()
                }
            }
            CaptureQueue.drop(applicationContext, sent)
            CaptureQueue.markUploadOk(applicationContext, System.currentTimeMillis())
        }
    }

    companion object {
        private const val NAME = "ledger-upload"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
