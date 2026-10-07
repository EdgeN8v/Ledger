package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.app.data.SummaryByCategoryDto
import com.ledger.app.ui.theme.CategoryBadgeColors
import com.ledger.app.ui.theme.ExpenseColor
import com.ledger.app.ui.theme.IncomeColor
import com.ledger.app.viewmodel.StatisticsViewModel

@Composable
fun StatisticsScreen(onOpenDetail: (com.ledger.app.viewmodel.DetailRequest) -> Unit = {}) {
    val viewModel: StatisticsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { viewModel.previousMonth() }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
            }
            Text(
                state.summary?.let { sm ->
                    if (sm.period_start != null && sm.period_end != null && !sm.period_start.endsWith("-01"))
                        "${sm.period_start.substring(5).replace('-', '月')}日 – ${sm.period_end.substring(5).replace('-', '月')}日"
                    else "${state.year}年${state.month}月"
                } ?: "${state.year}年${state.month}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp).clickable { showPicker = true }
            )
            IconButton(onClick = { viewModel.nextMonth() }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
            }
        }

        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val summary = state.summary
        if (summary == null) {
            Text(
                state.error ?: "暂无数据",
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val income = summary.total_income.toDoubleOrNull() ?: 0.0
        val expense = summary.total_expense.toDoubleOrNull() ?: 0.0

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("收入", income, IncomeColor, Modifier.weight(1f))
            StatCard("支出", expense, ExpenseColor, Modifier.weight(1f))
            StatCard("结余", income - expense, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }

        val expenseCategories = summary.by_category
            .filter { it.tx_type == "expense" }
            .sortedByDescending { it.total.toDoubleOrNull() ?: 0.0 }

        Text(
            "支出分类",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )

        if (expenseCategories.isEmpty()) {
            Text("这个月还没有支出记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val maxTotal = expenseCategories.maxOf { it.total.toDoubleOrNull() ?: 0.0 }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(expenseCategories) { category ->
                    CategoryBar(category, maxTotal) {
                        val from = summary.period_start ?: "%04d-%02d-01".format(state.year, state.month)
                        val to = summary.period_end ?: "%04d-%02d-28".format(state.year, state.month)
                        onOpenDetail(
                            com.ledger.app.viewmodel.DetailRequest(
                                title = category.category_name,
                                categoryId = category.category_id?.takeIf { it > 0 },
                                query = null, dateFrom = from, dateTo = to,
                                businessOnly = category.category_id == -2
                            )
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        MonthYearPickerDialog(
            initialYear = state.year,
            initialMonth = state.month,
            onDismiss = { showPicker = false },
            onConfirm = { year, month ->
                viewModel.setYearMonth(year, month)
                showPicker = false
            }
        )
    }
}

@Composable
private fun MonthYearPickerDialog(
    initialYear: Int,
    initialMonth: Int,
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int) -> Unit
) {
    var year by remember { mutableIntStateOf(initialYear) }
    var selectedMonth by remember { mutableIntStateOf(initialMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(year, selectedMonth) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { year -= 1 }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上一年")
                }
                Text("${year}年", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { year += 1 }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下一年")
                }
            }
        },
        text = {
            Column {
                for (row in 0 until 4) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (col in 1..3) {
                            val month = row * 3 + col
                            val isSelected = month == selectedMonth
                            Card(
                                onClick = { selectedMonth = month },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(
                                    "${month}月",
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun StatCard(label: String, amount: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "¥${"%.2f".format(amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun CategoryBar(category: SummaryByCategoryDto, maxTotal: Double, onClick: () -> Unit) {
    val total = category.total.toDoubleOrNull() ?: 0.0
    val fraction = if (maxTotal > 0) (total / maxTotal).toFloat().coerceIn(0.05f, 1f) else 0.05f
    val barColor = CategoryBadgeColors[Math.floorMod(category.category_id ?: 0, CategoryBadgeColors.size)]

    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(category.category_name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("¥${"%.2f".format(total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .background(barColor, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}
