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
                // the home server only has a public IPv6 address (no IPv4 at all): a network whose own upstream has no
                // IPv6 route cannot reach it, whichever network that is. If the current one fails, try moving the app's
                // traffic to the other radio (Wi-Fi <-> mobile data) once; if that fails too, say plainly that neither
                // path has IPv6 right now, rather than leaking OkHttp's raw English exception.
                try {
                    chain.proceed(chain.request())
                } catch (e: java.net.UnknownHostException) {
                    val switched = NetFallback.bindToOther(context.applicationContext)
                    if (!switched) {
                        throw java.io.IOException("连不上家里的服务器: 家里只有公网 IPv6, 当前网络没有 IPv6 出口, 也没有别的网络可切。开着 Wi-Fi 和流量两个一起试试, 或开一下 Tailscale", e)
                    }
                    try {
                        chain.proceed(chain.request())
                    } catch (e2: java.net.UnknownHostException) {
                        throw java.io.IOException("连不上家里的服务器: 家里只有公网 IPv6, 当前这两个网络(Wi-Fi/流量)都没有 IPv6 出口。开一下 Tailscale 试试", e2)
                    }
                }
            }
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
