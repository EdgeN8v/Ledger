package com.ledger.app.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ledger.app.data.Prefs

/**
 * Reads the shop name off WeChat's payment-success page, because the notification and the bank SMS of a scan-to-pay
 * payment do not name the shop. Deliberately narrow:
 *  - it is only ever connected to WeChat (see res/xml/payee_accessibility.xml);
 *  - nothing leaves the phone unless the screen says 支付成功 AND shows an amount, and then only the handful of short
 *    lines of that page (shop, amount) - never a chat, a list or a long text;
 *  - nothing is shown on screen. The server finds the shop in the lines (screenpay.py), so a layout change in WeChat
 *    does not need a new app.
 */
class PayeeReaderService : AccessibilityService() {
    private var lastKey = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        val source = SOURCES[pkg] ?: return
        if (!Prefs.isLoggedIn(this)) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 700) return                           // content-changed events come in bursts
        val root = rootInActiveWindow ?: return
        val lines = ArrayList<String>()
        collect(root, lines, 0)
        root.recycle()
        if (lines.none { it == "支付成功" || it == "付款成功" }) return
        if (lines.none { AMOUNT.matches(it) }) return
        val key = lines.joinToString("|")
        if (key == lastKey && now - lastAt < 60_000) return
        lastKey = key
        lastAt = now
        CaptureQueue.add(this, CaptureQueue.Item(source, null, lines.take(14).joinToString("\n"), now))
        UploadWorker.enqueue(this)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 14 || out.size >= 60) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length <= 40) out.add(t)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i)
            collect(c, out, depth + 1)
            c?.recycle()
        }
    }

    override fun onInterrupt() {}

    companion object {
        private val SOURCES = mapOf("com.tencent.mm" to "screen:wechat", "com.eg.android.AlipayGphone" to "screen:alipay")
        private val AMOUNT = Regex("^[¥￥]?\\s*[0-9][0-9,]*\\.[0-9]{2}$")
    }
}
