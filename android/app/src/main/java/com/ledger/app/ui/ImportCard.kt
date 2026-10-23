package com.ledger.app.ui

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ledger.app.data.ApiClient
import com.ledger.app.data.JdImportDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hand the JD statement (the zip as downloaded from the mailbox, plus its password) to the server: nothing to unzip on the phone. */
@Composable
fun ImportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JdImportDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { picked = uri; password = "" } }

    var info by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("导入京东账单", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                InfoDot { info = true }
            }
            Button(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("选择账单压缩包") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } },
            title = { Text("导入京东账单是怎么回事") },
            text = {
                Text("京东金融 → 我的 → 账单 → 右上角下载账单, 邮箱收到压缩包后保存到手机, 再在这里选这个文件并输入解压密码。会把买的商品名补到对应的付款记录上, 并按商品自动分类。")
            }
        )
    }

    if (picked != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) picked = null },
            title = { Text("输入解压密码") },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, enabled = !busy,
                    label = { Text("压缩包密码") }, visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                Button(enabled = !busy && password.isNotBlank(), onClick = {
                    val uri = picked ?: return@Button
                    busy = true; error = null
                    scope.launch {
                        try {
                            val b64 = withContext(Dispatchers.IO) {
                                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }
                            result = ApiClient.create(context).importJd(mapOf("zip_b64" to b64, "password" to password))
                            picked = null
                        } catch (e: retrofit2.HttpException) {
                            error = if (e.code() == 400) "打不开: 密码不对, 或者选错了文件" else "导入失败(${e.code()})"
                            picked = null
                        } catch (e: Exception) {
                            error = "导入失败: ${e.message ?: "网络不通"}"
                            picked = null
                        } finally {
                            busy = false; password = ""
                        }
                    }
                }) { Text(if (busy) "导入中…" else "导入") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { picked = null }) { Text("取消") } }
        )
    }
    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("导入完成") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("账单里共 ${r.orders} 笔付款, 对上了 ${r.matched} 笔: 补全商品名 ${r.noted} 笔, 改了分类 ${r.recategorized} 笔, 定了小类 ${r.subbed} 笔。")
                    if (r.unmatched > 0) {
                        Text(
                            "有 ${r.unmatched} 笔在记账里找不到对应的付款(多半是用京东白条、小金库、余额付的, 银行短信里没有):",
                            modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall
                        )
                        r.unmatched_list.forEach { u ->
                            Text("${u.date}  ¥${u.amount}  ${u.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("知道了") } }
        )
    }
}
