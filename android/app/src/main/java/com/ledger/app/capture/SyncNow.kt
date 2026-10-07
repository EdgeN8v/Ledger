package com.ledger.app.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ledger.app.data.Prefs

/** One-tap catch-up: re-bind the notification listener, sweep the SMS inbox, upload whatever is queued,
 *  and tell the user how it went. Shared by the shortcut, the Quick Settings tile and the app's own button. */
object SyncNow {
    fun run(context: Context) {
        val c = context.applicationContext
        val ui = Handler(Looper.getMainLooper())
        fun toast(msg: String) = ui.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }

        if (!Prefs.isLoggedIn(c)) {
            toast("请先打开记账 App 登录")
            return
        }
        MaintenanceWorker.ensureListenerBound(c)
        Thread {
            val found = SmsInboxScanner.scan(c)
            val pending = CaptureQueue.size(c)
            if (pending > 0) UploadWorker.enqueue(c)
            toast(
                when {
                    !SmsInboxScanner.hasPermission(c) -> "已同步通知; 未开启短信权限,银行短信可能漏记"
                    pending == 0 -> "已是最新,没有需要同步的新短信"
                    else -> "已提交 $pending 条(新读到短信 $found 条),正在上传记账"
                }
            )
        }.start()
    }
}
