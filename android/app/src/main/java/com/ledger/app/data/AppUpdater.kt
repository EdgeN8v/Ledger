package com.ledger.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String)

/**
 * One-tap self-update from the ledger server (GET /app-update/latest, /app-update/apk): the same signed-in
 * connection the app already uses, so nothing secret is baked in and it works at home or away.
 * Android only replaces the installed app when both builds are signed with the same key.
 */
object AppUpdater {
    fun installedVersionCode(context: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
    } catch (e: Exception) {
        0
    }

    private fun get(context: Context, path: String) =
        ApiClient.http(context).newCall(Request.Builder().url(Prefs.getBaseUrl(context).trimEnd('/') + path).build()).execute()

    /** The newer release the server offers, or null if this app is already current / the server has none. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        get(context, "/app-update/latest").use { resp ->
            if (resp.code == 404) return@withContext null
            check(resp.isSuccessful) { "服务器返回 ${resp.code}" }
            val obj = JSONObject(resp.body!!.string())
            val code = obj.getInt("versionCode")
            if (code <= installedVersionCode(context)) return@withContext null
            val notes = when (val raw = obj.opt("notes")) {
                is JSONArray -> (0 until raw.length()).joinToString("\n") { "· " + raw.getString(it) }
                is String -> raw
                else -> ""
            }
            UpdateInfo(code, obj.optString("versionName", code.toString()), notes)
        }
    }

    suspend fun download(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update.apk")
        try {
            get(context, "/app-update/apk").use { resp ->
                check(resp.isSuccessful) { "服务器返回 ${resp.code}" }
                val body = resp.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        file
    }

    /** Opens Android's installer. False = this app may not install apps yet; the system page for that was opened instead. */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
