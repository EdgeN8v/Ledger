package com.ledger.app.data

import android.content.Context

/** Simple synchronous storage - deliberately not DataStore, so the auth interceptor
 *  (which runs on OkHttp's background thread) can read the token without coroutines. */
object Prefs {
    private const val FILE = "ledger_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_BASE_URL = "base_url"
    private val DEFAULT_BASE_URL = com.ledger.app.BuildConfig.DEFAULT_SERVER
    /** Optional second address tried when the main one cannot be reached (e.g. the DDNS name does not resolve on home WiFi). Empty = none. */
    val LAN_URL: String get() = com.ledger.app.BuildConfig.LAN_SERVER

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getToken(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    fun setToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        prefs(context).edit().putString(KEY_BASE_URL, normalized).apply()
    }

    /** Statistics view: also count business-trip advances (they cancel out once reimbursed). Off = your everyday spending. */
    fun getIncludeBusiness(context: Context): Boolean = prefs(context).getBoolean("include_business", false)

    fun setIncludeBusiness(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("include_business", value).apply()
    }

    /** Upper bound for the offline cache (MB). The oldest cached answers are deleted when it is exceeded. */
    fun getCacheLimitMb(context: Context): Int = prefs(context).getInt("cache_limit_mb", 20)

    fun getCacheLimitBytes(context: Context): Long = getCacheLimitMb(context) * 1024L * 1024L

    fun setCacheLimitMb(context: Context, mb: Int) {
        prefs(context).edit().putInt("cache_limit_mb", mb).apply()
        DiskCache.trim(context, mb * 1024L * 1024L)
    }

    /** Bump CACHE_SCHEMA only when a cached screen's data changes in a way old files cannot be read with (Gson leaves
     *  missing fields null). Ordinary updates keep the cache, so the app opens with last time's data at once. */
    private const val CACHE_SCHEMA = 2

    fun clearCacheOnUpdate(context: Context) {
        if (prefs(context).getInt("cache_schema", 0) != CACHE_SCHEMA) {
            DiskCache.clear(context)
            prefs(context).edit().putInt("cache_schema", CACHE_SCHEMA).apply()
        }
    }

    /** Appearance: "system" / "light" / "dark". */
    fun getThemeMode(context: Context): String = prefs(context).getString("theme_mode", "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString("theme_mode", mode).apply()
    }

    /** The one-time "lock the app in recents / allow autostart" guide for vivo/OPPO/Xiaomi/Huawei. */
    fun getVendorGuideShown(context: Context): Boolean = prefs(context).getBoolean("vendor_guide_shown", false)

    fun setVendorGuideShown(context: Context) {
        prefs(context).edit().putBoolean("vendor_guide_shown", true).apply()
    }

    fun isLoggedIn(context: Context): Boolean = getToken(context) != null

    fun logout(context: Context) { setToken(context, null); DiskCache.clear(context) }
}
