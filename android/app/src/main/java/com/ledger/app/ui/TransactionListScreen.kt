@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material.ExperimentalMaterialApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.ledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.TransactionDto
import com.ledger.app.ui.theme.CategoryBadgeColors
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.TransactionUiState
import com.ledger.app.viewmodel.TxFilter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RANGES = listOf(
    "all" to "全部时间", "cycle" to "当前账期", "prev_cycle" to "上一账期", "month" to "本月", "last30" to "最近30天", "custom" to "自定义"
)

@Composable
fun TransactionListScreen(
    state: TransactionUiState,
    onAddManual: () -> Unit,
    onVoiceTap: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onFilterChange: (TxFilter) -> Unit,
    onSetCategory: (txId: Int, categoryId: Int, sub: String?, isTreat: Boolean) -> Unit,
    onLinkRefund: (incomeId: Int, purchaseId: Int) -> Unit = { _, _ -> },
    onSetTrip: (TransactionDto, Int?) -> Unit = { _, _ -> },
    onNewBusinessTrip: (TransactionDto, String, String, String, String?, String?, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onEditTransaction: (TransactionDto) -> Unit,
    onDeleteTransaction: (txId: Int) -> Unit = {},
    isListening: Boolean
) {
    var pickFor by remember { mutableStateOf<TransactionDto?>(null) }
    var newTripFor by remember { mutableStateOf<TransactionDto?>(null) }
    val listState = rememberLazyListState()
    val refreshing = state.loading || state.syncing
    val pullState = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)

    // keep loading older pages as the user scrolls toward the end of what is loaded
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.transactions.size) { if (nearEnd) onLoadMore() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(onClick = onVoiceTap, containerColor = MaterialTheme.colorScheme.secondary) {
                    Icon(Icons.Filled.Mic, contentDescription = "语音记账", tint = MaterialTheme.colorScheme.onPrimary)
                }
                ExtendedFloatingActionButton(
                    onClick = onAddManual,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("手动记一笔") },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("记账", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.syncing) {
                        Text("同步中…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "同步并刷新") }
                }
            }

            var typed by remember { mutableStateOf(state.filter.query) }
            LaunchedEffect(state.filter.query) { if (state.filter.query != typed.trim()) typed = state.filter.query }
            LaunchedEffect(typed) {
                delay(450)
                if (typed.trim() != state.filter.query.trim()) onFilterChange(state.filter.copy(query = typed.trim()))
            }
            SearchPill(typed, { typed = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            FilterHeader(state, onFilterChange)

            Box(modifier = Modifier.fillMaxSize().pullRefresh(pullState)) {
                if (state.transactions.isEmpty() && !refreshing) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🧾", fontSize = 40.sp)
                        Text(
                            if (state.filter.isActive) "没有符合筛选条件的记录" else "还没有记录, 点右下角开始记吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // always present (zero height until loaded) so a late summary can never push the list out of place
                        item { state.cycleSummary?.let { sm -> CycleSummaryCard(sm) } ?: Box(modifier = Modifier.size(1.dp)) }
                        var lastDay = ""
                        state.transactions.forEach { tx ->
                            val day = tx.occurred_at.take(10)
                            if (day != lastDay) {
                                lastDay = day
                                item(key = "h$day") { DayHeader(day) }
                            }
                            item(key = "t${tx.id}") {
                                TransactionRow(
                                    tx,
                                    state.categories.find { it.id == tx.category_id },
                                    onClick = {},
                                    onLongClick = { pickFor = tx },
                                    fullText = true
                                )
                            }
                        }
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    state.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    state.endReached && state.transactions.isNotEmpty() ->
                                        Text("— 没有更多了 —", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { Box(modifier = Modifier.size(96.dp)) }
                    }
                }

                PullRefreshIndicator(refreshing, pullState, Modifier.align(Alignment.TopCenter))

                if (isListening) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                    ) {
                        Text(
                            "🎙 正在听...说金额和干什么",
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    newTripFor?.let { tx ->
        NewBusinessTripDialog(
            startDate = tx.occurred_at.take(10),
            onDismiss = { newTripFor = null },
            onCreate = { name, start, end, daily, commute, autoTag -> newTripFor = null; onNewBusinessTrip(tx, name, start, end, daily, commute, autoTag) }
        )
    }
    pickFor?.let { tx ->
        CategoryPickerDialog(
            tx = tx,
            categories = state.categories,
            onDismiss = { pickFor = null },
            onConfirm = { catId, sub, isTreat -> pickFor = null; onSetCategory(tx.id, catId, sub, isTreat) },
            onEdit = { pickFor = null; onEditTransaction(tx) },
            onDelete = { pickFor = null; onDeleteTransaction(tx.id) },
            onLinkRefund = { incomeId, purchaseId -> pickFor = null; onLinkRefund(incomeId, purchaseId) }
        )
    }
}

@Composable
private fun CycleSummaryCard(sm: com.ledger.app.data.MonthSummaryDto) {
    val income = sm.total_income.toDoubleOrNull() ?: 0.0
    val expense = sm.total_expense.toDoubleOrNull() ?: 0.0
    val net = income - expense
    fun money(v: Double) = String.format(Locale.CHINA, "%,.2f", kotlin.math.abs(v))
    val label = if (sm.period_start != null && sm.period_end != null)
        "${sm.period_start.substring(5).replace('-', '月')}日 – ${sm.period_end.substring(5).replace('-', '月')}日  发工资至今"
    else "本月"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("收入", style = MaterialTheme.typography.labelSmall)
                    Text("¥" + money(income), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = IncomeColor, maxLines = 1)
                }
                Column {
                    Text("支出", style = MaterialTheme.typography.labelSmall)
                    Text("¥" + money(expense), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ExpenseColor, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("结余", style = MaterialTheme.typography.labelSmall)
                    Text(
                        (if (net < 0) "-¥" else "¥") + money(net),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (net < 0) ExpenseColor else IncomeColor, maxLines = 1
                    )
                }
            }
            Text(
                "已退货的不计入支出, 自己账户间转账不计收支",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun DayHeader(day: String) {
    val label = try {
        val d = LocalDate.parse(day)
        "${d.monthValue}月${d.dayOfMonth}日  ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)}"
    } catch (e: Exception) {
        day
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/** A compact rounded search field. No hint text: the magnifier says what it is. */
@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth().height(40.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close, contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).combinedClickable(onClick = { onChange("") })
                )
            }
        }
    }
}

/** One header cell, like a spreadsheet column header: shows the current choice and drops a list down on tap. */
@Composable
private fun DropChip(
    label: String,
    value: String?,                 // null = not filtering by this column
    options: List<Pair<String?, String>>,
    selectedKey: String?,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val active = value != null
    Box {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.combinedClickable(onClick = { open = true })
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value ?: label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    trailingIcon = { if (key == selectedKey) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; onSelect(key) }
                )
            }
        }
    }
}

