package com.ledger.app.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object ApiClient {

    // One client for the whole app: keeps connections open, so a screen does not pay a new TCP handshake per request.
    @Volatile private var shared: OkHttpClient? = null

    private var lanChecked = 0L
    private var lanUp = false

    /** At home the box answers directly on its LAN address; away from home that address is unreachable.
     *  A 400 ms TCP probe, remembered for a minute, decides - so the public/DDNS route (slow, via the router) is skipped at home. */
    @Synchronized
    private fun lanReachable(): Boolean {
        val lan = Prefs.LAN_URL.toHttpUrlOrNull() ?: return false
        val now = System.currentTimeMillis()
        if (now - lanChecked < 60_000) return lanUp
        lanUp = try {
            Socket().use { it.connect(InetSocketAddress(lan.host, lan.port), 400); true }
        } catch (_: Exception) {
            false
        }
        lanChecked = System.currentTimeMillis()
        return lanUp
    }

    fun http(context: Context): OkHttpClient = client(context.applicationContext)

    private fun client(context: Context): OkHttpClient = shared ?: synchronized(this) {
        shared ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                var request = chain.request()
                val token = Prefs.getToken(context)
                val b = request.newBuilder()
                if (token != null) b.addHeader("Authorization", "Bearer $token")
                // only for the built-in server address; a hand-typed one is respected
                val lan = Prefs.LAN_URL.toHttpUrlOrNull()
                if (lan != null && Prefs.getBaseUrl(context) == com.ledger.app.BuildConfig.DEFAULT_SERVER && lanReachable()) {
                    b.url(request.url.newBuilder().scheme(lan.scheme).host(lan.host).port(lan.port).build())
                }
                request = b.build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build().also { shared = it }
    }

    /** Retrofit is rebuilt per call so a base-URL change takes effect at once; the heavy part (the OkHttp client) is shared. */
    fun create(context: Context, baseUrlOverride: String? = null): ApiService {
        val app = context.applicationContext
        return Retrofit.Builder()
            .baseUrl(baseUrlOverride ?: Prefs.getBaseUrl(app))
            .client(client(app))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
