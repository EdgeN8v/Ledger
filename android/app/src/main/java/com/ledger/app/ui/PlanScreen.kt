package com.ledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.ApiClient
import com.ledger.app.data.FixedHistoryEntryDto
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.PlanUiState
import kotlinx.coroutines.launch
import java.util.Locale

internal fun money(v: String?, signed: Boolean = false): String {
    val d = v?.toDoubleOrNull() ?: return "-"
    val body = String.format(Locale.CHINA, "%,.2f", kotlin.math.abs(d))
    return (if (d < 0) "-" else if (signed) "+" else "") + "¥" + body
}

@Composable
fun PlanScreen(
    state: PlanUiState, onLoad: () -> Unit, onSave: (String?, String?, String?, String?) -> Unit,
    onMark: (name: String, paid: Boolean?, paidUntil: String?) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf<com.ledger.app.data.FixedItemDto?>(null) }
    var breakdown by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<String?>(null) }
    var historyRows by remember { mutableStateOf<List<FixedHistoryEntryDto>?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { onLoad() }
    val st = state.status
    val plan = state.plan

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        var help by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("存钱计划", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(22.dp).clickable { help = true }
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("i", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (help) AlertDialog(
            onDismissRequest = { help = false },
            confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } },
            title = { Text("计划是怎么算的") },
            text = {
                Text(
                    "· 可花预算 = 月收入 − 固定支出 − 要存的钱。先存,剩下的才是能花的。\n" +
                        "· 工资每月不固定:发工资前按最近三次里最低的一次估算,发了以后按实际到账的算。\n" +
                        "· 房租、停车费这种一次付好几个月的钱,每月先预留,不会让某一天的额度突然变成负数。\n" +
                        "· 出行的花销照样计入账期,但不算进日常花钱的速度里。\n" +
                        "· 月底前5天,快到期的固定账单会在这页顶部显示,并且推送通知。"
                )
            }
        )

        if (state.reminders.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔔 月底前要交", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    state.reminders.forEach { b ->
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(b.name + (if (b.days_left == 0) "  今天" else "  还剩${b.days_left}天"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(money(b.amount), fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }

        if (st != null) {
            val (title, color) = when (st.state) {
                "on_track" -> "✅ 在预算内" to IncomeColor
                "over" -> "❌ 本账期已超预算" to ExpenseColor
                else -> "还没有存钱目标, 先定一个" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "${st.period_start.substring(5).replace('-', '月')}日 – ${st.period_end.substring(5).replace('-', '月')}日" +
                            "  第${st.days_elapsed}/${st.days_total}天",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp))

                    val budget = st.budget.toDoubleOrNull() ?: 0.0
                    val spent = st.spent.toDoubleOrNull() ?: 0.0
                    LinearProgressIndicator(
                        progress = { if (budget > 0) (spent / budget).toFloat().coerceIn(0f, 1f) else 1f },
                        color = color,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    )
                    Text(
                        "已用 ${money(st.spent)} / 预算 ${money(st.budget)}",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.clickable { breakdown = true }) {
                            Metric("还能花 ⓘ", money(st.remaining), if ((st.remaining.toDoubleOrNull() ?: 0.0) < 0) ExpenseColor else IncomeColor)
                        }
                        if (st.treat_budget != null) {
                            Metric("吃好吃的还剩", money(st.treat_remaining),
                                if ((st.treat_remaining?.toDoubleOrNull() ?: 0.0) < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface)
                        } else {
                            Metric("剩 ${st.days_left} 天, 每天可花", money(st.daily_allowance), MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("已存", money(st.saved_so_far), MaterialTheme.colorScheme.onSurface)
                        Metric("目标存款", money(st.savings_target), MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        if (st != null && st.fixed_items.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("固定支出 (每月先预留 ${money(st.fixed_total)})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    st.fixed_items.forEach { f ->
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { marking = f }, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name + (f.due_day?.let { "  (每月${it}号)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                if (f.pot != null && f.pot_cap != null) {
                                    // lumpy bill (rent every 3 months): what has been set aside so far for the next one
                                    Text("已攒 ${money(f.pot)} / ${money(f.pot_cap)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    "历史", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        historyFor = f.name; historyRows = null; historyError = null
                                        scope.launch {
                                            try { historyRows = ApiClient.create(context).fixedHistory(f.name) }
                                            catch (e: Exception) { historyError = e.message ?: "加载失败" }
                                        }
                                    }
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(f.reserve), fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(when (f.state) { "paid" -> if (f.paid_note != null) "✅ ${f.paid_note}" else "✅ 本期已付 ${money(f.paid)}"; "waiting" -> "⏳ 本期还没付"; else -> "🔒 已预留" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("我的计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // pay is not the same every month, so show what this cycle really is: what has arrived, and only
                // before pay day a cautious guess, labelled as one
                if (st != null) {
                    PlanRow(if (st.income_is_estimate) "本期收入(预估)" else "本期收入", money(st.income_basis))
                    Text(
                        if (st.income_is_estimate || st.income_items.isNullOrEmpty()) "工资还没到账, 先按最近三次工资里最低的一次估算; 到账后按实际的算"
                        else st.income_items.joinToString(" + ") { it.name + " " + money(it.amount) } + "  (实际到账)",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    PlanRow("本期收入", "按实际到账的算")
                }
                PlanRow("固定支出", if (st != null && st.fixed_items.isNotEmpty()) "按上面的明细" else (plan?.fixed_expenses?.let { money(it) } ?: "未设置"))
                PlanRow("每月要存", plan?.savings_target?.let { money(it) } ?: "未设置")
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("修改计划") }
            }
        }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }

    historyFor?.let { name ->
        AlertDialog(
            onDismissRequest = { historyFor = null },
            confirmButton = { TextButton(onClick = { historyFor = null }) { Text("知道了") } },
            title = { Text("$name 历史") },
            text = {
                Column {
                    when {
                        historyError != null -> Text(historyError!!, color = MaterialTheme.colorScheme.error)
                        historyRows == null -> Text("加载中…")
                        historyRows!!.all { it.amount.toDoubleOrNull() == 0.0 } -> Text("还没有记录")
                        else -> historyRows!!.forEach { h ->
                            if (h.amount.toDoubleOrNull() != 0.0) {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(h.period_start.substring(5).replace('-', '月') + "日 – " + h.period_end.substring(5).replace('-', '月') + "日",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(money(h.amount), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (breakdown && st != null) {
        AlertDialog(
            onDismissRequest = { breakdown = false },
            confirmButton = { TextButton(onClick = { breakdown = false }) { Text("知道了") } },
            title = { Text("「还能花」是怎么算的") },
            text = {
                Column {
                    Text("本账期收入 ${money(st.income_basis)}\n− 固定支出 ${money(st.fixed_total)}\n− 要存 ${money(st.savings_target)}\n= 自由预算 ${money(st.budget)}")
                    Text("\n− 日常花销 ${money(st.spent)}\n− 出行 ${money(st.trip_total)}\n= 还能花 ${money(st.remaining)}", fontWeight = FontWeight.Bold)
                    Text("\n账期从发工资那天(20号)算到下个月19号。发工资当天起,固定支出先扣掉,剩下的才是自由预算; 大额的一次性花销(比如车品)会一下子把它用光。哪些钱花在哪,在「统计 → 花在哪里」里看。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    marking?.let { f ->
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { marking = null },
            title = { Text(f.name) },
            text = {
                Column {
                    Text("每个账期从工资到账那天(20号)开始,到下个月19号。这一期的账单没付/付过了,可以在这里标一下。", style = MaterialTheme.typography.bodySmall)
                    if (f.state == "paid" && f.paid_note != null) Text("现在: ${f.paid_note}", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { marking = null; onMark(f.name, true, null) }) { Text("✅ 本期已付") }
                    TextButton(onClick = {
                        val d = java.time.LocalDate.now().plusMonths(1)
                        android.app.DatePickerDialog(context, { _, y, m, day ->
                            marking = null
                            onMark(f.name, true, java.time.LocalDate.of(y, m + 1, day).toString())
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }) { Text("📅 已提前付到某一天…") }
                    TextButton(onClick = { marking = null; onMark(f.name, false, "") }) { Text("⏳ 本期还没付(清除标记)") }
                }
            },
            dismissButton = { TextButton(onClick = { marking = null }) { Text("关闭") } }
        )
    }

    if (editing) {
        var income by remember { mutableStateOf(plan?.expected_income ?: "") }
        var fixed by remember { mutableStateOf(plan?.fixed_expenses ?: "") }
        var target by remember { mutableStateOf(plan?.savings_target ?: "") }
        var treat by remember { mutableStateOf(plan?.treat_budget ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("修改计划") },
            text = {
                Column {
                    OutlinedTextField(income, { income = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("收入保底(选填; 工资不固定就留空)") })
                    OutlinedTextField(fixed, { fixed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("固定支出, 如房租") })
                    OutlinedTextField(target, { target = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("每月要存多少") })
                    OutlinedTextField(treat, { treat = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("每月吃好吃的额度 (空白=不开启)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onSave(income.trim().ifEmpty { null }, target.trim().ifEmpty { null }, fixed.trim().ifEmpty { null }, treat.trim().ifEmpty { null })
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun PlanRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
