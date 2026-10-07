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

    fun isLoggedIn(context: Context): Boolean = getToken(context) != null

    fun logout(context: Context) = setToken(context, null)
}
