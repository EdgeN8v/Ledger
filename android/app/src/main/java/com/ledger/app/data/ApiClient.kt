package com.ledger.app.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    /** Rebuilt on every call so a base-URL change (e.g. user edits the server address on the
     *  login screen) takes effect immediately without restarting the app. Request volume here
     *  is tiny (a personal/family ledger), so the lack of caching is not worth the complexity. */
    fun create(context: Context, baseUrlOverride: String? = null): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = Prefs.getToken(context)
                val request = chain.request().newBuilder().apply {
                    if (token != null) addHeader("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrlOverride ?: Prefs.getBaseUrl(context))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
