package com.ledger.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ledger.app.data.Prefs

/** Forwards payment-related notifications (WeChat / Alipay / SMS app) to the ledger server as raw text.
 *  No parsing happens on the phone: the server owns the templates so a fix never needs a new APK. */
class PaymentListenerService : NotificationListenerService() {

    /** When access is first granted, pick up what is already sitting in the notification shade. */
    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        if (!Prefs.isLoggedIn(this)) return
        if (sbn.packageName !in WATCHED) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (text.isNullOrBlank()) return
        // chat notifications are nobody's business: from WeChat only payment messages leave the phone
        if (sbn.packageName == "com.tencent.mm" && !PAYMENT_WORDS.containsMatchIn((title ?: "") + " " + text)) return

        CaptureQueue.add(this, CaptureQueue.Item(sbn.packageName, title, text, sbn.postTime))
        UploadWorker.enqueue(this)
    }

    companion object {
        private val PAYMENT_WORDS = Regex("已支付|已扣费|支付成功|付款|收款|转账|红包|到账|退款|扣款|¥|￥")
        val WATCHED = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.android.mms",
            "com.miui.mms",
            "com.google.android.apps.messaging"
        )
    }
}
