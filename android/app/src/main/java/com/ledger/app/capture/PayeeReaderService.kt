package com.ledger.app.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.DiskCache
import com.ledger.app.data.IngestRequest
import com.ledger.app.data.PendingCategoryRequest
import com.ledger.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads the shop name off a payment-success/bill-detail page, because the notification and the bank SMS of a
 * scan-to-pay or auto-deduct payment do not name the shop. Also drives PaymentPopup so a category can be picked
 * right there. Deliberately narrow:
 *  - only ever connected to WeChat, Alipay, Taobao and Tmall (see res/xml/payee_accessibility.xml);
 *  - nothing leaves the phone unless the screen says 支付成功/付款成功/扣款成功 AND shows an amount, and then only
 *    the handful of short lines of that page (shop, amount) - never a chat, a product listing or a long text;
 *  - nothing is shown on screen by this service itself (the popup is its own separate opt-in). The server finds
 *    the shop in the lines (screenpay.py), so a layout change in any of these apps does not need a new app build.
 */
class PayeeReaderService : AccessibilityService() {
    private var lastKey = ""
    private var lastAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        if (lines.any { it.contains("支付") }) android.util.Log.d("PayeeReader", pkg + " " + lines.take(24).toString())   // temporary, read over adb
        // 自动扣款成功 (免密支付/自动续费) shows no live "支付成功" screen at all - the bill-detail page you open
        // afterward (in Alipay's own 账单 list) is the only place it ever appears, so that counts too
        if (lines.none { it.contains("支付成功") || it.contains("付款成功") || it.contains("扣款成功") }) return
        if (lines.none { AMOUNT.matches(it) }) return
        val key = lines.joinToString("|")
        if (key == lastKey && now - lastAt < 60_000) return
        lastKey = key
        lastAt = now
        val text = lines.take(14).joinToString("\n")
        // sent right away (not through the queued UploadWorker) so the popup can show while the receipt is
        // still on screen; falls back to the queue only if the immediate send itself fails
        scope.launch { sendAndMaybePopup(source, text, now) }
    }

    private suspend fun sendAndMaybePopup(source: String, text: String, now: Long) {
        val primary = ApiClient.create(applicationContext)
        val result = try {
            try {
                primary.ingest(IngestRequest(source, null, text, now))
            } catch (e: java.io.IOException) {
                if (Prefs.LAN_URL.isBlank()) throw e
                ApiClient.create(applicationContext, Prefs.LAN_URL).ingest(IngestRequest(source, null, text, now))
            }
        } catch (_: Exception) {
            CaptureQueue.add(this, CaptureQueue.Item(source, null, text, now))
            UploadWorker.enqueue(this)
            return
        }
        val amount = result.amount ?: return
        if (!PaymentPopup.canShow(applicationContext)) return
        val categories = DiskCache.get<List<CategoryDto>>(applicationContext, "categories")
            ?: try { primary.listCategories().also { DiskCache.put(applicationContext, "categories", it) } } catch (_: Exception) { return }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            PaymentPopup.show(
                applicationContext, amount, result.payee ?: "", categories,
                suggestedCategoryId = result.suggested_category_id, suggestedSub = result.suggested_sub
            ) { categoryId, sub ->
                scope.launch {
                    try {
                        if (result.transaction != null) {
                            primary.updateTransaction(result.transaction.id, mapOf("category_id" to categoryId, "sub" to sub, "needs_review" to false))
                        } else if (result.event_id != null) {
                            primary.setPendingCategory(PendingCategoryRequest(result.event_id, categoryId, sub))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
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

    override fun onServiceConnected() { android.util.Log.d("PayeeReader", "connected") }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        PaymentPopup.dismiss()
    }

    companion object {
        private val SOURCES = mapOf(
            "com.tencent.mm" to "screen:wechat", "com.eg.android.AlipayGphone" to "screen:alipay",
            "com.taobao.taobao" to "screen:taobao", "com.tmall.wireless" to "screen:taobao"
        )
        // Alipay's own bill list shows a deduction as "-10.90" in some places and as "支出10.9元" (one decimal
        // digit, a word prefix, a 元 suffix) in others - same screen, different accessibility node
        private val AMOUNT = Regex("^(?:支出|收入|退款)?\\s*-?[¥￥]?\\s*[0-9][0-9,]*\\.[0-9]{1,2}\\s*元?$")
    }
}
