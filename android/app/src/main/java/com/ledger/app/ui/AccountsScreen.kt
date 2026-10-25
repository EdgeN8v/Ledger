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
    LaunchedEffect(Unit) { onRefresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("资产", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("总资产 (计入总额的账户合计)", style = MaterialTheme.typography.labelMedium)
                Text(
                    "¥" + (state.overview?.total ?: "0.00"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "银行卡的余额来自银行短信,每笔交易后自动更新",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // part of the total is already spoken for (the rent that is paid every 3 months but set aside every
                // month): shown apart, so what is left is what is really free to save or spend
                val spoken = state.overview?.earmarked.orEmpty()
                if (spoken.isNotEmpty()) {
                    spoken.forEach { e ->
                        val due = e.due?.takeIf { it.length >= 10 }?.let { " · ${it.substring(5, 7).trimStart('0')}月${it.substring(8, 10).trimStart('0')}日前要付" } ?: ""
                        Text(
                            "其中 ${e.name}专款 ${money(e.amount)} / ${money(e.cap)}$due",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    Text("可自由支配 ${money(state.overview?.free_total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        val owed = state.overview?.receivable?.toDoubleOrNull() ?: 0.0
        if (owed > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                onClick = { showReceivable = true },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🧾 待报销 ${money(state.overview?.receivable)} (不计入总资产, 但这是你的钱)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    state.overview?.receivable_trips?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }

        if (state.error != null) {
            Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.overview?.accounts ?: emptyList()) { a ->
                Card(
                    onClick = { editing = a },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (KINDS.find { it.first == a.kind }?.second ?: a.kind) +
                                    (a.balance_at?.let { " · 更新于 " + it.replace('T', ' ').take(16) } ?: " · 还没有余额") +
                                    (if (!a.include_in_total) " · 不计入总额" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "¥" + (a.balance ?: "-"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("添加账户 (现金 / 钱包 / 不发短信的卡)")
        }
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
                                Text(money(it.amount), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("垫付小计", style = MaterialTheme.typography.bodySmall); Text(money(t.advanced), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (t.open_end) "补贴(回程定了再算)" else "补贴(每天×天数+通勤)", style = MaterialTheme.typography.bodySmall); Text(if (t.open_end) "-" else money(t.allowance), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("应收", fontWeight = FontWeight.Bold); Text(money(t.expected), fontWeight = FontWeight.Bold)
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
