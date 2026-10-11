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
        // shopping apps: only order notices leave the phone (shipped / delivered / refund...), never ads or chats
        if (sbn.packageName in SHOP_APPS) {
            val all = (title ?: "") + " " + text
            if (!ORDER_WORDS.containsMatchIn(all) || PROMO_WORDS.containsMatchIn(all)) return
        }

        CaptureQueue.add(this, CaptureQueue.Item(sbn.packageName, title, text, sbn.postTime))
        UploadWorker.enqueue(this)
    }

    companion object {
        private val PAYMENT_WORDS = Regex("已支付|已扣费|支付成功|付款|收款|转账|红包|到账|退款|扣款|¥|￥")
        private val ORDER_WORDS = Regex("已发货|已签收|已出库|待收货|已揽收|派送|取件|包裹|订单|已下单|已支付|退款|退货|售后|换货")
        private val PROMO_WORDS = Regex("优惠|红包|秒杀|领券|降价|会员日|猜你喜欢|直播|签到|福利|限时|抢购|折扣|满[0-9]+减|活动|立减|补贴|大促|爆款")
        val SHOP_APPS = setOf("com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.taobao.taobao", "com.tmall.wireless", "com.jd.jrapp")
        val WATCHED = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.android.mms",
            "com.miui.mms",
            "com.google.android.apps.messaging",
            "com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.taobao.taobao", "com.tmall.wireless", "com.jd.jrapp"
        )
    }
}
