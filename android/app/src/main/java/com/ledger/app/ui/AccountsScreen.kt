package com.ledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.AccountDto
import com.ledger.app.viewmodel.AccountsUiState
import kotlinx.coroutines.launch

private val KINDS = listOf("bank" to "银行卡", "wallet" to "电子钱包", "cash" to "现金")

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onRefresh: () -> Unit,
    loadReceivable: suspend () -> List<com.ledger.app.data.ReceivableTripDto>,
    onAdd: (name: String, kind: String, balance: String?) -> Unit,
    onUpdate: (id: Int, balance: String?, includeInTotal: Boolean?) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountDto?>(null) }
    var showReceivable by remember { mutableStateOf(false) }
    var monthlyFor by remember { mutableStateOf<com.ledger.app.data.LoanStatusDto?>(null) }
    var monthlyText by remember { mutableStateOf("") }
    var monthlyError by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { onRefresh() }

    monthlyFor?.let { d ->
        AlertDialog(
            onDismissRequest = { monthlyFor = null; monthlyError = null },
            title = { Text("每月还${d.who}多少") },
            text = {
                Column {
                    OutlinedTextField(
                        value = monthlyText, onValueChange = { monthlyText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("每月计划还款(元)") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    )
                    monthlyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            com.ledger.app.data.ApiClient.create(context).setLoanMonthly(mapOf("who" to d.who, "monthly" to monthlyText.ifBlank { null }))
                            monthlyFor = null; monthlyError = null; onRefresh()
                        } catch (e: Exception) { monthlyError = e.message ?: "保存失败" }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { monthlyFor = null; monthlyError = null }) { Text("取消") } }
        )
    }

    val ov = state.overview
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("总资产", style = MaterialTheme.typography.labelMedium)
                    Text(moneyWhole(ov?.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    // part of the total is already spoken for (the rent fund ...): what is left is what is really free
                    ov?.earmarked.orEmpty().forEach { e ->
                        val due = e.due?.takeIf { it.length >= 10 }?.let { " · ${it.substring(5, 7).trimStart('0')}/${it.substring(8, 10).trimStart('0')}前付" } ?: ""
                        Text("${e.name}专款 ${moneyWhole(e.amount)} / ${moneyWhole(e.cap)}$due", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (ov?.earmarked.orEmpty().isNotEmpty()) Text("可自由支配 ${moneyWhole(ov?.free_total)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    if (!ov?.liabilities.isNullOrEmpty()) Text("负债 ${moneyWhole(ov?.liabilities_total)} · 净资产 ${moneyWhole(ov?.net_total)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    val owed = ov?.receivable?.toDoubleOrNull() ?: 0.0
                    if (owed > 0) TextButton(onClick = { showReceivable = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("待报销 ${moneyWhole(ov?.receivable)} ›") }
                }
            }
        }
        if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        items(ov?.liabilities.orEmpty()) { d ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("还剩 ${moneyWhole(d.remaining)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { (d.percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("已还 ${moneyWhole(d.repaid)} / ${moneyWhole(d.borrowed)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { monthlyFor = d; monthlyText = d.monthly?.substringBefore('.') ?: "" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text(if (d.monthly == null) "设每月还款" else "每月 ${moneyWhole(d.monthly)}")
                        }
                    }
                }
            }
        }
        items(ov?.accounts ?: emptyList()) { a ->
            Card(onClick = { editing = a }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        val sub = listOfNotNull(
                            a.balance_at?.let { it.replace('T', ' ').take(16).drop(5) },
                            if (!a.include_in_total) "不计入总额" else null
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(moneyWhole(a.balance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ 添加账户") } }
    }

    if (showReceivable) {
        var trips by remember { mutableStateOf<List<com.ledger.app.data.ReceivableTripDto>?>(null) }
        LaunchedEffect(Unit) { trips = loadReceivable() }
        AlertDialog(
            onDismissRequest = { showReceivable = false },
            confirmButton = { TextButton(onClick = { showReceivable = false }) { Text("知道了") } },
            title = { Text("待报销怎么算的") },
            text = {
                Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val list = trips
                    if (list == null) Text("加载中…")
                    else if (list.isEmpty()) Text("没有待报销的出差")
                    else list.forEach { t ->
                        Text(if (t.open_end) "${t.name}  ${t.start_date.drop(5)} 出发 · 回程未订" else "${t.name}  ${t.start_date.drop(5)} – ${t.end_date.drop(5)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        t.items.forEach {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(it.date.drop(5) + "  " + (it.merchant ?: ""), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(moneyWhole(it.amount), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("垫付小计", style = MaterialTheme.typography.bodySmall); Text(moneyWhole(t.advanced), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (t.open_end) "补贴(回程定了再算)" else "补贴(每天×天数+通勤)", style = MaterialTheme.typography.bodySmall); Text(if (t.open_end) "-" else moneyWhole(t.allowance), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("应收", fontWeight = FontWeight.Bold); Text(moneyWhole(t.expected), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        )
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var balance by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("bank") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加账户") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("名称,如 交通银行1234 / 微信零钱") }, singleLine = true)
                    OutlinedTextField(
                        balance, { balance = it }, label = { Text("当前余额") }, singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KINDS.forEach { (k, label) ->
                            TextButton(onClick = { kind = k }) {
                                Text(if (kind == k) "● $label" else "○ $label")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onAdd(name.trim(), kind, balance.trim().ifEmpty { null })
                    adding = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } }
        )
    }

    editing?.let { a ->
        var balance by remember(a.id) { mutableStateOf(a.balance ?: "") }
        var include by remember(a.id) { mutableStateOf(a.include_in_total) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(a.name) },
            text = {
                Column {
                    OutlinedTextField(balance, { balance = it }, label = { Text("余额(手动校正)") }, singleLine = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("计入总资产", modifier = Modifier.weight(1f))
                        Switch(include, { include = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(
                        a.id,
                        balance.trim().takeIf { it.isNotEmpty() && it != a.balance },
                        include.takeIf { it != a.include_in_total }
                    )
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
