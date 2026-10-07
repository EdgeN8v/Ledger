@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material.ExperimentalMaterialApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.ledger.app.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.TransactionDto
import com.ledger.app.ui.theme.CategoryBadgeColors
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.TransactionUiState
import com.ledger.app.viewmodel.TxFilter
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val RANGES = listOf(
    "all" to "全部时间", "cycle" to "当前账期", "prev_cycle" to "上一账期", "month" to "本月", "last30" to "最近30天"
)

@Composable
fun TransactionListScreen(
    state: TransactionUiState,
    onAddManual: () -> Unit,
    onVoiceTap: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onFilterChange: (TxFilter) -> Unit,
    onSetCategory: (txId: Int, categoryId: Int) -> Unit,
    onSetTrip: (TransactionDto, Int?) -> Unit = { _, _ -> },
    onNewBusinessTrip: (TransactionDto, String, String, String, String?, String?, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onEditTransaction: (TransactionDto) -> Unit,
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
                                    onClick = { onEditTransaction(tx) },
                                    onLongClick = { pickFor = tx }
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
            onPick = { catId -> pickFor = null; onSetCategory(tx.id, catId) },
            trips = state.businessTrips,
            onTrip = { tripId -> pickFor = null; onSetTrip(tx, tripId) },
            onNewTrip = { pickFor = null; newTripFor = tx }
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
    val kindLabel = when (f.kind) { "normal" -> "不含转账"; "transfer" -> "只看转账"; else -> null }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DropChip("时间", if (f.range == "all") null else rangeLabel, RANGES.map { it.first as String? to it.second }, f.range) {
            onFilterChange(f.copy(range = it ?: "all"))
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
        DropChip("转账", kindLabel, listOf(null to "都显示", "normal" to "不含转账", "transfer" to "只看转账"), f.kind) {
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
    onPick: (Int) -> Unit,
    trips: List<com.ledger.app.data.TripDto> = emptyList(),
    onTrip: (Int?) -> Unit = {},
    onNewTrip: (() -> Unit)? = null
) {
    val canBusiness = tx.tx_type == "expense" && !tx.is_reimbursement && (onNewTrip != null || trips.isNotEmpty() || tx.trip_id != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这笔怎么记", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (tx.merchant ?: tx.note ?: "") + "  ¥" + tx.amount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (canBusiness) {
                    Text(
                        "🧾 出差垫付: 之后公司会报销, 不算你的开销",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        trips.forEach { t ->
                            FilterChip(selected = tx.trip_id == t.id, onClick = { onTrip(t.id) }, label = { Text("归入「${t.name}」") })
                        }
                        if (onNewTrip != null) FilterChip(selected = false, onClick = onNewTrip, label = { Text("＋ 新建一次出差") })
                        if (tx.trip_id != null) FilterChip(selected = false, onClick = { onTrip(null) }, label = { Text("移出出差") })
                    }
                }
                Text(
                    if (canBusiness) "或者: 选一个类别,这家商户以后自动归到这一类" else "选一个类别,这家商户以后自动归到这一类",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                CategoryGrid(categories.filter { it.tx_type == tx.tx_type }, if (tx.trip_id == null) tx.category_id else null, tx.tx_type, onPick)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun TransactionRow(
    tx: TransactionDto,
    category: CategoryDto?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showDate: Boolean = false
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (fullyRefunded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (biz) "出差垫付 · ${tx.trip_name ?: ""}" else if (tx.is_reimbursement) "出差报销" else if (tx.is_transfer) "转账" else (category?.name ?: "未分类")) + " · " + time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
