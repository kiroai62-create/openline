package com.vaaniflow.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.R

/**
 * Brand: Vaani Indigo #5B47FB and Flow Teal #14C8B0, used as accents on a quiet neutral base
 * (near-black ink, hairline borders, soft shadows) so the app reads like a premium product.
 */
object Brand {
    val Indigo = Color(0xFF5B47FB)
    val IndigoSoft = Color(0xFF8B7CFF)
    val Blue = Color(0xFF3E7BF6)
    val Teal = Color(0xFF14C8B0)
    val Green = Color(0xFF16A34A)
    val Red = Color(0xFFE5484D)
    val Amber = Color(0xFFD97706)
    val Gold = Color(0xFFE0A526)

    /** Deep ink used for hero surfaces and the call screen. */
    val Ink950 = Color(0xFF07060F)
    val Ink900 = Color(0xFF0E0C1F)
    val Ink800 = Color(0xFF1A1636)
    val Night = Ink950

    val gradient = Brush.linearGradient(listOf(Indigo, Blue, Teal))
    val heroGradient = Brush.linearGradient(listOf(Ink950, Ink900, Color(0xFF1C1748)))
}

/** Extra tokens Material doesn't have: hairline borders, muted text and status colours. */
@Immutable
data class VaaniTokens(
    val hairline: Color,
    val hairlineStrong: Color,
    val muted: Color,
    val subtle: Color,
    val card: Color,
    val cardRaised: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val accent: Color,
    val dark: Boolean,
)

private val LightTokens = VaaniTokens(
    hairline = Color(0xFFE7E7EC), hairlineStrong = Color(0xFFD4D4DC), muted = Color(0xFF6B6B7B), subtle = Color(0xFF9A9AA8),
    card = Color.White, cardRaised = Color(0xFFF6F6F9),
    success = Color(0xFF15803D), warning = Color(0xFFB45309), danger = Color(0xFFDC2626), accent = Brand.Indigo, dark = false,
)

private val DarkTokens = VaaniTokens(
    hairline = Color(0xFF1F1F27), hairlineStrong = Color(0xFF2E2E39), muted = Color(0xFF9B9BAB), subtle = Color(0xFF6E6E7E),
    card = Color(0xFF111117), cardRaised = Color(0xFF17171F),
    success = Color(0xFF34D399), warning = Color(0xFFFBBF24), danger = Color(0xFFF87171), accent = Brand.IndigoSoft, dark = true,
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

/** Shortcut: `Vaani.tokens.hairline`. */
object Vaani {
    val tokens: VaaniTokens
        @Composable get() = LocalTokens.current
}

private val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// Primary is ink (light) / white (dark), like dub.co's buttons; indigo is the accent.
private val Light = lightColorScheme(
    primary = Color(0xFF0B0B12), onPrimary = Color.White, primaryContainer = Color(0xFFEEEBFF), onPrimaryContainer = Color(0xFF2A1F8F),
    secondary = Brand.Indigo, onSecondary = Color.White, secondaryContainer = Color(0xFFEEEBFF), onSecondaryContainer = Color(0xFF2A1F8F),
    tertiary = Brand.Teal,
    background = Color(0xFFFAFAFB), onBackground = Color(0xFF0B0B12),
    surface = Color(0xFFFAFAFB), onSurface = Color(0xFF0B0B12),
    surfaceVariant = Color(0xFFF1F1F4), onSurfaceVariant = Color(0xFF6B6B7B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F6F9), surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFECECF1), surfaceContainerHighest = Color(0xFFE5E5EB),
    outline = Color(0xFFD4D4DC), outlineVariant = Color(0xFFE7E7EC), error = Color(0xFFDC2626),
    inverseSurface = Color(0xFF0B0B12), inverseOnSurface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF0B0B12), primaryContainer = Color(0xFF221C52), onPrimaryContainer = Color(0xFFDCD6FF),
    secondary = Brand.IndigoSoft, onSecondary = Color(0xFF120D3A), secondaryContainer = Color(0xFF221C52), onSecondaryContainer = Color(0xFFDCD6FF),
    tertiary = Color(0xFF5FE3CF),
    background = Color(0xFF09090D), onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF09090D), onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF17171F), onSurfaceVariant = Color(0xFF9B9BAB),
    surfaceContainerLowest = Color(0xFF111117), surfaceContainerLow = Color(0xFF111117), surfaceContainer = Color(0xFF15151C),
    surfaceContainerHigh = Color(0xFF1B1B23), surfaceContainerHighest = Color(0xFF23232D),
    outline = Color(0xFF2E2E39), outlineVariant = Color(0xFF1F1F27), error = Color(0xFFF87171),
    inverseSurface = Color(0xFFF5F5F7), inverseOnSurface = Color(0xFF0B0B12),
)

private val Type = Typography().let { t ->
    fun TextStyle.sans(weight: FontWeight? = null, tracking: Float? = null) =
        copy(fontFamily = Sans, fontWeight = weight ?: fontWeight, letterSpacing = tracking?.sp ?: letterSpacing)
    Typography(
        displayLarge = t.displayLarge.sans(FontWeight.SemiBold, -1.5f), displayMedium = t.displayMedium.sans(FontWeight.SemiBold, -1.2f),
        displaySmall = t.displaySmall.sans(FontWeight.SemiBold, -0.9f),
        headlineLarge = t.headlineLarge.sans(FontWeight.SemiBold, -0.8f), headlineMedium = t.headlineMedium.sans(FontWeight.SemiBold, -0.6f),
        headlineSmall = t.headlineSmall.sans(FontWeight.SemiBold, -0.45f),
        titleLarge = t.titleLarge.sans(FontWeight.SemiBold, -0.35f), titleMedium = t.titleMedium.sans(FontWeight.SemiBold, -0.2f),
        titleSmall = t.titleSmall.sans(FontWeight.SemiBold, -0.1f),
        bodyLarge = t.bodyLarge.sans(tracking = -0.1f), bodyMedium = t.bodyMedium.sans(tracking = -0.05f), bodySmall = t.bodySmall.sans(tracking = 0f),
        labelLarge = t.labelLarge.sans(FontWeight.SemiBold, -0.05f), labelMedium = t.labelMedium.sans(FontWeight.Medium, 0f),
        labelSmall = t.labelSmall.sans(FontWeight.SemiBold, 1.1f),
    )
}

private val VaaniShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun VaaniTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = VaaniShapes, content = content)
    }
}
