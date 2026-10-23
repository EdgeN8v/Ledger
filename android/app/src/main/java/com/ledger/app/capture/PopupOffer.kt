package com.ledger.app.capture

import android.content.Context
import com.ledger.app.data.ApiService
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.DiskCache
import com.ledger.app.data.IngestResult
import com.ledger.app.data.PendingCategoryRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * One category popup per payment, whichever way the payment is noticed first: the payment page read by
 * PayeeReaderService, or the WeChat/Alipay payment notification read by PaymentListenerService (Taobao's 免密支付
 * page shows only "免密支付中", so there the notification is the only early sign). Both run in this process, so
 * a small in-memory record is enough: same transaction (or, when one side has none yet, same amount within 2
 * minutes) = already popped.
 */
object PopupOffer {
    private class Seen(val amount: BigDecimal, val at: Long, val txId: Int?)

    private const val WINDOW_MS = 2 * 60_000L
    private val recent = ArrayList<Seen>()

    /** True = nobody popped for this payment yet, and now this caller has; false = skip. */
    @Synchronized
    fun claim(amount: String, txId: Int?, now: Long = System.currentTimeMillis()): Boolean {
        val a = amount.trim().toBigDecimalOrNull() ?: return true
        recent.removeAll { now - it.at > WINDOW_MS }
        // by transaction when both sides know it (two orders of the same amount are two popups); by amount only
        // when one side has no transaction yet (a payment page read before the server created the record)
        if (recent.any { if (txId != null && it.txId != null) it.txId == txId else it.amount.compareTo(a) == 0 }) return false
        recent += Seen(a, now, txId)
        return true
    }

    /** Show the popup for an ingest result that carries an amount (the server sets it only when a popup is
     *  wanted). A pick updates the transaction, or - no transaction yet - is parked on the event until the
     *  bank SMS creates it. Call from a background coroutine; [scope] runs the pick's upload. */
    suspend fun offer(c: Context, scope: CoroutineScope, api: ApiService, result: IngestResult, fallbackPayee: String) {
        val amount = result.amount ?: return
        val app = c.applicationContext
        if (!PaymentPopup.canShow(app)) return
        val categories = DiskCache.get<List<CategoryDto>>(app, "categories")
            ?: try { api.listCategories().also { DiskCache.put(app, "categories", it) } } catch (_: Exception) { return }
        if (!claim(amount, result.transaction?.id)) return
        withContext(Dispatchers.Main) {
            PaymentPopup.show(
                app, amount, result.payee?.takeIf { it.isNotBlank() } ?: fallbackPayee, categories,
                suggestedCategoryId = result.suggested_category_id, suggestedSub = result.suggested_sub
            ) { categoryId, sub ->
                scope.launch {
                    try {
                        if (result.transaction != null) {
                            api.updateTransaction(result.transaction.id, mapOf("category_id" to categoryId, "sub" to sub, "needs_review" to false))
                        } else if (result.event_id != null) {
                            api.setPendingCategory(PendingCategoryRequest(result.event_id, categoryId, sub))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
