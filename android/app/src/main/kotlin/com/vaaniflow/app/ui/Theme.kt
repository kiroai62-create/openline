package com.vaaniflow.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app looks like the Google Phone app: Material You colours from the wallpaper (Android 12+),
 * the system font and Google's layouts. Brand colours are only used for the AI parts.
 */
object Brand {
    val Indigo = Color(0xFF5B47FB)
    val Blue = Color(0xFF3E7BF6)
    val Teal = Color(0xFF14C8B0)
    val Green = Color(0xFF1E8E3E)
    val Red = Color(0xFFDC362E)
    val Amber = Color(0xFFB06000)
    val Gold = Color(0xFFE0A526)
    val Night = Color(0xFF0E0E10)

    /** AI accent only (sparkle icon, AI call chip). */
    val gradient = Brush.linearGradient(listOf(Indigo, Blue, Teal))
}

/** Surface roles of the Google Phone app, derived from the colour scheme. */
@Immutable
data class VaaniTokens(
    val background: Color,
    val card: Color,
    val cardRaised: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val muted: Color,
    val subtle: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val callGreen: Color,
    val endRed: Color,
    val dark: Boolean,
)

val LocalTokens = staticCompositionLocalOf { tokensFor(GoogleLight, false) }

object Vaani {
    val tokens: VaaniTokens
        @Composable get() = LocalTokens.current
}

private fun tokensFor(cs: ColorScheme, dark: Boolean) = VaaniTokens(
    // Google Phone: tinted grey page, list rows on brighter segments.
    background = if (dark) cs.surface else cs.surfaceContainer,
    card = if (dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest,
    cardRaised = if (dark) cs.surfaceContainerHighest else cs.surfaceContainerHigh,
    hairline = cs.outlineVariant,
    hairlineStrong = cs.outline,
    muted = cs.onSurfaceVariant,
    subtle = cs.onSurfaceVariant.copy(alpha = 0.75f),
    accent = cs.primary,
    success = if (dark) Color(0xFF6DD58C) else Color(0xFF146C2E),
    warning = if (dark) Color(0xFFFFB95C) else Color(0xFF8C4A00),
    danger = cs.error,
    callGreen = if (dark) Color(0xFF6DD58C) else Color(0xFF1E8E3E),
    endRed = Color(0xFFDC362E),
    dark = dark,
)

// Google's baseline colours, used where the phone has no Material You palette (Android 10/11).
private val GoogleLight = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White, primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B), onSecondary = Color.White, secondaryContainer = Color(0xFFC2E7FF), onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF146C2E), onTertiary = Color.White, tertiaryContainer = Color(0xFFC4EED0), onTertiaryContainer = Color(0xFF072711),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1F1F), surfaceVariant = Color(0xFFE1E3E1), onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8FAFD), surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6), surfaceContainerHighest = Color(0xFFDDE3EA), surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5), error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val GoogleDark = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F), primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF), onSecondary = Color(0xFF003355), secondaryContainer = Color(0xFF004A77), onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C), onTertiary = Color(0xFF0A3818), tertiaryContainer = Color(0xFF0F5223), onTertiaryContainer = Color(0xFFC4EED0),
    background = Color(0xFF131314), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314), onSurface = Color(0xFFE3E3E3), surfaceVariant = Color(0xFF444746), onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF1B1B1B), surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C), surfaceContainerHighest = Color(0xFF333537), surfaceBright = Color(0xFF37393B),
    outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746), error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun VaaniTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> GoogleDark
        else -> GoogleLight
    }
    // Default typography = the phone's own font (Google Sans on Pixel, the maker's font elsewhere),
    // so the app matches the rest of the system.
    CompositionLocalProvider(LocalTokens provides tokensFor(scheme, dark)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
