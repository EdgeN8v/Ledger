package com.ledger.app.capture

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** The "sync now" shortcut (long-press the app icon): runs one catch-up pass and closes itself. No screen,
 *  no background service - the app can stay closed all day and be poked only when the user remembers. */
class SyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncNow.run(this)
        finish()
    }
}
