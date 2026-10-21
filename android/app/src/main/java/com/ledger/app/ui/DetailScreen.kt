@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.TransactionDto
import com.ledger.app.viewmodel.DetailUiState

private val SORTS = listOf(
    "time_desc" to "时间 新→旧", "time_asc" to "时间 旧→新", "amount_desc" to "金额 大→小", "amount_asc" to "金额 小→大"
)

/** Every transaction behind a number on the statistics pages, sortable by time or by amount. */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onSort: (String) -> Unit,
    onLoadMore: () -> Unit,
    onSetCategory: (txId: Int, categoryId: Int, sub: String?, isTreat: Boolean) -> Unit,
    onSubFilter: (String?) -> Unit = {},
    onDeleteTransaction: (txId: Int) -> Unit = {}
) {
    val req = state.request ?: return
    val listState = rememberLazyListState()
    var pickFor by remember { mutableStateOf<TransactionDto?>(null) }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) onLoadMore() }

    val total = state.items.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Column {
                Text(req.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "${req.dateFrom.substring(5).replace('-', '月')}日 – ${req.dateTo.substring(5).replace('-', '月')}日" +
                        if (!state.loading) "  ·  已列出 ${state.items.size} 笔" + (if (!state.endReached) "+" else "") else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SORTS.forEach { (k, label) -> FilterChip(selected = state.sort == k, onClick = { onSort(k) }, label = { Text(label) }) }
        }
        if (state.subs.isNotEmpty()) {
            val allTotal = state.subs.sumOf { it.total.toDoubleOrNull() ?: 0.0 }
            FlowRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = req.sub == null, onClick = { onSubFilter(null) },
                    label = { Text("全部 ¥" + String.format(java.util.Locale.CHINA, "%,.0f", allTotal)) })
                state.subs.forEach { s ->
                    val key = s.sub ?: "__none__"
                    FilterChip(selected = req.sub == key, onClick = { onSubFilter(key) },
                        label = { Text((s.sub ?: "未细分") + " ¥" + String.format(java.util.Locale.CHINA, "%,.0f", s.total.toDoubleOrNull() ?: 0.0) + " · ${s.count}笔") })
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.items.isEmpty()) {
                Text("这个范围内没有记录", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items.size, key = { state.items[it].id }) { i ->
                        val tx = state.items[i]
                        TransactionRow(
                            tx, state.categories.find { it.id == tx.category_id },
                            onClick = {}, onLongClick = { pickFor = tx }, showDate = true, fullText = true
                        )
                    }
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            else if (state.endReached) Text(
                                "共 ${state.items.size} 笔, 合计 ¥${String.format(java.util.Locale.CHINA, "%,.2f", total)} (未扣退款)",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item { Box(modifier = Modifier.size(48.dp)) }
                }
            }
        }
    }

    pickFor?.let { tx ->
        CategoryPickerDialog(
            tx = tx, categories = state.categories, onDismiss = { pickFor = null },
            onConfirm = { c, sub, isTreat -> pickFor = null; onSetCategory(tx.id, c, sub, isTreat) },
            onDelete = { pickFor = null; onDeleteTransaction(tx.id) }
        )
    }
}
