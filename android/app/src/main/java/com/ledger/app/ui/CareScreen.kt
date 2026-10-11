package com.ledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CarCareDto
import com.ledger.app.data.CareItemDto
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun km(v: Int?): String = if (v == null) "-" else if (v >= 10000) String.format("%.1f万公里", v / 10000.0) else "${v}公里"

private fun leftText(i: CareItemDto): String {
    val parts = mutableListOf<String>()
    i.left_km?.let { parts += if (it <= 0) "已超 ${km(-it)}" else "还剩 ${km(it)}" }
    i.left_days?.let { parts += if (it <= 0) "已过期 ${-it} 天" else "还剩 ${if (it >= 60) "${it / 30} 个月" else "$it 天"}" }
    return if (parts.isEmpty()) "还没有记录: 做过一次就记一下" else parts.joinToString(" · ")
}

/** When the next services are due, counted down from the odometer. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CareScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(com.ledger.app.data.DiskCache.get<CarCareDto>(context, "care")) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var odoDialog by remember { mutableStateOf(false) }
    var doneFor by remember { mutableStateOf<String?>(null) }          // item key whose "just did it" dialog is open
    var deleting by remember { mutableStateOf<com.ledger.app.data.CareServiceDto?>(null) }

    LaunchedEffect(tick) {
        try {
            data = ApiClient.create(context).carCare(); error = null
            com.ledger.app.data.DiskCache.put(context, "care", data)
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        try { block(); tick++ } catch (e: Exception) { error = e.message ?: "保存失败" }
    }

    val d = data
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (d == null) { Text("加载中…"); return@Column }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val o = d.odometer
                Text("现在大约", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (o == null) "还没记里程" else km(o.est_km), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (o != null) Text("${o.as_of} 表显 ${km(o.km)} · 平均每月约 ${(o.per_day * 30).toInt()} 公里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { odoDialog = true }, modifier = Modifier.padding(top = 8.dp)) { Text("更新里程表读数") }
            }
        }

        d.items.forEach { i ->
            val tone = when (i.status) { "overdue" -> Color(0xFFD32F2F); "soon" -> Color(0xFFEF6C00); "ok" -> Color(0xFF2E7D32); else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(i.name, fontWeight = FontWeight.Medium)
                        Text(when (i.status) { "overdue" -> "该换了"; "soon" -> "快到了"; "ok" -> "不急"; else -> "" }, color = tone, fontWeight = FontWeight.Bold)
                    }
                    Text(leftText(i), color = tone, modifier = Modifier.padding(top = 2.dp))
                    val target = listOfNotNull(i.due_km?.let { "到 ${km(it)}" }, i.due_date?.let { "到 $it" }).joinToString(" 或 ")
                    if (target.isNotBlank()) Text("下次: $target" + (i.eta_date?.let { " (按现在的开法约 $it)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    i.last?.let { Text("上次: ${it.date}" + (it.km?.let { k -> " · ${km(k)}" } ?: "") + (it.cost?.let { c -> " · ${c}元" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val rule = listOfNotNull(i.interval_km?.let { "每 ${km(it)}" }, i.interval_months?.let { "每 $it 个月" }).joinToString(" 或 ")
                    if (rule.isNotBlank()) Text("规则: $rule" + (i.note?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { doneFor = i.key }) { Text("刚做过了, 记一笔") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("做过的保养和维修", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { doneFor = "other" }) { Text("+ 记其他维修") }
        }
        d.history.forEach { s ->
            Column(modifier = Modifier.fillMaxWidth().clickable { deleting = s }.padding(vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${s.date}  ${s.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(s.cost?.let { "${it}元" } ?: "", style = MaterialTheme.typography.bodyMedium)
                }
                val sub = listOfNotNull(s.km?.let { km(it) }, s.note).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (odoDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { odoDialog = false },
            title = { Text("现在里程表显示多少公里?") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.filter(Char::isDigit) }, singleLine = true, label = { Text("例如 118000") }) },
            confirmButton = { Button(enabled = text.isNotBlank(), onClick = { odoDialog = false; act { ApiClient.create(context).setOdometer(mapOf("km" to text.toInt())) } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { odoDialog = false }) { Text("取消") } }
        )
    }
    doneFor?.let { key ->
        val name = d?.choices?.firstOrNull { it.key == key }?.name ?: "其他维修"
        var day by remember { mutableStateOf(LocalDate.now().toString()) }
        var odo by remember { mutableStateOf("") }
        var cost by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { doneFor = null },
            title = { Text("记一笔: $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = day, onValueChange = { day = it }, singleLine = true, label = { Text("日期 (年-月-日)") })
                    OutlinedTextField(value = odo, onValueChange = { odo = it.filter(Char::isDigit) }, singleLine = true, label = { Text("当时里程 (不填就按估算)") })
                    OutlinedTextField(value = cost, onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, label = { Text("花了多少钱 (可不填)") })
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(if (key == "other") "修了什么" else "备注 (可不填)") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    doneFor = null
                    act {
                        ApiClient.create(context).addCareService(mapOf(
                            "item" to key, "on" to day, "km" to odo.toIntOrNull(), "cost" to cost.ifBlank { null }, "note" to note.ifBlank { null }
                        ))
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { doneFor = null }) { Text("取消") } }
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记录?") },
            text = { Text("${s.date} ${s.name}\n删除后, 这一项会按更早的一次保养重新计算。") },
            confirmButton = { TextButton(onClick = { deleting = null; act { ApiClient.create(context).deleteCareService(s.id) } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("不删") } }
        )
    }
}
