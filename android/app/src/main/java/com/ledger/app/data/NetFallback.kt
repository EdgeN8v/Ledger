package com.ledger.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The home server has only a public IPv6 address (the home ISP gives no public IPv4 at all). A network whose own
 * upstream lacks an IPv6 route cannot reach it, whichever network that is - the phone's Wi-Fi might be IPv4-only, or
 * its mobile data might be, depending on carrier and location; either can be the broken one on a given day.
 *
 * When a request fails to resolve the home name, [bindToOther] moves the app's own traffic to whichever radio
 * (Wi-Fi vs. cellular) it is NOT currently preferring, and the caller retries once. Nothing else on the phone is
 * touched, and the bind is dropped the next time the app successfully reaches the server on its own default route
 * (see [clearIfWorking]), so a day when both radios are fine never stays pinned to one of them.
 */
object NetFallback {
    @Volatile private var boundTransport: Int? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True once the app's traffic is pinned to the other radio than whatever the system is defaulting to. */
    @Synchronized
    fun bindToOther(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val current = currentTransport(cm)
        val want = when (current) {
            NetworkCapabilities.TRANSPORT_WIFI -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> NetworkCapabilities.TRANSPORT_WIFI                         // unknown/cellular/none: try Wi-Fi
        }
        if (boundTransport == want) return true                                // already pinned to the other one

        release(context)
        val latch = CountDownLatch(1)
        var got: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                got = network
                cm.bindProcessToNetwork(network)
                boundTransport = want
                latch.countDown()
            }

            override fun onLost(network: Network) {
                if (got == network) { boundTransport = null; cm.bindProcessToNetwork(null) }
            }
        }
        return try {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(want).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
            callback = cb
            latch.await(6, TimeUnit.SECONDS) && boundTransport == want
        } catch (_: Exception) {
            false
        }
    }

    private fun currentTransport(cm: ConnectivityManager): Int? {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null
        }
    }

    /** Called after a request succeeds: once the ordinary default network is reaching the server again, stop pinning. */
    fun clearIfWorking(context: Context) {
        if (boundTransport == null) return
        release(context)
    }

    @Synchronized
    private fun release(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { callback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null
        if (boundTransport != null) cm.bindProcessToNetwork(null)
        boundTransport = null
    }
}
