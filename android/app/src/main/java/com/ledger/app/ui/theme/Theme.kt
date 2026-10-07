package com.ledger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF6D4C41),
    background = Color(0xFFF7F6F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEAE4),
    error = Color(0xFFC62828)
)

val IncomeColor = Color(0xFF2E7D32)
val ExpenseColor = Color(0xFFC62828)
val CategoryBadgeColors = listOf(
    Color(0xFFFFE0B2), Color(0xFFC8E6C9), Color(0xFFB3E5FC),
    Color(0xFFF8BBD0), Color(0xFFD1C4E9), Color(0xFFFFF9C4)
)

private val LedgerShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, shapes = LedgerShapes, content = content)
}
