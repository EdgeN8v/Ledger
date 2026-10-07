package com.ledger.app.capture

import android.service.quicksettings.TileService

/** Quick Settings tile "记账同步": pull down the notification shade, tap once, done. */
class SyncTileService : TileService() {
    override fun onClick() {
        super.onClick()
        SyncNow.run(this)
    }
}