@Composable
private fun FilterHeader(state: TransactionUiState, onFilterChange: (TxFilter) -> Unit) {
    val f = state.filter
    val rangeLabel = RANGES.first { it.first == f.range }.second
    val catLabel = f.categoryId?.let { id -> state.categories.find { it.id == id }?.name }
    val typeLabel = when (f.type) { "expense" -> "支出"; "income" -> "收入"; else -> null }
    val kindLabel = when (f.kind) { "normal" -> "不含转账"; "transfer" -> "只看转账"; "family" -> "只看亲情卡"; "mine" -> "不含亲情卡"; else -> null }

    var pickDay by remember { mutableStateOf(false) }
    var pickMonth by remember { mutableStateOf(false) }
    val customLabel = if (f.range == "custom" && f.from != null) customText(f.from, f.to) else null
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DropChip(
            "时间", customLabel ?: (if (f.range == "all") null else rangeLabel),
            listOf<Pair<String?, String>>("pick_day" to "📅 选某一天…", "pick_month" to "🗓 选某个月…") + RANGES.filter { it.first != "custom" }.map { it.first as String? to it.second },
            f.range
        ) {
            when (it) {
                "pick_day" -> pickDay = true
                "pick_month" -> pickMonth = true
                else -> onFilterChange(f.copy(range = it ?: "all", from = null, to = null))
            }
        }
        DropChip("类型", typeLabel, listOf(null to "全部", "expense" to "支出", "income" to "收入"), f.type) {
            onFilterChange(f.copy(type = it, categoryId = null))
        }
        DropChip(
            "类别", catLabel,
            listOf<Pair<String?, String>>(null to "全部类别") +
                state.categories.filter { f.type == null || it.tx_type == f.type }.map { it.id.toString() as String? to ((it.icon ?: "") + " " + it.name) },
            f.categoryId?.toString()
        ) { onFilterChange(f.copy(categoryId = it?.toIntOrNull())) }
        DropChip("转账", kindLabel, listOf(null to "都显示", "normal" to "不含转账", "transfer" to "只看转账", "family" to "只看亲情卡(老婆)", "mine" to "不含亲情卡"), f.kind) {
            onFilterChange(f.copy(kind = it))
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (f.needsReviewOnly) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (f.needsReviewOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.combinedClickable(onClick = { onFilterChange(f.copy(needsReviewOnly = !f.needsReviewOnly)) })
        ) {
            Text(
                "待确认", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (f.needsReviewOnly) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
        if (f.isActive) {
            Text(
                "清除", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.combinedClickable(onClick = { onFilterChange(TxFilter()) }).padding(horizontal = 6.dp, vertical = 8.dp)
            )
        }
    }

    // a picked day / month: step to the neighbour with one tap instead of scrolling and loading
    if (f.range == "custom" && f.from != null && f.to != null) {
        val isDay = f.from == f.to
        val start = LocalDate.parse(f.from)
        fun go(delta: Long) {
            if (isDay) { val d = start.plusDays(delta); onFilterChange(f.copy(from = d.toString(), to = d.toString())) }
            else { val m = start.withDayOfMonth(1).plusMonths(delta); onFilterChange(f.copy(from = m.toString(), to = m.plusMonths(1).minusDays(1).toString())) }
        }
        val atEnd = LocalDate.parse(f.to) >= LocalDate.now()
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { go(-1) }) { Text(if (isDay) "‹ 前一天" else "‹ 上个月") }
            Text(customText(f.from, f.to), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
            TextButton(enabled = !atEnd, onClick = { go(1) }) { Text(if (isDay) "后一天 ›" else "下个月 ›") }
        }
    }

    if (pickDay) {
        val st = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (f.from?.let { LocalDate.parse(it) } ?: LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000L
            }
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onFilterChange(f.copy(range = "custom", from = d.toString(), to = d.toString()))
                    }
                    pickDay = false
                }) { Text("看这一天") }
            },
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text("取消") } }
        ) { androidx.compose.material3.DatePicker(state = st) }
    }
    if (pickMonth) {
        var year by remember { mutableStateOf((f.from?.let { LocalDate.parse(it) } ?: LocalDate.now()).year) }
        AlertDialog(
            onDismissRequest = { pickMonth = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { year -= 1 }) { Text("‹") }
                    Text("${year}年", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    TextButton(enabled = year < LocalDate.now().year, onClick = { year += 1 }) { Text("›") }
                }
            },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..12).forEach { m ->
                        val first = LocalDate.of(year, m, 1)
                        FilterChip(
                            selected = f.range == "custom" && f.from == first.toString() && f.to == first.plusMonths(1).minusDays(1).toString(),
                            enabled = first <= LocalDate.now(),
                            onClick = {
                                onFilterChange(f.copy(range = "custom", from = first.toString(), to = first.plusMonths(1).minusDays(1).toString()))
                                pickMonth = false
                            },
                            label = { Text("${m}月") }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickMonth = false }) { Text("取消") } }
        )
    }
}

