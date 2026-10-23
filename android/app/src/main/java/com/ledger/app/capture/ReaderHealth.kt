package com.ledger.app.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Is PayeeReaderService actually running, or only ticked in settings? vivo's "single-cleaner" (and Xiaomi's) force
 * stops the app in the background; Android then keeps the service ticked but marks it 此服务出现故障 and never
 * binds it again until it is switched off and on, so capture quietly stops after the first payment. A real
 * force stop (`am force-stop`, Settings → 强行停止) goes further and unticks it outright - so "the user wanted it"
 * is remembered separately, and forgotten only when the service is unbound gracefully, i.e. switched off by hand.
 *
 * With WRITE_SECURE_SETTINGS (only grantable over adb: `adb shell pm grant com.ledger.app
 * android.permission.WRITE_SECURE_SETTINGS`) the app does that off/on itself; without it, the 我的 page warns.
 */
object ReaderHealth {
    enum class State { OFF, RUNNING, DEAD }

    /** Set by the service itself; it lives in this process, so a fresh process after a force stop starts false. */
    @Volatile var connected = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("reader_health", Context.MODE_PRIVATE)

    /** Called by the service: true once it connects, false only from onUnbind (switched off in settings). A
     *  kill or force stop never reaches onUnbind, so it stays true. */
    fun setWanted(c: Context, on: Boolean) { prefs(c).edit().putBoolean("wanted", on).apply() }

    private fun wanted(c: Context) = prefs(c).getBoolean("wanted", false)

    /** Ticked in settings - false after a real force stop even though the user never switched it off. */
    fun ticked(c: Context): Boolean {
        val me = me(c).flattenToString()
        val short = me(c).flattenToShortString()
        return enabledSetting(c).split(':').any { it.equals(me, true) || it.equals(short, true) }
    }

    private fun me(c: Context) = ComponentName(c, PayeeReaderService::class.java)

    private fun enabledSetting(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

    /** The services the system has really bound - a "malfunctioning" one is ticked in settings but missing here. */
    private fun bound(c: Context): Boolean {
        val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val me = me(c)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si != null && si.packageName == me.packageName && si.name == me.className
        }
    }

    fun state(c: Context): State = when {
        connected -> State.RUNNING
        !ticked(c) -> if (wanted(c)) State.DEAD else State.OFF
        bound(c) -> State.RUNNING
        else -> State.DEAD
    }

    fun canSelfHeal(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * If the service is dead and the permission is there, switch it off and on again by rewriting the setting: our
     * entry out, a short pause so the system notices, our entry back in (just back in, if a force stop already took
     * it out). Every other app's entry is left exactly as it was. Blocking (it sleeps) - call off the main thread. True = a re-register was done.
     */
    // app start, the listener reconnect and the workers can all fire at once: one heal at a time, and none again
    // right after one, while the system is still binding the service it just re-registered
    @Volatile private var lastHealAt = 0L

    @Synchronized
    fun healIfDead(c: Context): Boolean {
        if (!canSelfHeal(c)) return false
        if (System.currentTimeMillis() - lastHealAt < 15_000) return false
        // right after the process starts the system may still be binding a healthy service: give it a moment
        if (state(c) != State.DEAD) return false
        Thread.sleep(2_000)
        if (state(c) != State.DEAD) return false
        return try {
            val me = me(c)
            val mine = setOf(me.flattenToString().lowercase(), me.flattenToShortString().lowercase())
            val others = enabledSetting(c).split(':').filter { it.isNotBlank() && it.lowercase() !in mine }
            val cr = c.contentResolver
            if (ticked(c)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Thread.sleep(1_000)
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            lastHealAt = System.currentTimeMillis()
            android.util.Log.i("PayeeReader", "re-registered after being found dead")
            true
        } catch (e: Exception) {
            android.util.Log.w("PayeeReader", "self-heal failed", e)
            false
        }
    }

    /** Fire-and-forget version for app start / boot / workers. */
    fun healInBackground(c: Context) {
        val app = c.applicationContext
        Thread { try { healIfDead(app) } catch (_: Exception) {} }.start()
    }
}
