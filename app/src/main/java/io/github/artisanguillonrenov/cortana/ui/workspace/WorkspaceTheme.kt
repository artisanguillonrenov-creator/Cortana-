package io.github.artisanguillonrenov.cortana.ui.workspace

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.ui.theme.Rc4WorkspaceColors

/**
 * Design tokens of the Chat Workspace (doc 16): every surface, radius and spacing comes from here,
 * never from a color written in a component. Status is always icon + text, never color alone.
 */
@Immutable
data class WorkspaceTokens(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val composer: Color,
    val activity: Color,
    val onActivity: Color,
    val warning: Color,
    val onWarning: Color,
    val critical: Color,
    val onCritical: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val code: Color,
    val onCode: Color,
    val border: Color,
    val muted: Color,
    val link: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val radiusSmall: Dp = 8.dp,
    val radiusCard: Dp = 14.dp,
    val radiusComposer: Dp = 22.dp,
    val radiusModal: Dp = 20.dp,
    /** Base spacing of the 4/8 dp grid, scaled by the density setting. */
    val gap: Dp = 8.dp,
    /** Reading width of an answer on large screens (doc 16 §16.5). */
    val readingWidth: Dp = 760.dp,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
) {
    /** Animation duration (120–220 ms, doc 16 §16.6), zero when the owner reduces motion. */
    fun motion(ms: Int = 180): Int = if (reduceMotion) 0 else ms
}

val LocalWorkspace = staticCompositionLocalOf { tokensFor(lightColorScheme(), false, ChatPrefs()) }




internal fun tokensFor(scheme: ColorScheme, contrast: Boolean, prefs: ChatPrefs): WorkspaceTokens {
    val dark = scheme.background.luminance() < 0.4f
    val gap = when (prefs.density) { "compact" -> 6.dp; "large" -> 12.dp; else -> 8.dp }
    return WorkspaceTokens(
        background = scheme.background,
        surface = scheme.surface,
        elevated = scheme.surfaceContainerHigh,
        composer = if (contrast) scheme.surface else scheme.surfaceContainerHighest,
        activity = if (contrast) scheme.secondaryContainer else scheme.tertiaryContainer,
        onActivity = if (contrast) scheme.onSecondaryContainer else scheme.onTertiaryContainer,
        warning = if (dark) Rc4WorkspaceColors.warningDark else Rc4WorkspaceColors.warningLight,
        onWarning = if (dark) Rc4WorkspaceColors.onWarningDark else Rc4WorkspaceColors.onWarningLight,
        critical = scheme.errorContainer,
        onCritical = scheme.onErrorContainer,
        userBubble = if (contrast) scheme.surface else scheme.secondaryContainer,
        onUserBubble = if (contrast) scheme.onSurface else scheme.onSecondaryContainer,
        code = if (dark) Rc4WorkspaceColors.codeDark else Rc4WorkspaceColors.codeLight,
        onCode = if (dark) Rc4WorkspaceColors.onCodeDark else Rc4WorkspaceColors.onCodeLight,
        border = if (contrast) scheme.onSurface else scheme.outlineVariant,
        muted = scheme.onSurfaceVariant,
        link = scheme.primary,
        diffAdded = if (dark) Rc4WorkspaceColors.diffAddedDark else Rc4WorkspaceColors.diffAddedLight,
        diffRemoved = if (dark) Rc4WorkspaceColors.diffRemovedDark else Rc4WorkspaceColors.diffRemovedLight,
        gap = gap,
        readingWidth = if (prefs.density == "large") 820.dp else 760.dp,
        reduceMotion = prefs.reduceMotion,
        highContrast = contrast,
    )
}

/**
 * Applies the owner's Workspace theme (system, light, dark, high contrast) and density on top of the
 * app theme. "system" keeps the app's colors (dynamic on Android 12+).
 */
@Composable
fun WorkspaceTheme(prefs: ChatPrefs, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme
    val scheme = when (prefs.theme) {
        "light" -> Rc4WorkspaceColors.light
        "dark" -> Rc4WorkspaceColors.dark
        "contrast" -> if (systemDark) Rc4WorkspaceColors.highContrastDark else Rc4WorkspaceColors.highContrastLight
        else -> base
    }
    val contrast = prefs.theme == "contrast"
    val tokens = remember(scheme, prefs.density, prefs.reduceMotion, contrast) { tokensFor(scheme, contrast, prefs) }
    val density = LocalDensity.current
    val scaled = if (prefs.density == "large") Density(density.density, density.fontScale * 1.12f) else density
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalWorkspace provides tokens, LocalDensity provides scaled, content = content)
    }
}
