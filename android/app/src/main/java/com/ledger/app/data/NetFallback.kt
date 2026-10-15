package com.ledger.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The home server is reached over IPv6 (its address has no IPv4). Many Wi-Fi networks (hotels, other cities) are IPv4-only:
 * the name then "cannot be resolved" although the mobile network would reach it. When that happens the app moves its own
 * traffic to the mobile network (nothing else on the phone is touched) and retries once - no switch to flip.
 */
object NetFallback {
    @Volatile private var bound = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True when the app's traffic now runs over the mobile network. Safe to call repeatedly. */
    @Synchronized
    fun bindToMobile(context: Context): Boolean {
        if (bound) return true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val latch = CountDownLatch(1)
        var got: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                got = network
                cm.bindProcessToNetwork(network)
                bound = true
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (got == network) { bound = false; cm.bindProcessToNetwork(null) }
            }
        }
        return try {
            cm.requestNetwork(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                cb
            )
            callback = cb
            latch.await(6, TimeUnit.SECONDS) && bound
        } catch (_: Exception) {
            false
        }
    }

    /** Back to the normal network (e.g. when the app is reopened on a Wi-Fi that works). */
    @Synchronized
    fun release(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { callback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null
        if (bound) cm.bindProcessToNetwork(null)
        bound = false
    }
}
