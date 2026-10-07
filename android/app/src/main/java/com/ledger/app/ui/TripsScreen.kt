package com.ledger.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.app.data.TripDto
import com.ledger.app.viewmodel.DetailRequest
import com.ledger.app.viewmodel.TripsUiState
import java.time.LocalDate

private fun md(d: String) = d.substring(5).replace('-', '月') + "日"

@Composable
fun TripsScreen(
    state: TripsUiState,
    onLoad: () -> Unit,
    onCreate: (name: String, start: String, end: String) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: (String, String) -> Unit,
    onOpenDetail: (DetailRequest) -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }
    var creating by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var deleting by remember { mutableStateOf<TripDto?>(null) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "出行和旅游单独算: 钱照样计入这个账期的支出, 但不会被当成你日常的花法去推算月底。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.suggestions.forEach { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("疑似一次出行", style = MaterialTheme.typography.labelMedium)
                    Text("${md(s.start_date)} – ${md(s.end_date)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${s.reason}, 约 ${money(s.estimate)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = {
                            onOpenDetail(DetailRequest("疑似出行 ${md(s.start_date)}–${md(s.end_date)}", null, null, s.start_date, s.end_date))
                        }) { Text("看账单") }
                        Button(onClick = { creating = Triple("", s.start_date, s.end_date) }) { Text("记为出行") }
                        TextButton(onClick = { onDismiss(s.start_date, s.end_date) }) { Text("不是出行") }
                    }
                }
            }
        }

        state.trips.forEach { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable { onOpenDetail(DetailRequest("✈️ ${t.name}", null, null, t.start_date, t.end_date, tripId = t.id)) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${md(t.start_date)} – ${md(t.end_date)} · ${t.count} 笔", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(t.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = com.ledger.app.ui.theme.ExpenseColor, maxLines = 1)
                    }
                    t.by_category.forEach {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${it.icon ?: ""} ${it.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(money(it.total), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = { deleting = t }) { Text("取消这次出行") }
                }
            }
        }

        if (state.trips.isEmpty() && state.suggestions.isEmpty()) {
            Text("还没有出行记录。去过哪里, 点下面新建一个, 那几天的油费、过路费、吃饭会自动归进来。",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        Button(
            onClick = { val d = LocalDate.now().toString(); creating = Triple("", d, d) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)
        ) { Text("新建一次出行") }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
    }

    creating?.let { (n0, s0, e0) ->
        var name by remember { mutableStateOf(n0) }
        var start by remember { mutableStateOf(s0) }
        var end by remember { mutableStateOf(e0) }
        val context = LocalContext.current
        fun pick(current: String, onPick: (String) -> Unit) {
            val d = LocalDate.parse(current)
            DatePickerDialog(context, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        AlertDialog(
            onDismissRequest = { creating = null },
            title = { Text("新建出行") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名字, 如 中秋去黄山") }, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pick(start) { start = it; if (end < it) end = it } }, modifier = Modifier.weight(1f)) { Text("从 ${md(start)}") }
                        OutlinedButton(onClick = { pick(end) { end = if (it < start) start else it } }, modifier = Modifier.weight(1f)) { Text("到 ${md(end)}") }
                    }
                    Text("这段日子里的油费、过路费、停车、吃饭、门票会自动归进来; 房租等固定支出不会。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { creating = null; onCreate(name.ifBlank { "出行" }, start, end) }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { creating = null }) { Text("取消") } }
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("取消「${t.name}」?") },
            text = { Text("只是取消这次出行的归类, 里面的每一笔账都还在, 会回到日常花销里。") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(t.id) }) { Text("取消出行") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } }
        )
    }
}
