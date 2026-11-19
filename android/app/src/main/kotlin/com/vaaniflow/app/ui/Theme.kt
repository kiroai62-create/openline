package com.vaaniflow.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.R

/** Brand: Vaani Indigo #5B47FB and Flow Teal #14C8B0. */
object Brand {
    val Indigo = Color(0xFF5B47FB)
    val Blue = Color(0xFF3E7BF6)
    val Teal = Color(0xFF14C8B0)
    val Green = Color(0xFF12A150)
    val Red = Color(0xFFE5484D)
    val Amber = Color(0xFFC77700)
    val Night = Color(0xFF0C0B24)
    val gradient = Brush.linearGradient(listOf(Indigo, Blue, Teal))
}

private val Sans = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_medium, FontWeight.Medium),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
    Font(R.font.public_sans_semibold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Brand.Indigo, onPrimary = Color.White, primaryContainer = Color(0xFFE9E6FF), onPrimaryContainer = Color(0xFF1E1470),
    secondary = Brand.Teal, onSecondary = Color.White, secondaryContainer = Color(0xFFD8F7F2), onSecondaryContainer = Color(0xFF00382F),
    tertiary = Brand.Blue,
    background = Color(0xFFF7F7FC), onBackground = Color(0xFF0F1222),
    surface = Color(0xFFF7F7FC), onSurface = Color(0xFF0F1222),
    surfaceVariant = Color(0xFFEDEDF6), onSurfaceVariant = Color(0xFF5F6680),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F2FA), surfaceContainer = Color(0xFFEDEDF6),
    surfaceContainerHigh = Color(0xFFE7E7F2), surfaceContainerHighest = Color(0xFFE1E1EE),
    outline = Color(0xFFD5D7E3), outlineVariant = Color(0xFFE6E8F0), error = Brand.Red,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB3A9FF), onPrimary = Color(0xFF22168A), primaryContainer = Color(0xFF3A2DB8), onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = Color(0xFF5FE3CF), onSecondary = Color(0xFF00382F), secondaryContainer = Color(0xFF0F4F46), onSecondaryContainer = Color(0xFFD8F7F2),
    tertiary = Color(0xFF8AB0FF),
    background = Color(0xFF0E0F1A), onBackground = Color(0xFFECEEF8),
    surface = Color(0xFF0E0F1A), onSurface = Color(0xFFECEEF8),
    surfaceVariant = Color(0xFF22253A), onSurfaceVariant = Color(0xFF9BA1BC),
    surfaceContainerLowest = Color(0xFF0A0B14), surfaceContainerLow = Color(0xFF151728), surfaceContainer = Color(0xFF1A1C2F),
    surfaceContainerHigh = Color(0xFF222538), surfaceContainerHighest = Color(0xFF2B2E43),
    outline = Color(0xFF3A3E58), outlineVariant = Color(0xFF262940), error = Color(0xFFFF6B6F),
)

private val Type = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = Sans)
    Typography(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans().copy(fontWeight = FontWeight.SemiBold), headlineMedium = t.headlineMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.sans().copy(fontWeight = FontWeight.SemiBold), titleMedium = t.titleMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans().copy(fontWeight = FontWeight.SemiBold), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans().copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun VaaniTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
