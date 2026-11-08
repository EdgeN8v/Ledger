package com.ledger.app.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // One client for the whole app: keeps connections open, so a screen does not pay a new TCP handshake per request.
    @Volatile private var shared: OkHttpClient? = null

    /** The address in use stopped answering: re-read the table, pick again, try once more. */
    private fun retryElsewhere(context: Context, chain: okhttp3.Interceptor.Chain, e: java.io.IOException): okhttp3.Response {
        if (!Prefs.isAutoAddress(context)) throw e
        EndpointTable.forget()
        EndpointTable.refreshInBackground(force = true)
        return chain.proceed(chain.request())
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
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.ConnectException) {
                        retryElsewhere(context, chain, e)
                    } catch (e: java.net.SocketTimeoutException) {
                        retryElsewhere(context, chain, e)
                    }
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
                // the built-in address follows the shared address table (home network first, then the domains); a hand-typed one is respected
                if (Prefs.isAutoAddress(context)) {
                    EndpointTable.init(context)
                    EndpointTable.refreshInBackground()
                    EndpointTable.resolve()?.toHttpUrlOrNull()?.let { t ->
                        b.url(request.url.newBuilder().scheme(t.scheme).host(t.host).port(t.port).build())
                    }
                }
                request = b.build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build().also { shared = it }
    }

    /** For the time-critical sends (the category popup waits for the answer): the same client with short timeouts, so
     *  an unreachable address costs 1.5 s instead of the 10 s connect timeout. Meant for the home LAN address. */
    fun createFast(context: Context, baseUrl: String): ApiService {
        val app = context.applicationContext
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client(app).newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
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
