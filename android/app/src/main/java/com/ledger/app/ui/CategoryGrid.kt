package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ledger.app.data.ApiClient
import com.ledger.app.data.CategoryDto
import com.ledger.app.data.CategoryOrderRequest
import kotlinx.coroutines.launch

/** Whoever changes the category order tells the screens that keep their own copy of the list. */
object CategoryHub {
    var onChanged: (() -> Unit)? = null
}

/** Categories as an even grid (icon over name). Biggest spenders first until the user moves things: press and hold a
 *  tile, drag it where it belongs, let go - the order is remembered for the whole ledger. */
@Composable
fun CategoryGrid(
    items: List<CategoryDto>,
    selectedId: Int?,
    txType: String,
    onPick: (Int) -> Unit,
    columns: Int = 4
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember(items) { mutableStateOf(items) }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cellW = maxWidth / columns
            val cellH = 74.dp
            val rows = (order.size + columns - 1) / columns
            val cwPx = with(density) { cellW.toPx() }
            val chPx = with(density) { cellH.toPx() }
            Box(modifier = Modifier.fillMaxWidth().height(cellH * rows)) {
                order.forEachIndexed { i, c ->
                    val dragging = dragId == c.id
                    Box(
                        modifier = Modifier
                            .offset(x = cellW * (i % columns), y = cellH * (i / columns))
                            .size(cellW, cellH)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    translationX = drag.x; translationY = drag.y; scaleX = 1.1f; scaleY = 1.1f; alpha = 0.92f
                                }
                            }
                            .padding(3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (c.id == selectedId) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onPick(c.id) }
                            .pointerInput(c.id, order) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragId = c.id; drag = Offset.Zero },
                                    onDrag = { change, amount -> change.consume(); drag += amount },
                                    onDragCancel = { dragId = null; drag = Offset.Zero },
                                    onDragEnd = {
                                        val from = order.indexOfFirst { it.id == c.id }
                                        val cx = (from % columns) * cwPx + cwPx / 2 + drag.x
                                        val cy = (from / columns) * chPx + chPx / 2 + drag.y
                                        val col = (cx / cwPx).toInt().coerceIn(0, columns - 1)
                                        val row = (cy / chPx).toInt().coerceIn(0, rows - 1)
                                        val to = (row * columns + col).coerceIn(0, order.size - 1)
                                        dragId = null; drag = Offset.Zero
                                        if (to != from) {
                                            val next = order.toMutableList().also { it.add(to, it.removeAt(from)) }
                                            order = next
                                            scope.launch {
                                                try {
                                                    ApiClient.create(context).setCategoryOrder(CategoryOrderRequest(txType, next.map { it.id }))
                                                    CategoryHub.onChanged?.invoke()
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(c.icon ?: "•", style = MaterialTheme.typography.titleLarge)
                            Text(
                                c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
