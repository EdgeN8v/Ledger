package com.ledger.app.capture

import android.content.Context
import com.ledger.app.data.ApiClient
import com.ledger.app.data.ApiService
import com.ledger.app.data.IngestRequest
import com.ledger.app.data.IngestResult
import com.ledger.app.data.Prefs

/**
 * The send behind the category popup, which is waiting for the answer. The home LAN address goes first with a 1.5 s
 * connect timeout; only when that fails is the public address tried. Before, the public address went first, and
 * when it hung (an IPv6-only name, a Wi-Fi that was still waking up, the 400 ms LAN probe failing once and being
 * remembered for a minute) the answer came 13-26 s late - after the receipt was long gone.
 */
object ImmediateIngest {
    class Sent(val result: IngestResult, val api: ApiService)

    @Volatile private var lanFailedAt = 0L

    /** Throws like a normal call when neither address works (the caller then falls back to the upload queue). */
    suspend fun send(c: Context, req: IngestRequest): Sent {
        val app = c.applicationContext
        val lan = Prefs.LAN_URL
        // away from home the LAN address never answers: after a failure skip it for 20 s instead of paying 1.5 s each time
        if (lan.isNotBlank() && System.currentTimeMillis() - lanFailedAt > 20_000) {
            val fast = ApiClient.createFast(app, lan)
            try {
                return Sent(fast.ingest(req), fast)
            } catch (e: java.io.IOException) {
                lanFailedAt = System.currentTimeMillis()
            }
        }
        val primary = ApiClient.create(app)
        return Sent(primary.ingest(req), primary)
    }
}
