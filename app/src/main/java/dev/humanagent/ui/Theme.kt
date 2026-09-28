package dev.humanagent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Palette used by HumanPhone: a deep navy canvas, a blue accent and a mint accent.
 * Kept private on purpose - everything outside this file reads colours through [MaterialTheme].
 */
private val HumanPhoneDarkColors = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color(0xFF04101F),
    primaryContainer = Color(0xFF16325F),
    onPrimaryContainer = Color(0xFFD7E5FF),
    inversePrimary = Color(0xFF1F5FBF),
    secondary = Color(0xFF8BE9C0),
    onSecondary = Color(0xFF032018),
    secondaryContainer = Color(0xFF11382C),
    onSecondaryContainer = Color(0xFFC8F6E2),
    tertiary = Color(0xFFB9A6FF),
    onTertiary = Color(0xFF130A33),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF0F1420),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1A2130),
    onSurfaceVariant = Color(0xFFA8B2C4),
    surfaceTint = Color(0xFF4C8DFF),
    inverseSurface = Color(0xFFE6EAF2),
    inverseOnSurface = Color(0xFF1A2130),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505),
    errorContainer = Color(0xFF53201F),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF455065),
    outlineVariant = Color(0xFF2A3242),
    scrim = Color(0xFF000000),
)

private val HumanPhoneLightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E5FF),
    onPrimaryContainer = Color(0xFF04101F),
    secondary = Color(0xFF1C6E52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F6E2),
    onSecondaryContainer = Color(0xFF032018),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF11151E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151E),
    surfaceVariant = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF444C5C),
    outline = Color(0xFF747C8C),
    outlineVariant = Color(0xFFC3C9D6),
)

/**
 * Root theme for the app. The app ships a dark palette by default; [darkTheme] only exists so a
 * caller can preview the light counterpart.
 */
@Composable
fun HumanPhoneTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HumanPhoneDarkColors else HumanPhoneLightColors,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
