package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.ledger.app.capture.CaptureQueue
import com.ledger.app.capture.MaintenanceWorker
import com.ledger.app.capture.SyncNow
import com.ledger.app.capture.SmsInboxScanner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    baseUrl: String,
    inviteCode: String?,
    onLogout: () -> Unit
) {
    var showInviteInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                SettingRow("服务器地址", baseUrl)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "账本邀请码",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(inviteCode ?: "-", style = MaterialTheme.typography.bodyLarge)
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { showInviteInfo = true }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                "i",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        UpdateCard()

        CaptureCard()

        AppearanceCard()

        CacheCard()

        Button(
            onClick = onLogout,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        ) {
            Text("退出登录")
        }
    }

    if (showInviteInfo) {
        AlertDialog(
            onDismissRequest = { showInviteInfo = false },
            confirmButton = {
                TextButton(onClick = { showInviteInfo = false }) { Text("知道了") }
            },
            title = { Text("账本邀请码是什么") },
            text = { Text("把这个号码发给家人,家人在登录页选\"加入账本\",输入这个号码就能加入同一个账本,两人的记录会共享。") }
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The "automatic bookkeeping" switchboard: is the app allowed to read payment notifications, and are they getting through. */
@Composable
private fun CaptureCard() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val enabled = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val pending = remember(tick) { CaptureQueue.size(context) }
    val lastOk = remember(tick) { CaptureQueue.lastOkMs(context) }
    val lastErr = remember(tick) { CaptureQueue.lastError(context) }
    val smsOk = remember(tick) { SmsInboxScanner.hasPermission(context) }
    val noticeOk = remember(tick) { com.ledger.app.capture.DailyReminder.canNotify(context) }
    val noticeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MaintenanceWorker.runNow(context)
        tick++
    }

    var info by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动记账", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InfoDot { info = true }
            }
            val last = if (lastOk > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastOk)) else "还没有"
            Text(
                (if (enabled) "✅ 已开启" else "⚠️ 未开启") + " · 待上传 $pending 条 · 上次 $last" + (lastErr?.let { " · 错误: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Button(
                onClick = { SyncNow.run(context); tick++ },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("立即同步") }
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (enabled) "管理通知权限" else "去开启") }
            Button(
                onClick = { com.ledger.app.capture.Background.openSettings(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("后台运行 / 自启动设置") }
            if (!noticeOk) {
                Button(
                    onClick = { noticeLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("开启每日分析提醒") }
            }
            if (!smsOk) {
                Button(
                    onClick = { smsLauncher.launch(android.Manifest.permission.READ_SMS) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("允许读取短信") }
            }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } },
            title = { Text("自动记账是怎么工作的") },
            text = {
                Text(
                    "· 微信/支付宝的支付通知和银行短信会自动变成账目。\n" +
                        "· 通知使用权:必须开启,否则收不到支付通知。\n" +
                        "· 短信补漏:" + (if (smsOk) "已开启" else "未开启") + ",通知被清掉或漏掉时,会从短信箱补记。\n" +
                        "· 每日分析提醒:" + (if (noticeOk) "已开启" else "未开启") + ",每天早上推送昨天花了多少、预算还剩多少。\n" +
                        "· 后台运行:点「后台运行 / 自启动设置」,允许自启动、省电策略选「无限制」,锁屏后也能收到短信。\n" +
                        "· 不开 App 也能同步:长按桌面图标选「立即同步」,或把「记账同步」磁贴加到下拉快捷开关。"
                )
            }
        )
    }
}

/** The small round "i" that opens an explanation instead of printing it on the page. */
@Composable
private fun InfoDot(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(22.dp).clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("i", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun CacheCard() {
    val context = LocalContext.current
    var limit by remember { mutableStateOf(com.ledger.app.data.Prefs.getCacheLimitMb(context)) }
    var used by remember { mutableStateOf(com.ledger.app.data.DiskCache.size(context)) }
    val options = listOf(5, 20, 50, 200)
    var cacheInfo by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("缓存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InfoDot { cacheInfo = true }
            }
            Text(
                "已用 " + (if (used < 1024 * 1024) "${used / 1024} KB" else "%.1f MB".format(used / 1048576.0)) + " / 上限 $limit MB(下方选择,单位 MB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                options.forEach { mb ->
                    androidx.compose.material3.FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = limit == mb,
                        onClick = {
                            limit = mb
                            com.ledger.app.data.Prefs.setCacheLimitMb(context, mb)
                            used = com.ledger.app.data.DiskCache.size(context)
                        },
                        label = { Text("$mb", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    )
                }
            }
            androidx.compose.material3.TextButton(onClick = {
                com.ledger.app.data.DiskCache.clear(context)
                used = 0
            }) { Text("清除缓存") }
        }
    }
    if (cacheInfo) {
        AlertDialog(
            onDismissRequest = { cacheInfo = false },
            confirmButton = { TextButton(onClick = { cacheInfo = false }) { Text("知道了") } },
            title = { Text("缓存是什么") },
            text = { Text("上次看过的账目和统计会存在手机里,下次进来先显示它们,再在后台悄悄更新,所以不用每次转圈。超出上限时自动删除最早的缓存;退出登录会全部清掉。") }
        )
    }
}


@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    val mode = com.ledger.app.ui.theme.ThemeState.mode
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                options.forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = mode == key,
                        onClick = {
                            com.ledger.app.ui.theme.ThemeState.mode = key
                            com.ledger.app.data.Prefs.setThemeMode(context, key)
                        },
                        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    )
                }
            }
        }
    }
}
