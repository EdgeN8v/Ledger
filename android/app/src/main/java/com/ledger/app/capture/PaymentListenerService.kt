package com.ledger.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ledger.app.data.ApiClient
import com.ledger.app.data.IngestRequest
import com.ledger.app.data.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Forwards payment-related notifications (WeChat / Alipay / SMS app) to the ledger server as raw text.
 *  No parsing happens on the phone: the server owns the templates so a fix never needs a new APK. */
class PaymentListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> android.util.Log.w("PaymentListener", "send failed", e) })

    /** When access is first granted, pick up what is already sitting in the notification shade. */
    override fun onListenerConnected() {
        ReaderHealth.listenerConnected = true
        ReaderHealth.healInBackground(this)   // bound again after a force stop: the reader may need the same
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

        val item = CaptureQueue.Item(sbn.packageName, title, text, sbn.postTime)
        // fresh ones only: old alerts re-read from the shade on reconnect just go through the queue, no popup
        val fresh = System.currentTimeMillis() - sbn.postTime < 2 * 60_000
        if (fresh && sbn.packageName in WALLETS && PAY_ALERT.containsMatchIn((title ?: "") + " " + text)) {
            // a WeChat/Alipay payment alert ("交易提醒: 你有一笔4.70元的支出"): sent right away so the category
            // popup can show now - the payment page itself often can't be read (Taobao 免密支付). The server
            // decides whether it is a popup-worthy purchase (it returns an amount only then)
            scope.launch { sendAndMaybePopup(item) }
            return
        }
        CaptureQueue.add(this, item)
        UploadWorker.enqueue(this)
    }

    private suspend fun sendAndMaybePopup(item: CaptureQueue.Item) {
        val primary = ApiClient.create(applicationContext)
        val req = IngestRequest(item.source, item.title, item.text, item.postedAtMs)
        val result = try {
            try {
                primary.ingest(req)
            } catch (e: java.io.IOException) {
                if (Prefs.LAN_URL.isBlank()) throw e
                ApiClient.create(applicationContext, Prefs.LAN_URL).ingest(req)
            }
        } catch (_: Exception) {
            CaptureQueue.add(this, item)
            UploadWorker.enqueue(this)
            return
        }
        // the shop name usually arrives later (bank SMS), so name the popup after the wallet meanwhile
        PopupOffer.offer(applicationContext, scope, primary, result, if (item.source == "com.tencent.mm") "微信支付" else "支付宝付款")
    }

    override fun onListenerDisconnected() {
        ReaderHealth.listenerConnected = false
    }

    override fun onDestroy() {
        ReaderHealth.listenerConnected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val PAYMENT_WORDS = Regex("已支付|已扣费|支付成功|付款|收款|转账|红包|到账|退款|扣款|¥|￥")
        private val ORDER_WORDS = Regex("已发货|已签收|已出库|待收货|已揽收|派送|取件|包裹|订单|已下单|已支付|退款|退货|售后|换货")
        private val PROMO_WORDS = Regex("优惠|红包|秒杀|领券|降价|会员日|猜你喜欢|直播|签到|福利|限时|抢购|折扣|满[0-9]+减|活动|立减|补贴|大促|爆款")
        private val WALLETS = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
        // tolerant on purpose: anything that might be a payment goes now; the server sorts out the rest
        private val PAY_ALERT = Regex("交易提醒|支出|已支付|付款|扣款|扣费|消费|支付成功")
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
