package dev.claudiverse.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Claudiverse × Nord. Nord's Polar Night / Snow Storm neutrals, Frost as the
 * secondary, Aurora for status — with the web app's violet (#7C3AED / #A78BFA,
 * web/src/index.css --accent) as primary so the app reads as the same product.
 */
object Nord {
    // Polar Night
    val n0 = Color(0xFF2E3440); val n1 = Color(0xFF3B4252); val n2 = Color(0xFF434C5E); val n3 = Color(0xFF4C566A)
    // Snow Storm
    val n4 = Color(0xFFD8DEE9); val n5 = Color(0xFFE5E9F0); val n6 = Color(0xFFECEFF4)
    // Frost
    val n7 = Color(0xFF8FBCBB); val n8 = Color(0xFF88C0D0); val n9 = Color(0xFF81A1C1); val n10 = Color(0xFF5E81AC)
    // Aurora
    val red = Color(0xFFBF616A); val orange = Color(0xFFD08770); val yellow = Color(0xFFEBCB8B)
    val green = Color(0xFFA3BE8C); val purple = Color(0xFFB48EAD)
}

object Brand {
    val violet = Color(0xFF7C3AED)       // web --accent (light)
    val violetLight = Color(0xFFA78BFA)  // web --accent (dark)
    val violetTint = Color(0xFFEDE9FE)   // web --accent-light
}

private val NordDark = darkColorScheme(
    primary = Brand.violetLight, onPrimary = Color(0xFF1E1533),
    primaryContainer = Color(0xFF463A6B), onPrimaryContainer = Brand.violetTint,
    secondary = Nord.n8, onSecondary = Nord.n0,
    secondaryContainer = Color(0xFF463A6B), onSecondaryContainer = Brand.violetTint,
    tertiary = Nord.purple, onTertiary = Nord.n0,
    tertiaryContainer = Color(0xFF4E4256), onTertiaryContainer = Color(0xFFF1E3EE),
    error = Nord.red, onError = Nord.n0,
    errorContainer = Color(0xFF4A3238), onErrorContainer = Color(0xFFF2D4D7),
    background = Nord.n0, onBackground = Nord.n6,
    surface = Nord.n0, onSurface = Nord.n6,
    surfaceVariant = Nord.n1, onSurfaceVariant = Nord.n4,
    surfaceContainerLowest = Color(0xFF272C36), surfaceContainerLow = Color(0xFF323845),
    surfaceContainer = Nord.n1, surfaceContainerHigh = Nord.n2, surfaceContainerHighest = Nord.n3,
    surfaceBright = Nord.n2, surfaceDim = Color(0xFF272C36),
    outline = Color(0xFF7B8699), outlineVariant = Nord.n2,
    inverseSurface = Nord.n6, inverseOnSurface = Nord.n0, inversePrimary = Brand.violet,
    scrim = Color.Black,
)

private val NordLight = lightColorScheme(
    primary = Brand.violet, onPrimary = Color.White,
    primaryContainer = Brand.violetTint, onPrimaryContainer = Color(0xFF3B1A78),
    secondary = Nord.n10, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3DCFB), onSecondaryContainer = Color(0xFF3B1A78),
    tertiary = Color(0xFF8A5F82), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1E3EE), onTertiaryContainer = Color(0xFF3E2439),
    error = Color(0xFFB04F59), onError = Color.White,
    errorContainer = Color(0xFFF5DADD), onErrorContainer = Color(0xFF5C1F26),
    background = Nord.n6, onBackground = Nord.n0,
    surface = Nord.n6, onSurface = Nord.n0,
    surfaceVariant = Nord.n5, onSurfaceVariant = Nord.n3,
    surfaceContainerLowest = Color(0xFFF8F9FB), surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Nord.n5, surfaceContainerHigh = Color(0xFFDFE4EC), surfaceContainerHighest = Nord.n4,
    surfaceBright = Color(0xFFF8F9FB), surfaceDim = Nord.n4,
    outline = Color(0xFF7B8699), outlineVariant = Nord.n4,
    inverseSurface = Nord.n0, inverseOnSurface = Nord.n6, inversePrimary = Brand.violetLight,
    scrim = Color.Black,
)

/** Replaces the dynamic-colour theme: the brand palette is used on every API level. */
@Composable
fun ClaudiverseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) NordDark else NordLight, content = content)
}

/** Aurora. Pill text is [onStatus] (Nord0), not black. */
object StatusColors {
    val good = Nord.green
    val busy = Nord.yellow
    val bad = Nord.red
    val muted = Color(0xFF96A0B2)
    val info = Nord.n8
    /** A seat asking for the operator: Aurora orange, apart from every activity colour. */
    val attention = Nord.orange
    val onStatus = Nord.n0
}
