package com.ledger.app.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short local trace of what happened to every payment popup (notice seen, sent, shown or skipped and why), so a popup
 * that did not appear can be explained afterwards. Kept in the app's own external folder (readable over adb:
 * Android/data/<package>/files/popup.log), at most ~300 lines. New lines are also sent to the ledger server (diag/popup) a few seconds later, best effort.
 */
object PopupLog {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    private val unsent = ArrayList<String>()
    private var uploadScheduled = false

    /** The new lines also go to the ledger server a few seconds later (best effort), so the trace can be read without a cable. */
    private fun scheduleUpload(c: Context) {
        if (uploadScheduled) return
        uploadScheduled = true
        val app = c.applicationContext
        Thread {
            try {
                Thread.sleep(6_000)
                val lines = synchronized(this) { uploadScheduled = false; ArrayList(unsent).also { unsent.clear() } }
                if (lines.isEmpty() || !com.ledger.app.data.Prefs.isLoggedIn(app)) return@Thread
                kotlinx.coroutines.runBlocking {
                    try {
                        com.ledger.app.data.ApiClient.create(app).sendPopupLog(com.ledger.app.data.DiagLogRequest(com.ledger.app.data.Prefs.deviceId(app), lines))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                synchronized(this) { uploadScheduled = false }
            }
        }.start()
    }

    @Synchronized
    fun add(c: Context, line: String) {
        android.util.Log.d("PopupLog", line)
        if (unsent.size < 200) unsent.add(fmt.format(Date()) + " " + line)
        scheduleUpload(c)
        try {
            val f = file ?: File(c.applicationContext.getExternalFilesDir(null) ?: c.applicationContext.filesDir, "popup.log").also { file = it }
            f.appendText(fmt.format(Date()) + " " + line + "\n")
            if (f.length() > 40_000) f.writeText(f.readLines().takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }
}
