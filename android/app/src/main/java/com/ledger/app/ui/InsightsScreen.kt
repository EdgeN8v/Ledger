package com.ledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.ledger.app.ui.theme.CategoryBadgeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledger.app.data.CategoryStatDto
import com.ledger.app.data.CycleAnalysisDto
import com.ledger.app.data.YearAnalysisDto
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.InsightsUiState
import java.util.Locale

private fun md(date: String) = date.substring(5).replace('-', '月') + "日"

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Plain bars drawn with Canvas - enough for a daily or monthly series without a chart library. */
@Composable
private fun Bars(values: List<Double>, color: Color, modifier: Modifier = Modifier, highlight: Int? = null, onBarClick: ((Int) -> Unit)? = null) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val muted = color.copy(alpha = 0.45f)
    Canvas(
        modifier = modifier.fillMaxWidth().height(90.dp).let { m ->
            if (onBarClick == null || values.isEmpty()) m else m.pointerInput(values.size) {
                detectTapGestures { pos -> onBarClick(((pos.x / size.width) * values.size).toInt().coerceIn(0, values.size - 1)) }
            }
        }
    ) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val gap = (slot * 0.2f).coerceAtMost(6f)
        values.forEachIndexed { i, v ->
            val h = (v / max).toFloat() * size.height
            drawRect(
                color = if (highlight == null || highlight == i) color else muted,
                topLeft = Offset(i * slot + gap / 2, size.height - h),
                size = Size((slot - gap).coerceAtLeast(1f), h.coerceAtLeast(if (v > 0) 2f else 0f))
            )
        }
    }
}

