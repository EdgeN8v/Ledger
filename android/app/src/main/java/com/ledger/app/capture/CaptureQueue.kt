package com.ledger.app.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Notifications captured but not yet accepted by the server. Persisted so nothing is lost when the
 *  phone is offline or the server is down; the upload worker drains it. */
object CaptureQueue {
    private const val FILE = "ledger_capture"
    private const val KEY = "queue"
    private const val KEY_LAST_OK = "last_ok_ms"
    private const val KEY_LAST_ERR = "last_error"
    private const val MAX_ITEMS = 5000

    data class Item(val source: String, val title: String?, val text: String?, val postedAtMs: Long, val notifKey: String? = null)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, item: Item) {
        val arr = load(c)
        arr.put(JSONObject().put("s", item.source).put("t", item.title).put("x", item.text).put("p", item.postedAtMs).put("k", item.notifKey))
        val trimmed = if (arr.length() > MAX_ITEMS) JSONArray((0 until arr.length()).drop(arr.length() - MAX_ITEMS).map { arr.get(it) }) else arr
        prefs(c).edit().putString(KEY, trimmed.toString()).apply()
    }

    @Synchronized
    fun peek(c: Context, n: Int = 20): List<Item> {
        val arr = load(c)
        return (0 until minOf(n, arr.length())).map {
            val o = arr.getJSONObject(it)
            Item(o.getString("s"), o.optString("t").ifEmpty { null }, o.optString("x").ifEmpty { null }, o.getLong("p"), o.optString("k").ifEmpty { null })
        }
    }

    /** Removes the first [n] items (the ones just uploaded successfully). */
    @Synchronized
    fun drop(c: Context, n: Int) {
        val arr = load(c)
        val rest = JSONArray((n until arr.length()).map { arr.get(it) })
        prefs(c).edit().putString(KEY, rest.toString()).apply()
    }

    @Synchronized
    fun size(c: Context): Int = load(c).length()

    fun markUploadOk(c: Context, nowMs: Long) =
        prefs(c).edit().putLong(KEY_LAST_OK, nowMs).remove(KEY_LAST_ERR).apply()

    fun markUploadError(c: Context, message: String) = prefs(c).edit().putString(KEY_LAST_ERR, message).apply()

    fun lastOkMs(c: Context): Long = prefs(c).getLong(KEY_LAST_OK, 0L)
    fun lastError(c: Context): String? = prefs(c).getString(KEY_LAST_ERR, null)

    private fun load(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
}
