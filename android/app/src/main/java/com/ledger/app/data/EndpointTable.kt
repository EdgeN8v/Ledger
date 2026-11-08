package com.ledger.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The home's shared address table (HomeHub, `GET /api/endpoints`, no token), the same one the music app reads. The app
 * only carries the way in to the table - the hub addresses it saw last time (cached) and the two domains - and takes
 * the ledger's own addresses (`ledger`: home-network `lan`, internet `wan`) from it. A failed read changes nothing:
 * the cached table, then the built-in addresses, keep working.
 */
object EndpointTable {
    private const val SERVICE = "ledger"
    private const val HUB_SERVICE = "homehub"
    private const val PREFS = "endpoint_table"
    private const val KEY_JSON = "json"
    private const val KEY_AT = "fetched_at"

    private val BUILT_IN_SEEDS = listOf("https://pi.cuikexin.com:8200", "https://nas.cuikexin.com:8095")
    private const val PI_COPY_LAN = "http://192.168.31.61:8099/endpoints.json"
    private const val PI_COPY_WAN = "http://pi.cuikexin.com:8099/endpoints.json"
    private const val LOCAL_SEED_MS = 1000
    private const val EXTERNAL_SEED_MS = 3000
    private const val REFRESH_EVERY_MS = 5 * 60_000L
    private const val PICK_KEEP_MS = 60_000L
    private const val LOCAL_PROBE_MS = 600
    private const val EXTERNAL_PROBE_MS = 3000

    /** Used while the table has no `ledger` entry (not registered yet, or never read). */
    private val FALLBACK_LAN = listOf("http://192.168.31.131:8000")
    private val FALLBACK_WAN get() = listOf(com.ledger.app.BuildConfig.DEFAULT_SERVER.trim().trimEnd('/'))

    private var prefs: SharedPreferences? = null
    @Volatile private var table: JSONObject? = null
    @Volatile private var lastTryMs = 0L
    @Volatile private var picked: String? = null
    @Volatile private var pickedAtMs = 0L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        table = p.getString(KEY_JSON, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun urls(service: String, key: String): List<String> {
        val array = table?.optJSONObject("services")?.optJSONObject(service)?.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it).trim().trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }

    /** Where to try the ledger server, in order: home-network addresses first, then the internet ones. */
    private fun candidates(): List<Pair<String, Int>> {
        val lan = urls(SERVICE, "lan").ifEmpty { FALLBACK_LAN }
        val wan = urls(SERVICE, "wan").ifEmpty { FALLBACK_WAN }
        return (lan.map { it to LOCAL_PROBE_MS } + wan.map { it to EXTERNAL_PROBE_MS }).distinctBy { it.first }
    }

    /** The first ledger address that answers `/health`, remembered for a minute; null if none does. */
    fun resolve(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        picked?.let { if (!force && now - pickedAtMs < PICK_KEEP_MS) return it }
        for ((base, timeout) in candidates()) {
            if (healthy(base, timeout)) {
                picked = base
                pickedAtMs = System.currentTimeMillis()
                return base
            }
        }
        picked = null
        return null
    }

    /** Forget the remembered choice (a request just failed): the next call picks again. */
    fun forget() {
        picked = null
    }

    private fun healthy(base: String, timeoutMs: Int): Boolean = try {
        val conn = URL("$base/health").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 3000
            conn.responseCode in 200..299 && conn.inputStream.bufferedReader().use { it.readText() }.contains("ok")
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /** Re-reads the table without making the caller wait. Rate-limited; [force] (a request failed) allows a retry sooner. */
    fun refreshInBackground(force: Boolean = false) {
        val gap = if (force) 30_000L else REFRESH_EVERY_MS
        if (System.currentTimeMillis() - lastTryMs < gap) return
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                refreshNow()
            } finally {
                busy.set(false)
            }
        }.start()
    }

    /** Asks every place that may hold the table at once, keeps the first valid answer. False leaves the cache as it was. */
    fun refreshNow(): Boolean {
        lastTryMs = System.currentTimeMillis()
        val seeds = (urls(HUB_SERVICE, "lan").map { "$it/api/endpoints" to LOCAL_SEED_MS } +
            BUILT_IN_SEEDS.map { "$it/api/endpoints" to EXTERNAL_SEED_MS } +
            (PI_COPY_LAN to LOCAL_SEED_MS) + (PI_COPY_WAN to EXTERNAL_SEED_MS)).distinctBy { it.first }
        val pool = Executors.newFixedThreadPool(seeds.size)
        try {
            val done = ExecutorCompletionService<String?>(pool)
            seeds.forEach { (seed, ms) -> done.submit(java.util.concurrent.Callable<String?> { runCatching { fetch(seed, ms) }.getOrNull() }) }
            val deadline = System.currentTimeMillis() + 6_000
            repeat(seeds.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@repeat
                val text = done.poll(left, TimeUnit.MILLISECONDS)?.get() ?: return@repeat
                table = JSONObject(text)
                prefs?.edit()?.putString(KEY_JSON, text)?.putLong(KEY_AT, System.currentTimeMillis())?.apply()
                picked = null
                return true
            }
            return false
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetch(source: String, timeoutMs: Int): String {
        val conn = URL(source).openConnection() as HttpURLConnection
        try {
            // The hub's https certificate is self-signed: accept exactly that one (by fingerprint), nothing else. The host
            // name is not checked: the same certificate serves the IP addresses and both domains.
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = pinnedFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 5000
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(JSONObject(text).optJSONObject("services") != null) { "not an address table" }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** SHA-256 of the HomeHub certificate (the same self-signed one on both machines, valid until 2036-10-18). */
    private const val HUB_CERT_SHA256 = "a3793a609a5f0fd295668d17c6de68037e5fbfd2e9702c162e0b93af38dedb11"

    private val pinnedFactory by lazy {
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("no client certificates")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
                val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (sha != HUB_CERT_SHA256) throw CertificateException("not the HomeHub certificate")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }.socketFactory
    }
}
