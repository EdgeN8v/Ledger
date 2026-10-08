package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.viewmodel.CarUiState

/** What the car costs: fuel, upkeep and insurance are the "core"; parking, washing, tolls and parts sit beside it. */
@Composable
fun CarScreen(state: CarUiState, onLoad: (String) -> Unit) {
    LaunchedEffect(Unit) { onLoad(state.range) }
    val r = state.report
    var open by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.range == "year", onClick = { onLoad("year") }, label = { Text("今年") })
            FilterChip(selected = state.range == "12m", onClick = { onLoad("12m") }, label = { Text("最近12个月") })
        }
        if (r == null) {
            Text(if (state.loading) "加载中…" else (state.error ?: ""), modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        if (r.first_record > r.since) Text(
            "账本里最早的用车记录是 ${r.first_record.substring(5).replace('-', '月')}日, 之前的没记。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("油 + 保养维修 + 保险", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(r.core_total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ExpenseColor)
                Text("加上停车、洗车、过路、配件等一共 ${money(r.all_total)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (r.fuel.count > 0) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⛽ 加油", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("共 ${r.fuel.count} 次, 合计 ${money(r.fuel.total)}", modifier = Modifier.padding(top = 6.dp))
                Text("平均每次 ${money(r.fuel.avg)} · 最多一次 ${money(r.fuel.max)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val peak = (r.monthly.maxOfOrNull { (it.fuel.toDoubleOrNull() ?: 0.0) + (it.repair.toDoubleOrNull() ?: 0.0) + (it.other.toDoubleOrNull() ?: 0.0) } ?: 0.0).coerceAtLeast(1.0)
        if (r.monthly.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("每月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("绿=加油 · 橙=保养保险 · 灰=其他", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.monthly.forEach { m ->
                    val f = m.fuel.toDoubleOrNull() ?: 0.0; val u = m.repair.toDoubleOrNull() ?: 0.0; val o = m.other.toDoubleOrNull() ?: 0.0
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.month.substring(2), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        Row(modifier = Modifier.weight(1f).height(14.dp).clip(MaterialTheme.shapes.small)) {
                            if (f > 0) Box(Modifier.weight((f / peak).toFloat().coerceAtLeast(0.001f)).height(14.dp).background(Color(0xFF66BB6A)))
                            if (u > 0) Box(Modifier.weight((u / peak).toFloat().coerceAtLeast(0.001f)).height(14.dp).background(Color(0xFFFFA726)))
                            if (o > 0) Box(Modifier.weight((o / peak).toFloat().coerceAtLeast(0.001f)).height(14.dp).background(Color(0xFF9E9E9E)))
                            val rest = 1.0 - (f + u + o) / peak
                            if (rest > 0.001) Box(Modifier.weight(rest.toFloat()).height(14.dp))
                        }
                        Text(money((f + u + o).toString()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        Text("明细(点开看每一笔)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        r.buckets.forEach { b ->
            if (b.count == 0 && !b.core) return@forEach
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { open = if (open == b.key) null else b.key }) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(b.label + (if (b.core) "" else "  (还没定算不算)"), fontWeight = FontWeight.Medium)
                        Text("${money(b.total)} · ${b.count}笔")
                    }
                    if (open == b.key) b.items.forEach {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(it.date.substring(5) + "  " + it.merchant, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(money(it.amount), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text(
            "油耗(每百公里几升)需要升数和里程, 短信里没有, 这里只统计花了多少钱。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        )
    }
}