private fun customText(from: String?, to: String?): String {
    if (from == null || to == null) return "自定义"
    val a = LocalDate.parse(from)
    return if (from == to) {
        "${a.monthValue}月${a.dayOfMonth}日 " + a.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
    } else "${a.year}年${a.monthValue}月"
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
internal fun CategoryChoiceDialog(
    heading: String, txType: String, selectedId: Int?, categories: List<CategoryDto>,
    onDismiss: () -> Unit, onPick: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改类别", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(heading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("选一个类别,这家商户以后自动归到这一类", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                CategoryGrid(categories.filter { it.tx_type == txType }, selectedId, txType, onPick)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun CategoryPickerDialog(
    tx: TransactionDto,
    categories: List<CategoryDto>,
    onDismiss: () -> Unit,
    onConfirm: (categoryId: Int, sub: String?, isTreat: Boolean) -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onLinkRefund: ((incomeId: Int, purchaseId: Int) -> Unit)? = null
) {
    var selCat by remember { mutableStateOf(tx.category_id) }
    var selSub by remember { mutableStateOf(tx.sub) }
    var selTreat by remember { mutableStateOf(tx.is_treat) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var refundSearch by remember { mutableStateOf(false) }         // null = not searched, empty = searched and found nothing
    var refundCandidates by remember { mutableStateOf<List<TransactionDto>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subs = categories.find { it.id == selCat }?.subs.orEmpty()
    val changed = selCat != tx.category_id || selSub != tx.sub || selTreat != tx.is_treat
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        Text("¥" + tx.amount, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(
                                tx.occurred_at.replace('T', ' ').take(16),
                                tx.merchant?.takeIf { it.isNotBlank() }?.let { m -> m + (tx.note?.takeIf { it.isNotBlank() && it != tx.merchant }?.let { " ($it)" } ?: "") },
                                tx.account_hint?.takeIf { it.isNotBlank() }
                            ).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                CategoryGrid(categories.filter { it.tx_type == tx.tx_type }, selCat, tx.tx_type, onPick = { id -> if (id != selCat) { selCat = id; selSub = null } })
                if (tx.tx_type == "income" && onLinkRefund != null && tx.refund_of_id == null) {
                    TextButton(onClick = {
                        refundSearch = true
                        scope.launch {
                            val found = try { ApiClient.create(context).refundCandidates(tx.id) } catch (_: Exception) { emptyList() }
                            if (found.size == 1) { onLinkRefund(tx.id, found[0].id); refundSearch = false }
                            else refundCandidates = found
                        }
                    }, modifier = Modifier.padding(top = 4.dp)) { Text("🔎 找原消费") }
                    if (refundSearch && refundCandidates == null) Text("搜索中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (refundCandidates?.isEmpty() == true) Text("没找到金额一致的消费记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tx.tx_type == "expense" && subs.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        subs.forEach { s ->
                            // a longer label (e.g. "水果/酸奶") needs more of the row than a short one ("零食"); share
                            // the width by how long the text actually is instead of splitting it evenly
                            CompactChip(text = s, selected = selSub == s, onClick = { selSub = if (selSub == s) null else s }, modifier = Modifier.weight(s.length.toFloat().coerceAtLeast(2f)))
                        }
                    }
                }
                if (tx.tx_type == "expense") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        androidx.compose.material3.Checkbox(checked = selTreat, onCheckedChange = { selTreat = it })
                        Text("算进「吃好吃的」额度", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.padding(top = 8.dp)) { Text("改金额 / 备注") }
                if (onDelete != null) TextButton(
                    onClick = { confirmingDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除这一笔") }
            }
        },
        // a transaction already flagged "待确认" must stay tappable even when the guessed category was right all
        // along and the user changes nothing - only require a real edit for one that wasn't flagged
        confirmButton = { Button(enabled = selCat != null && (changed || tx.needs_review), onClick = { onConfirm(selCat!!, selSub, selTreat) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这一笔?") },
            text = { Text((tx.merchant ?: tx.note ?: "") + "  ¥" + tx.amount + "\n删掉之后找不回来了。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { confirmingDelete = false; onDelete?.invoke() }
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("不删") } }
        )
    }
    val multiple = refundCandidates
    if (multiple != null && multiple.size > 1) {
        AlertDialog(
            onDismissRequest = { refundCandidates = null; refundSearch = false },
            title = { Text("是哪一笔的退款?") },
            text = {
                Column {
                    multiple.forEach { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .clickable { onLinkRefund?.invoke(tx.id, c.id); refundCandidates = null; refundSearch = false },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                (c.merchant?.takeIf { it.isNotBlank() } ?: c.note ?: "") + "\n" + c.occurred_at.replace('T', ' ').take(16),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                            )
                            Text("¥" + c.amount, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { refundCandidates = null; refundSearch = false }) { Text("取消") } }
        )
    }
}

/** A small-category pick: as many as there are (up to 6 for 狗狗), sized to always fit one row without wrapping or
 *  spilling past the dialog edge - the caller gives each one a width share based on how long its label is. */
@Composable
private fun CompactChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun TransactionRow(
    tx: TransactionDto,
    category: CategoryDto?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showDate: Boolean = false,
    fullText: Boolean = false          // drill-down screens: every line in full, wrapped, never cut off with an ellipsis
) {
    val badgeColor = CategoryBadgeColors[Math.floorMod(category?.id ?: 0, CategoryBadgeColors.size)]
    val biz = tx.trip_kind == "business" && !tx.is_reimbursement
    val title = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.note?.takeIf { it.isNotBlank() } ?: (category?.name ?: "未分类")
    val clock = if (tx.occurred_at.length >= 16) tx.occurred_at.substring(11, 16) else ""
    val time = if (showDate && tx.occurred_at.length >= 10)
        tx.occurred_at.substring(5, 10).replace('-', '月') + "日 " + clock else clock
    val refunded = tx.refunded_amount?.toDoubleOrNull() ?: 0.0
    val fullyRefunded = refunded > 0 && refunded >= (tx.amount.toDoubleOrNull() ?: 0.0) - 0.005

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(if (biz) "🧾" else if (tx.is_reimbursement) "💼" else if (tx.is_transfer) "⇄" else (category?.icon ?: "❓"), fontSize = 18.sp)
                }
            }
            // weight(1f) + single-line ellipsis: a long merchant name can never squeeze the amount into a column
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (fullText) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (tx.family_card) "👛亲情卡 · " else "") +
                            (if (biz) "出差垫付 · ${tx.trip_name ?: ""}" else if (tx.is_reimbursement) "出差报销" else if (tx.is_transfer) "转账" else (category?.name ?: "未分类") + (tx.sub?.let { "·$it" } ?: "")) + " · " + time +
                            (if (!tx.note.isNullOrBlank() && tx.note != tx.merchant && !tx.is_transfer) (if (fullText) "\n" else " · ") + tx.note else "") +
                            (if (fullText && !tx.account_hint.isNullOrBlank()) "\n付款: " + tx.account_hint else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (fullText) 8 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (tx.needs_review) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                "待确认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                when {
                    biz -> Text(
                        "待报销, 不计入你的开销", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    tx.is_reimbursement -> Text(
                        "公司报销到账: 只有补贴算收入", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    refunded > 0 -> Text(
                        (if (fullyRefunded) "↩ 已退货, 不计入支出" else "↩ 已部分退款 ¥" + String.format(Locale.CHINA, "%.2f", refunded) + ", 其余计入支出"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    tx.is_transfer -> Text(
                        "⇄ 自己账户之间转账,不计收支", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    tx.refund_of_id != null -> Text(
                        "↩ 退款,已冲抵原支出", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                (if (tx.tx_type == "expense") "-¥" else "+¥") + tx.amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant
                else if (tx.tx_type == "expense") ExpenseColor else IncomeColor,
                textDecoration = if (fullyRefunded) TextDecoration.LineThrough else null,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
