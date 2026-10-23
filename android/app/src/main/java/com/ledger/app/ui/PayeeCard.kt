package com.ledger.app.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.ledger.app.capture.PayeeReaderService
import com.ledger.app.capture.PaymentPopup

/** Switch for reading the shop name off WeChat's payment page (扫码付款 shows no shop name anywhere else), and
 *  for the popup that lets you pick a category right there on that page. */
@Composable
fun PayeeCard() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val on = remember(tick) {
        val me = ComponentName(context, PayeeReaderService::class.java).flattenToString()
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any { it.equals(me, true) } == true
    }
    val popupOn = remember(tick) { PaymentPopup.canShow(context) }
    var info by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("付款自动识别商家 + 弹窗选类别", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                InfoDot { info = true }
            }
            Text(
                "读取商家名: " + (if (on) "✅" else "⚠️") + "    弹窗选类别: " + (if (popupOn) "✅" else "⚠️"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
            if (!on) Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("去开启识别商家(无障碍 → 记账)") }
            if (!popupOn) Button(
                onClick = { PaymentPopup.requestPermission(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("去开启弹窗(显示在其他应用上层)") }
            if (on) TextButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.padding(top = 4.dp)
            ) { Text("管理(在无障碍里关闭)") }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } },
            title = { Text("这两项是怎么工作的") },
            text = {
                Column {
                    Text(
                        "· 识别商家名: 微信/支付宝扫码付款的短信和通知都没有商家名。开启后, 出现「支付成功」「扣款成功」的页面时, 只读页面上的商家名和金额(只在微信、支付宝、淘宝、天猫里), 不读聊天、不读商品浏览页。\n\n" +
                            "· 弹窗选类别: 识别到商家后, 在屏幕上弹一个小框当场选类别(跟长按一笔账单改类别一样), 不用回头再分类; 认识的商家还会自动帮你选好, 一键确认。需要「显示在其他应用上层」权限。"
                    )
                    Text(
                        "\n小米手机若提示「受限制的设置」: 系统设置 → 应用 → 记账 → 右上角 ⋮ → 允许受限制的设置, 再回来开启。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}
