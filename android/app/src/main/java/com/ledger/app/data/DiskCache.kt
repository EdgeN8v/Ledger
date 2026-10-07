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
        } catch (_: Exception) {
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
