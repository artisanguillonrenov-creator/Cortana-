package io.github.artisanguillonrenov.cortana.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/**
 * The "Cortana Workspace" design for the whole app (design handoff, spec 16). It is dark by design;
 * the owner's "Contraste élevé" keeps the same layout with stronger secondary texts and borders.
 * Screens not redrawn by the design take their colors from the Material scheme built on the tokens.
 */
@Composable
fun CortanaTheme(
    highContrast: Boolean = false,
    reduceMotion: Boolean = false,
    devMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (highContrast) CortanaHighContrast else CortanaDark
    val ctx = LocalContext.current
    // The system's "Supprimer les animations" counts as the owner's choice too.
    val systemNoMotion = remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val scheme = remember(palette) { schemeOf(palette) }
    MaterialTheme(colorScheme = scheme, typography = CortanaTypography) {
        CompositionLocalProvider(
            LocalCortanaPalette provides palette,
            LocalReduceMotion provides (reduceMotion || systemNoMotion),
            LocalDevMode provides devMode,
            content = content,
        )
    }
}

private fun schemeOf(p: CortanaPalette) = darkColorScheme(
    primary = p.accent, onPrimary = p.onAccent,
    primaryContainer = p.selectedSegment, onPrimaryContainer = p.textPrimary,
    inversePrimary = p.accentBottom,
    secondary = p.accentLink, onSecondary = p.background,
    secondaryContainer = p.tile, onSecondaryContainer = p.textBody,
    tertiary = p.purpleText, onTertiary = p.background,
    tertiaryContainer = p.badge, onTertiaryContainer = p.purpleTag,
    background = p.background, onBackground = p.textBody,
    surface = p.background, onSurface = p.textBody,
    surfaceVariant = p.control, onSurfaceVariant = p.textSecondary,
    surfaceTint = p.accent,
    inverseSurface = p.textPrimary, inverseOnSurface = p.background,
    error = p.dangerText, onError = p.background,
    errorContainer = p.badge, onErrorContainer = p.dangerTextSoft,
    outline = p.controlBorder, outlineVariant = p.divider,
    scrim = p.scrim,
    surfaceBright = p.controlPressed, surfaceDim = p.background,
    surfaceContainerLowest = p.sunken, surfaceContainerLow = p.surface,
    surfaceContainer = p.control, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.input,
)

/** Material's scale with the design's family (Geist); sizes stay Material's for screens the design does not draw. */
private val CortanaTypography: Typography = Typography().run {
    fun TextStyle.g() = copy(fontFamily = CortanaFonts.Sans)
    Typography(
        displayLarge = displayLarge.g(), displayMedium = displayMedium.g(), displaySmall = displaySmall.g(),
        headlineLarge = headlineLarge.g(), headlineMedium = headlineMedium.g(), headlineSmall = headlineSmall.g(),
        titleLarge = titleLarge.g(), titleMedium = titleMedium.g(), titleSmall = titleSmall.g(),
        bodyLarge = bodyLarge.g(), bodyMedium = bodyMedium.g(), bodySmall = bodySmall.g(),
        labelLarge = labelLarge.g(), labelMedium = labelMedium.g(), labelSmall = labelSmall.g(),
    )
}
