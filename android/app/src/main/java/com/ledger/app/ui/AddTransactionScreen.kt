package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.TransactionDto

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    categories: List<CategoryDto>,
    existing: TransactionDto? = null,
    onSave: (txType: String, amount: String, categoryId: Int?, note: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var txType by remember { mutableStateOf(existing?.tx_type ?: "expense") }
    var amount by remember { mutableStateOf(existing?.amount ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var selectedCategory by remember {
        mutableStateOf(categories.find { it.id == existing?.category_id })
    }

    val filteredCategories = categories.filter { it.tx_type == txType }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            if (existing != null) "✍️ 编辑这一笔" else "✍️ 记一笔",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // the list cuts long names short: here is the whole record, in full and selectable
        if (existing != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val rows = listOfNotNull(
                            existing.merchant?.takeIf { it.isNotBlank() }?.let { "商户" to it },
                            existing.note?.takeIf { it.isNotBlank() && it != existing.merchant }?.let { "说明" to it },
                            "时间" to existing.occurred_at.replace('T', ' ').take(19),
                            existing.account_hint?.takeIf { it.isNotBlank() }?.let { "付款" to it },
                            categories.find { it.id == existing.category_id }?.let { "分类" to (it.name + (existing.sub?.let { s -> " · $s" } ?: "")) },
                            existing.raw_text?.takeIf { it.isNotBlank() && it != existing.note && it != existing.merchant }?.let { "原文" to it }
                        )
                        rows.forEachIndexed { i, (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row {
                    FilterChip(
                        selected = txType == "expense",
                        onClick = { txType = "expense"; selectedCategory = null },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = txType == "income",
                        onClick = { txType = "income"; selectedCategory = null },
                        label = { Text("收入") },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额") },
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.let { "${it.icon ?: ""} ${it.name}" } ?: "选择分类",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分类") },
                        shape = MaterialTheme.shapes.small,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        filteredCategories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text("${category.icon ?: ""} ${category.name}") },
                                onClick = { selectedCategory = category; expanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注 (比如: 换机油/续保险等)") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )

                Button(
                    onClick = { onSave(txType, amount, selectedCategory?.id, note.ifBlank { null }) },
                    enabled = amount.toDoubleOrNull() != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                ) {
                    Text(if (existing != null) "更新" else "保存")
                }

                if (existing != null && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        Text("删除这一笔")
                    }
                }
            }
        }
    }
}
