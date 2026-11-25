package com.ledger.app.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short local trace of what happened to every payment popup (notice seen, sent, shown or skipped and why), so a popup
 * that did not appear can be explained afterwards. Kept in the app's own external folder (readable over adb:
 * Android/data/<package>/files/popup.log), at most ~300 lines; nothing leaves the phone.
 */
object PopupLog {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    @Synchronized
    fun add(c: Context, line: String) {
        android.util.Log.d("PopupLog", line)
        try {
            val f = file ?: File(c.applicationContext.getExternalFilesDir(null) ?: c.applicationContext.filesDir, "popup.log").also { file = it }
            f.appendText(fmt.format(Date()) + " " + line + "\n")
            if (f.length() > 40_000) f.writeText(f.readLines().takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }
}
