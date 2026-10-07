package com.ledger.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledger.app.data.Prefs

/** A text message arrived: put it in the upload queue straight away (no polling). On some phones the system hides
 *  bank messages from this broadcast; the notification listener and the periodic inbox sweep catch those. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Prefs.isLoggedIn(context)) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null } ?: return
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        if (!SmsInboxScanner.isRelevant(body)) return
        CaptureQueue.add(context, CaptureQueue.Item("sms", parts[0].originatingAddress, body, parts[0].timestampMillis))
        UploadWorker.enqueue(context)
    }
}
