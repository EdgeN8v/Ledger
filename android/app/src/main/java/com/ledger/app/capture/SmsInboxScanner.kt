package com.ledger.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/** Safety net behind the notification listener: reads the SMS inbox directly and forwards bank messages the
 *  listener missed (service not bound, notification cleared, phone rebooted). The server de-duplicates, so
 *  forwarding a message twice is harmless. */
object SmsInboxScanner {
    private const val FILE = "ledger_capture"
    private const val KEY_LAST_ID = "sms_last_id"
    private const val FIRST_RUN_DAYS = 30L
    private val MONEY_WORDS = listOf("支出", "收入", "消费", "转入", "转出", "扣款", "取现", "存入", "入账", "支付", "退款")

    /** Money movements and airline ticket messages; everything else (codes, ads) stays on the phone. */
    fun isRelevant(body: String): Boolean {
        val ticket = body.contains("乘机人") && body.contains("航班") && (body.contains("支付成功") || body.contains("成功预定"))
        return ticket || (body.contains("元") && MONEY_WORDS.any { body.contains(it) })
    }

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Returns how many messages were queued. */
    fun scan(c: Context): Int {
        if (!hasPermission(c)) return 0
        val prefs = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val lastId = prefs.getLong(KEY_LAST_ID, 0L)
        val since = System.currentTimeMillis() - FIRST_RUN_DAYS * 24 * 3600 * 1000
        var maxId = lastId
        var queued = 0
        try {
            c.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                if (lastId == 0L) "date > ?" else "_id > ?",
                arrayOf((if (lastId == 0L) since else lastId).toString()),
                "_id ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val address = cur.getString(1)
                    val body = cur.getString(2) ?: continue
                    val date = cur.getLong(3)
                    if (id > maxId) maxId = id
                    if (isRelevant(body)) {
                        CaptureQueue.add(c, CaptureQueue.Item("sms", address, body, date))
                        queued++
                    }
                }
            }
        } catch (_: Exception) {
            return queued
        }
        if (maxId > lastId) prefs.edit().putLong(KEY_LAST_ID, maxId).apply()
        return queued
    }
}
