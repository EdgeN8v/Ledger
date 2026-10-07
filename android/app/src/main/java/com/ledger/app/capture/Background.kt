package com.ledger.app.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Shortcuts to the system pages that decide whether the app may keep watching in the background. */
object Background {
    fun openSettings(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val tries = mutableListOf<Intent>()
        if (!pm.isIgnoringBatteryOptimizations(c.packageName)) {
            tries += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
        }
        // Xiaomi's "autostart" page, then the generic app info page
        tries += Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        tries += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
        for (i in tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }
}
