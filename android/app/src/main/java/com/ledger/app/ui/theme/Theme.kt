package com.ledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** "system" (follow the phone) / "light" / "dark". Changing it recolours the app at once. */
object ThemeState {
    var mode by mutableStateOf("system")
}

private val LedgerLight = lightColorScheme(
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

private val LedgerDark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1F4D24),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFBCAAA4),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2B2B2B),
    tertiaryContainer = Color(0xFF4A3B1B),
    onTertiaryContainer = Color(0xFFFFE0B2),
    error = Color(0xFFEF9A9A)
)

// A softer jade/sage green for the "我的" (Settings) screen only - calmer across a whole screen of stacked
// buttons than the app's main saturated green, without touching how the rest of the app looks.
private val SettingsLight = lightColorScheme(
    primary = Color(0xFF3F8F6F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAF0E6),
    onPrimaryContainer = Color(0xFF0D4A36),
    secondary = Color(0xFF7A6657),
    background = Color(0xFFFAF9F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEEE9),
    error = Color(0xFFC14C4C)
)

private val SettingsDark = darkColorScheme(
    primary = Color(0xFF8FD4B5),
    onPrimary = Color(0xFF00392A),
    primaryContainer = Color(0xFF28503F),
    onPrimaryContainer = Color(0xFFD5F0E3),
    secondary = Color(0xFFC9B8A8),
    background = Color(0xFF151715),
    surface = Color(0xFF1F211F),
    surfaceVariant = Color(0xFF2C2F2C),
    tertiaryContainer = Color(0xFF4A3B1B),
    onTertiaryContainer = Color(0xFFFFE0B2),
    error = Color(0xFFE79999)
)

private val LocalDark = staticCompositionLocalOf { false }

val IncomeColor: Color @Composable get() = if (LocalDark.current) Color(0xFF66BB6A) else Color(0xFF2E7D32)
val ExpenseColor: Color @Composable get() = if (LocalDark.current) Color(0xFFEF5350) else Color(0xFFC62828)
val CategoryBadgeColors: List<Color>
    @Composable get() = if (LocalDark.current) listOf(
        Color(0xFF7A5A2E), Color(0xFF2F6B35), Color(0xFF2B5F78),
        Color(0xFF7D3A52), Color(0xFF55468A), Color(0xFF7A7430)
    ) else listOf(
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
    val dark = when (ThemeState.mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) LedgerDark else LedgerLight, shapes = LedgerShapes, content = content)
    }
}

/** Wraps just the "我的" screen in the softer palette - everywhere else keeps LedgerTheme's colors untouched.
 *  Nests inside LedgerTheme, so it only overrides colorScheme; dark/light mode still follows ThemeState. */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val dark = LocalDark.current
    MaterialTheme(colorScheme = if (dark) SettingsDark else SettingsLight, shapes = LedgerShapes, content = content)
}
