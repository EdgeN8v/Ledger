package com.ledger.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ledger.app.data.AppUpdater
import com.ledger.app.data.UpdateInfo
import kotlinx.coroutines.launch

/** "有新版本": one button downloads the build and opens Android's installer (which still asks to confirm). */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = progress != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodySmall)
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                message = null
                progress = 0f
                scope.launch {
                    try {
                        val file = AppUpdater.download(context) { progress = it }
                        progress = null
                        if (AppUpdater.install(context, file)) onDismiss()
                        else message = "请先在刚打开的页面里允许「记账」安装应用, 然后回来再点一次更新"
                    } catch (e: Exception) {
                        progress = null
                        message = "下载失败: ${e.message ?: "网络不通"}"
                    }
                }
            }) { Text(if (busy) "下载中…" else "更新") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("以后再说") } }
    )
}

/** Startup check: asks the server once per app launch; shows the dialog only when a newer build exists. */
@Composable
fun UpdateCheckOnStart() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        info = try { AppUpdater.check(context) } catch (_: Exception) { null }
    }
    info?.let { UpdateDialog(it) { info = null } }
}

/** Settings card: "检查更新" with a plain answer either way. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var status by remember { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            val name = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "" }
            Text("当前版本 $name", style = MaterialTheme.typography.bodyLarge)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Button(onClick = {
                status = "检查中…"
                scope.launch {
                    try {
                        val got = AppUpdater.check(context)
                        if (got == null) status = "已经是最新版" else { status = ""; info = got }
                    } catch (e: Exception) {
                        status = "检查失败: ${e.message ?: "网络不通"}"
                    }
                }
            }, modifier = Modifier.padding(top = 12.dp)) { Text("检查更新") }
        }
    }
    info?.let { UpdateDialog(it) { info = null } }
}
