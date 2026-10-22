package de.marcusleg.alertfetcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import de.marcusleg.alertfetcher.model.Severity

// Colors from marcusleg.de (Monokai). The schemes are generated with Google's material-color-utilities
// (the algorithm behind Material Theme Builder and Android's wallpaper colors), variant TONAL_SPOT, from:
//   primary   #AE81FF  Monokai purple, the site's link and active-tab color
//   tertiary  #F92672  Monokai pink, the wordmark and the icon's bell
//   neutral   HCT hue 128, chroma 2.5 (neutral variant: 5), matching the site's near-gray, slightly olive backgrounds
private val LightColors = lightColorScheme(
    primary = Color(0xFF7143BE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDCFF),
    onPrimaryContainer = Color(0xFF5827A5),
    inversePrimary = Color(0xFFD4BBFF),
    secondary = Color(0xFF635B70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADEF7),
    onSecondaryContainer = Color(0xFF4B4358),
    tertiary = Color(0xFFBC0050),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9DF),
    onTertiaryContainer = Color(0xFF90003C),
    background = Color(0xFFFCF9F4),
    onBackground = Color(0xFF1B1C19),
    surface = Color(0xFFFCF9F4),
    onSurface = Color(0xFF1B1C19),
    surfaceVariant = Color(0xFFE3E3D9),
    onSurfaceVariant = Color(0xFF464740),
    inverseSurface = Color(0xFF30312D),
    inverseOnSurface = Color(0xFFF3F0EB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF777870),
    outlineVariant = Color(0xFFC7C7BE),
    surfaceBright = Color(0xFFFCF9F4),
    surfaceDim = Color(0xFFDCDAD5),
    surfaceContainer = Color(0xFFF0EEE9),
    surfaceContainerHigh = Color(0xFFEAE8E3),
    surfaceContainerHighest = Color(0xFFE4E2DD),
    surfaceContainerLow = Color(0xFFF6F3EE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF40008C),
    primaryContainer = Color(0xFF5827A5),
    onPrimaryContainer = Color(0xFFEBDCFF),
    inversePrimary = Color(0xFF7143BE),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFEADEF7),
    tertiary = Color(0xFFFFB1C0),
    onTertiary = Color(0xFF660028),
    tertiaryContainer = Color(0xFF90003C),
    onTertiaryContainer = Color(0xFFFFD9DF),
    background = Color(0xFF131411),
    onBackground = Color(0xFFE4E2DD),
    surface = Color(0xFF131411),
    onSurface = Color(0xFFE4E2DD),
    surfaceVariant = Color(0xFF464740),
    onSurfaceVariant = Color(0xFFC7C7BE),
    inverseSurface = Color(0xFFE4E2DD),
    inverseOnSurface = Color(0xFF30312D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF919189),
    outlineVariant = Color(0xFF464740),
    surfaceBright = Color(0xFF393936),
    surfaceDim = Color(0xFF131411),
    surfaceContainer = Color(0xFF1F201D),
    surfaceContainerHigh = Color(0xFF2A2A27),
    surfaceContainerHighest = Color(0xFF353532),
    surfaceContainerLow = Color(0xFF1B1C19),
    surfaceContainerLowest = Color(0xFF0E0E0C),
)

@Immutable
data class StatusColors(
    val critical: Color,
    val warning: Color,
    val info: Color,
    val resolved: Color,
) {
    fun forSeverity(severity: Severity) = when (severity) {
        Severity.CRITICAL -> critical
        Severity.WARNING -> warning
        Severity.INFO -> info
    }
}

// The site's accent colors: darker variants that stay readable on light backgrounds…
private val LightStatus = StatusColors(
    critical = Color(0xFFD9005E),
    warning = Color(0xFFA35D00),
    info = Color(0xFF00798A),
    resolved = Color(0xFF477210),
)

// …and the original Monokai colors on dark backgrounds.
private val DarkStatus = StatusColors(
    critical = Color(0xFFF92672),
    warning = Color(0xFFFD971F),
    info = Color(0xFF66D9EF),
    resolved = Color(0xFFA6E22E),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The site's header rule: Monokai purple, orange and pink, the same in light and dark mode. */
val BrandGradient = listOf(Color(0xFFAE81FF), Color(0xFFFD971F), Color(0xFFF92672))

val MonoFamily = FontFamily.Monospace

@Composable
fun AlertFetcherTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography(),
            content = content,
        )
    }
}
