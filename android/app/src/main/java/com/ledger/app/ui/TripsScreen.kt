package com.ledger.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.TripDto
import com.ledger.app.viewmodel.DetailRequest
import com.ledger.app.viewmodel.TripsUiState
import java.time.LocalDate

private fun md(d: String) = d.substring(5).replace('-', '月') + "日"

@Composable
fun TripsScreen(
    state: TripsUiState,
    onLoad: () -> Unit,
    onCreate: (name: String, start: String, end: String, kind: String, daily: String?, commute: String?) -> Unit,
    onSettle: (tripId: Int, txId: Int) -> Unit,
    onUnsettle: (Int) -> Unit,
    loadIncomes: suspend () -> List<com.ledger.app.data.TransactionDto>,
    onDelete: (Int) -> Unit,
    onDismiss: (String, String) -> Unit,
    onOpenDetail: (DetailRequest) -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }
    var creating by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var deleting by remember { mutableStateOf<TripDto?>(null) }
    var settling by remember { mutableStateOf<TripDto?>(null) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "出行和旅游单独算: 自己的旅行钱照样计入账期支出, 但不会被当成日常的花法去推算月底。出差垫的钱不算你的开销, 记成\"待报销\"。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.suggestions.forEach { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("疑似一次出行", style = MaterialTheme.typography.labelMedium)
                    Text("${md(s.start_date)} – ${md(s.end_date)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${s.reason}, 约 ${money(s.estimate)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = {
                            onOpenDetail(DetailRequest("疑似出行 ${md(s.start_date)}–${md(s.end_date)}", null, null, s.start_date, s.end_date))
                        }) { Text("看账单") }
                        Button(onClick = { creating = Triple("", s.start_date, s.end_date) }) { Text("记为出行") }
                        TextButton(onClick = { onDismiss(s.start_date, s.end_date) }) { Text("不是出行") }
                    }
                }
            }
        }

        state.trips.forEach { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable { onOpenDetail(DetailRequest("✈️ ${t.name}", null, null, t.start_date, t.end_date, tripId = t.id)) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${md(t.start_date)} – ${md(t.end_date)} · ${t.count} 笔", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(t.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = com.ledger.app.ui.theme.ExpenseColor, maxLines = 1)
                    }
                    if (t.kind == "business") {
                        Text(
                            if (t.settled) "✅ 已报销到账 ${money(t.received_amount)}, 其中补贴等多出来的 ${money(t.allowance_gain)} 记为收入"
                            else "🧾 出差垫付 ${money(t.total)} + 补贴 ${money(t.allowance_total)} = 待报销 ${money(t.expected_reimbursement)}",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            color = if (t.settled) com.ledger.app.ui.theme.IncomeColor else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (!t.settled) Text("补贴: 每天 ${money(t.daily_allowance)} × ${t.days} 天 + 通勤 ${money(t.commute_allowance)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    t.by_category.forEach {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${it.icon ?: ""} ${it.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(money(it.total), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row {
                        if (t.kind == "business") {
                            if (t.settled) TextButton(onClick = { onUnsettle(t.id) }) { Text("撤销到账") }
                            else Button(onClick = { settling = t }) { Text("报销已到账") }
                        }
                        TextButton(onClick = { deleting = t }) { Text("取消这次") }
                    }
                }
            }
        }

        if (state.trips.isEmpty() && state.suggestions.isEmpty()) {
            Text("还没有出行记录。去过哪里, 点下面新建一个, 那几天的油费、过路费、吃饭会自动归进来。",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        Button(
            onClick = { val d = LocalDate.now().toString(); creating = Triple("", d, d) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)
        ) { Text("新建一次出行") }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
    }

    creating?.let { (n0, s0, e0) ->
        var name by remember { mutableStateOf(n0) }
        var start by remember { mutableStateOf(s0) }
        var end by remember { mutableStateOf(e0) }
        var business by remember { mutableStateOf(false) }
        var daily by remember { mutableStateOf("180") }
        var commute by remember { mutableStateOf("80") }
        val context = LocalContext.current
        fun pick(current: String, onPick: (String) -> Unit) {
            val d = LocalDate.parse(current)
            DatePickerDialog(context, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        AlertDialog(
            onDismissRequest = { creating = null },
            title = { Text("新建出行") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(selected = !business, onClick = { business = false }, label = { Text("自己出行") })
                        androidx.compose.material3.FilterChip(selected = business, onClick = { business = true }, label = { Text("出差") })
                    }
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(if (business) "名字, 如 北京出差" else "名字, 如 中秋去黄山") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pick(start) { start = it; if (end < it) end = it } }, modifier = Modifier.weight(1f)) { Text("从 ${md(start)}") }
                        OutlinedButton(onClick = { pick(end) { end = if (it < start) start else it } }, modifier = Modifier.weight(1f)) { Text("到 ${md(end)}") }
                    }
                    if (business) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(daily, { daily = it }, singleLine = true, label = { Text("每天补贴") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(commute, { commute = it }, singleLine = true, label = { Text("通勤补贴") }, modifier = Modifier.weight(1f))
                        }
                    }
                    Text(if (business) "这段日子里**所有**消费(机票、酒店、打车、吃饭)都算出差垫付, 不计入你的月开销, 记成待报销; 日期请从出发前订机票那天算起, 到回家那天。"
                        else "这段日子里的油费、过路费、停车、吃饭、门票会自动归进来; 房租等固定支出不会。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = {
                creating = null
                onCreate(name.ifBlank { if (business) "出差" else "出行" }, start, end, if (business) "business" else "personal",
                    if (business) daily.trim().ifEmpty { null } else null, if (business) commute.trim().ifEmpty { null } else null)
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { creating = null }) { Text("取消") } }
        )
    }
    settling?.let { t ->
        var incomes by remember(t.id) { mutableStateOf<List<com.ledger.app.data.TransactionDto>?>(null) }
        LaunchedEffect(t.id) { incomes = loadIncomes() }
        AlertDialog(
            onDismissRequest = { settling = null },
            title = { Text("哪一笔是报销到账?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("应收 ${money(t.expected_reimbursement)}。选公司打给你的那笔进账:", style = MaterialTheme.typography.bodySmall)
                    val list = incomes
                    if (list == null) Text("加载中…", modifier = Modifier.padding(top = 8.dp))
                    else if (list.isEmpty()) Text("最近没有进账记录", modifier = Modifier.padding(top = 8.dp))
                    else list.forEach { tx ->
                        TextButton(onClick = { settling = null; onSettle(t.id, tx.id) }, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tx.occurred_at.take(10) + "  " + (tx.merchant ?: tx.note ?: ""), maxLines = 1, modifier = Modifier.weight(1f))
                                Text("+¥" + tx.amount)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { settling = null }) { Text("取消") } }
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("取消「${t.name}」?") },
            text = { Text("只是取消这次出行的归类, 里面的每一笔账都还在, 会回到日常花销里。") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(t.id) }) { Text("取消出行") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } }
        )
    }
}
