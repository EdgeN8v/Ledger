package com.ledger.app.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.PlanUiState
import java.util.Locale

internal fun money(v: String?, signed: Boolean = false): String {
    val d = v?.toDoubleOrNull() ?: return "-"
    val body = String.format(Locale.CHINA, "%,.2f", kotlin.math.abs(d))
    return (if (d < 0) "-" else if (signed) "+" else "") + "¥" + body
}

@Composable
fun PlanScreen(state: PlanUiState, onLoad: () -> Unit, onSave: (String?, String?, String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoad() }
    val st = state.status
    val plan = state.plan

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("存钱计划", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "目的只有一个: 每个月把该存的先存下来, 剩下的才是能花的",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (st != null) {
            val (title, color) = when (st.state) {
                "on_track" -> "✅ 在计划内, 照这个节奏能存下" to IncomeColor
                "at_risk" -> "⚠️ 照这个花法会超预算" to Color(0xFFE08A00)
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
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("自由花销已用 ${money(st.spent)}", style = MaterialTheme.typography.bodyMedium)
                        Text("自由预算 ${money(st.budget)}", style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("还能花", money(st.remaining), if ((st.remaining.toDoubleOrNull() ?: 0.0) < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface)
                        Metric("剩 ${st.days_left} 天, 每天可花", money(st.daily_allowance), MaterialTheme.colorScheme.onSurface)
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("预计月底能存", money(st.projected_saving), if ((st.projected_saving.toDoubleOrNull() ?: 0.0) >= (st.savings_target.toDoubleOrNull() ?: 0.0)) IncomeColor else ExpenseColor)
                        Metric("目标存款", money(st.savings_target), MaterialTheme.colorScheme.onSurface)
                    }
                    if ((st.trip_total.toDoubleOrNull() ?: 0.0) > 0) Text(
                        "其中出行 ${money(st.trip_total)} 已花掉, 但没有算进日常速度里",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "按现在的速度, 本账期预计花 ${money(st.projected_spend)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
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
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name + (f.due_day?.let { "  (每月${it}号)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                if (!f.note.isNullOrBlank()) Text(f.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(f.reserve), fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(when (f.state) { "paid" -> "✅ 本期已付 ${money(f.paid)}"; "waiting" -> "⏳ 本期还没付"; else -> "🔒 已预留" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "上面这些先扣掉再算可花预算, 所以房租、停车费这种一次付好几个月的钱, 不会让你某一天的可花额度突然变成负数。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("我的计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PlanRow("每月月收入",
                    (plan?.expected_income?.let { money(it) } ?: ("自动 " + money(plan?.estimated_income))))
                PlanRow("固定支出", if (st != null && st.fixed_items.isNotEmpty()) "按上面的明细" else (plan?.fixed_expenses?.let { money(it) } ?: "未设置"))
                PlanRow("每月要存", plan?.savings_target?.let { money(it) } ?: "未设置")
                Text(
                    "可花预算 = 月收入 − 固定支出 − 要存的钱。你的工资每月不固定, 月收入可以不填: 发工资前按最近三次工资里最低的一次估算, 发了以后按实际到账的算。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("修改计划") }
            }
        }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }

    if (editing) {
        var income by remember { mutableStateOf(plan?.expected_income ?: "") }
        var fixed by remember { mutableStateOf(plan?.fixed_expenses ?: "") }
        var target by remember { mutableStateOf(plan?.savings_target ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("修改计划") },
            text = {
                Column {
                    OutlinedTextField(income, { income = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("每月收入 (空白=按工资自动算)") })
                    OutlinedTextField(fixed, { fixed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("固定支出, 如房租") })
                    OutlinedTextField(target, { target = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("每月要存多少") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onSave(income.trim().ifEmpty { null }, target.trim().ifEmpty { null }, fixed.trim().ifEmpty { null })
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