@Composable
fun CycleInsights(
    state: InsightsUiState, onLoad: () -> Unit, onShift: (Int) -> Unit,
    report: com.ledger.app.data.ReportDto? = null, onOpenDetail: (com.ledger.app.viewmodel.DetailRequest) -> Unit = {},
    categories: List<com.ledger.app.data.CategoryDto> = emptyList(), onSetCategory: (Int, Int) -> Unit = { _, _ -> }
) {
    LaunchedEffect(Unit) { onLoad() }
    val c = state.cycle
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { onShift(-1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一账期") }
            Text(
                if (c != null) "${md(c.period_start)} – ${md(c.period_end)}" else "加载中…",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            IconButton(onClick = { onShift(1) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一账期") }
        }
        if (c == null) {
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (report != null) DailyReportCard(report)
        CycleBody(c, onOpenDetail, categories, onSetCategory)
    }
}

@Composable
private fun CycleBody(
    c: CycleAnalysisDto, onOpenDetail: (com.ledger.app.viewmodel.DetailRequest) -> Unit,
    categories: List<com.ledger.app.data.CategoryDto>, onSetCategory: (Int, Int) -> Unit
) {
    val spent = c.total_expense.toDoubleOrNull() ?: 0.0
    val prev = c.previous_expense.toDoubleOrNull() ?: 0.0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("这个账期花了", style = MaterialTheme.typography.labelMedium)
            Text(money(c.total_expense), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ExpenseColor)
            Text(
                "日均 ${money(c.avg_daily)} · 上一账期整期 ${money(c.previous_expense)}" +
                    if (prev > 0) " (现在是它的 ${(spent / prev * 100).toInt()}%)" else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    SectionCard("看一眼就懂") {
        c.insights.forEach {
            Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }

    SectionCard("花在哪些类别") {
        c.categories.forEach { cat ->
            CategoryBar(cat) {
                onOpenDetail(com.ledger.app.viewmodel.DetailRequest(cat.name, cat.category_id?.takeIf { it > 0 }, null, c.period_start, c.period_end, businessOnly = cat.category_id == -2))
            }
        }
        Text("点一个类别, 看这期间这一类的每一笔", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }

    var byWeek by remember { mutableStateOf(false) }
    SectionCard(if (byWeek) "每周花了多少" else "每天花了多少") {
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byWeek, onClick = { byWeek = false }, label = { Text("按天") })
            FilterChip(selected = byWeek, onClick = { byWeek = true }, label = { Text("按周") })
        }
        val daily = c.daily
        val weekly = c.weekly
        val values = if (byWeek) weekly.map { it.total.toDoubleOrNull() ?: 0.0 } else daily.map { it.total.toDoubleOrNull() ?: 0.0 }
        val peakIdx = values.indices.maxByOrNull { values[it] }
        Bars(
            values, ExpenseColor, Modifier.padding(top = 10.dp), highlight = peakIdx,
            onBarClick = { i ->
                if (byWeek) {
                    val w = weekly[i]
                    onOpenDetail(com.ledger.app.viewmodel.DetailRequest("${md(w.start)} – ${md(w.end)} 这一周", null, null, w.start, w.end))
                } else {
                    val d = daily[i]
                    onOpenDetail(com.ledger.app.viewmodel.DetailRequest("${md(d.date)} 这一天", null, null, d.date, d.date))
                }
            }
        )
        if (peakIdx != null && values[peakIdx] > 0) {
            val label = if (byWeek) "${md(weekly[peakIdx].start)}–${md(weekly[peakIdx].end)}" else md(daily[peakIdx].date)
            Text("最高: $label ${money(values[peakIdx].toString())}   (点柱子看那${if (byWeek) "一周" else "一天"}花了什么)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }

    SectionCard("周几花得多") {
        val days = listOf("一", "二", "三", "四", "五", "六", "日")
        val values = c.weekday_totals.map { it.toDoubleOrNull() ?: 0.0 }
        Bars(values, MaterialTheme.colorScheme.primary, Modifier.padding(top = 10.dp), highlight = values.indices.maxByOrNull { values[it] })
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            days.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }

    if (c.top_shops.isNotEmpty()) {
        SectionCard("钱都去了哪些商家") {
            c.top_shops.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clickable { onOpenDetail(com.ledger.app.viewmodel.DetailRequest(s.name, null, s.name, c.period_start, c.period_end)) },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${s.name}  ×${s.count}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(money(s.total), fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }

    if (c.biggest.isNotEmpty()) {
        var changing by remember { mutableStateOf<com.ledger.app.data.BigSpendDto?>(null) }
        SectionCard("最大的几笔 (点一笔可以改类别)") {
            c.biggest.forEach { bgt ->
                val cat = categories.find { it.id == bgt.category_id }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { changing = bgt },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = CategoryBadgeColors[Math.floorMod(bgt.category_id ?: 0, CategoryBadgeColors.size)], modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text(cat?.icon ?: "❓") }
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(bgt.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${md(bgt.date)} · ${bgt.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(money(bgt.amount), fontWeight = FontWeight.Bold, color = ExpenseColor, maxLines = 1)
                }
            }
        }
        changing?.let { b ->
            CategoryChoiceDialog(
                heading = b.merchant + "  " + money(b.amount), txType = "expense", selectedId = b.category_id, categories = categories,
                onDismiss = { changing = null }, onPick = { cid -> changing = null; onSetCategory(b.id, cid) }
            )
        }
    }
    Box(modifier = Modifier.size(24.dp))
}

@Composable
private fun CategoryBar(c: CategoryStatDto, onClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(top = 10.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${c.icon ?: ""} ${c.name}", modifier = Modifier.weight(1f), maxLines = 1)
            val ch = c.change_pct
            if (ch != null) {
                Text(
                    (if (ch >= 0) "↑" else "↓") + String.format(Locale.CHINA, "%.0f%%", kotlin.math.abs(ch)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ch >= 20) ExpenseColor else if (ch <= -20) IncomeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(money(c.total) + if (c.count > 0) "  ${c.count}笔" else "", fontWeight = FontWeight.Medium, maxLines = 1)
        }
        LinearProgressIndicator(
            progress = { c.share.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

@Composable
fun YearInsights(state: InsightsUiState, onLoad: (Int) -> Unit) {
    LaunchedEffect(Unit) { onLoad(state.selYear) }
    val y = state.year
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { onLoad(state.selYear - 1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一年") }
            Text("${state.selYear} 年", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = { onLoad(state.selYear + 1) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一年") }
        }
        if (y == null) {
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        YearBody(y)
    }
}

@Composable
private fun YearBody(y: YearAnalysisDto) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("全年(按发工资的账期算)", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("收入", style = MaterialTheme.typography.labelSmall); Text(money(y.income), fontWeight = FontWeight.Bold, color = IncomeColor, maxLines = 1) }
                Column { Text("支出", style = MaterialTheme.typography.labelSmall); Text(money(y.expense), fontWeight = FontWeight.Bold, color = ExpenseColor, maxLines = 1) }
                Column(horizontalAlignment = Alignment.End) {
                    Text("存下", style = MaterialTheme.typography.labelSmall)
                    Text(money(y.saved), fontWeight = FontWeight.Bold, color = if ((y.saved.toDoubleOrNull() ?: 0.0) < 0) ExpenseColor else IncomeColor, maxLines = 1)
                }
            }
            Text(
                "储蓄率 ${String.format(Locale.CHINA, "%.0f%%", y.saving_rate * 100)} · 平均每个账期花 ${money(y.avg_monthly_expense)}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "收入只统计短信里看得到的(建行工资等)。交通银行的年终奖、公积金没有短信, 请在记账页「手动记一笔」补上收入, 这里才准。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    SectionCard("每个账期的收入与支出") {
        val maxV = y.months.maxOfOrNull { maxOf(it.income.toDoubleOrNull() ?: 0.0, it.expense.toDoubleOrNull() ?: 0.0) }?.coerceAtLeast(1.0) ?: 1.0
        y.months.forEach { m ->
            val inc = m.income.toDoubleOrNull() ?: 0.0
            val exp = m.expense.toDoubleOrNull() ?: 0.0
            val saved = m.saved.toDoubleOrNull() ?: 0.0
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${m.month}月起 (${md(m.period_start)}–${md(m.period_end)})", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text((if (saved >= 0) "存 " else "亏 ") + money(kotlin.math.abs(saved).toString()),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (saved >= 0) IncomeColor else ExpenseColor)
                }
                LinearProgressIndicator(progress = { (inc / maxV).toFloat().coerceIn(0f, 1f) }, color = IncomeColor, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                LinearProgressIndicator(progress = { (exp / maxV).toFloat().coerceIn(0f, 1f) }, color = ExpenseColor, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
            }
        }
        Text("绿=收入 红=支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }

    if (y.best_month != null && y.worst_month != null && y.months.size > 1) {
        SectionCard("最好和最差") {
            Text("存得最多: ${y.best_month}月起的账期", modifier = Modifier.padding(top = 8.dp))
            Text("存得最少: ${y.worst_month}月起的账期", modifier = Modifier.padding(top = 4.dp))
        }
    }

    SectionCard("全年钱花在了哪") { y.categories.forEach { CategoryBar(it) } }
    Box(modifier = Modifier.size(24.dp))
}

/** The analysis the server writes by itself every morning. */
@Composable
fun DailyReportCard(r: com.ledger.app.data.ReportDto) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("每日分析 · 服务器每天早上自动生成", style = MaterialTheme.typography.labelMedium)
            Text(r.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            r.alerts.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            r.subscriptions.forEach { Text("🔁 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            Text(
                "更新于 ${r.generated_at.replace('T', ' ').take(16)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
