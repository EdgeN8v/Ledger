package com.ledger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ledger.app.data.Prefs

/** After a reboot or an update: start watching again without anyone opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isLoggedIn(context)) return
        MaintenanceWorker.schedule(context)
        MaintenanceWorker.runNow(context)
    }
}
