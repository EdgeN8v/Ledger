package com.ledger.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Last server answers kept as JSON files, so screens show the previous data at once and refresh silently. */
object DiskCache {
    private val gson = Gson()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "cache").apply { mkdirs() }
    private fun file(ctx: Context, key: String) = File(dir(ctx), key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    fun put(ctx: Context, key: String, value: Any?) {
        try {
            if (value == null) file(ctx, key).delete() else file(ctx, key).writeText(gson.toJson(value))
            trim(ctx)
        } catch (_: Exception) {
        }
    }

    /** Bytes the cache takes on disk. */
    fun size(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Over the limit: delete the least recently written files first until it fits. */
    fun trim(ctx: Context, limit: Long = Prefs.getCacheLimitBytes(ctx)) {
        val files = dir(ctx).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            total -= f.length()
            f.delete()
        }
    }

    inline fun <reified T> get(ctx: Context, key: String): T? = read(ctx, key, object : TypeToken<T>() {}.type)

    fun <T> read(ctx: Context, key: String, type: java.lang.reflect.Type): T? = try {
        val f = file(ctx, key)
        if (f.exists()) gson.fromJson<T>(f.readText(), type) else null
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
