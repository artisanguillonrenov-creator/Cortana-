package io.github.artisanguillonrenov.cortana.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.R

/**
 * Tokens of the "Cortana Workspace" design (design_handoff_cortana_workspace/README.md and its
 * prototype). The only place of the app where a color is written: every component reads
 * [Cortana.colors] (spec 16.2). Conversion from the prototype: 1 CSS px = 1 dp, 1 font px = 1 sp.
 */
@Immutable
open class CortanaPalette {
    // Surfaces
    open val background: Color = Color(0xFF0A0F19)
    open val backgroundGlow: Color = Color(0xFF101A30)
    open val sidebarTop: Color = Color(0xFF0D1525)
    open val sidebarBottom: Color = Color(0xFF0A111D)
    open val sidebarBorder: Color = Color(0xFF152033)
    open val surface: Color = Color(0xFF0D1522)
    open val surfaceBorder: Color = Color(0xFF1B2536)
    open val planBorder: Color = Color(0xFF1C2638)
    open val control: Color = Color(0xFF0E1522)
    open val controlBorder: Color = Color(0xFF1F2A3C)
    open val controlHover: Color = Color(0xFF131D2E)
    open val controlHoverAlt: Color = Color(0xFF141E30)
    open val controlPressed: Color = Color(0xFF16213A)
    open val input: Color = Color(0xFF0F1726)
    open val focusBorder: Color = Color(0xFF2F6FD6)
    open val composer: Color = Color(0xFF0C1320)
    open val sunken: Color = Color(0xFF0A111D)
    open val console: Color = Color(0xFF09101A)
    open val consoleBorder: Color = Color(0xFF182130)
    open val codeHeader: Color = Color(0xFF0E1624)
    open val codeHeaderDivider: Color = Color(0xFF18212F)
    open val elevated: Color = Color(0xFF101828)
    open val elevatedBorder: Color = Color(0xFF243149)
    open val divider: Color = Color(0xFF172131)
    open val dividerAlt: Color = Color(0xFF1A2436)
    open val badge: Color = Color(0xFF172133)
    open val badgeBorder: Color = Color(0xFF222E42)
    open val badgeText: Color = Color(0xFFD4DDE9)
    open val tile: Color = Color(0xFF141E2F)
    open val tileBorder: Color = Color(0xFF223047)
    open val tileBorderAlt: Color = Color(0xFF243047)
    open val taskTileTop: Color = Color(0xFF1A2740)
    open val taskTileBottom: Color = Color(0xFF121B2C)
    open val selectedSegment: Color = Color(0xFF1F3050)
    open val devCard: Color = Color(0xFF0E1626)
    open val devCardBorder: Color = Color(0xFF1C2739)
    open val neutralButton: Color = Color(0xFF0F1828)
    open val neutralButtonBorder: Color = Color(0xFF1E2A3D)
    open val scrim: Color = Color(0x8C03060C)
    open val shadow: Color = Color(0xBF000000)
    open val drawerShadow: Color = Color(0x8C000000)
    open val placeholder: Color = Color(0xFF6F7D92)
    open val scrollbar: Color = Color(0xFF233046)
    // Controls (values of the prototype's elements)
    open val toggleOff: Color = Color(0xFF253044)
    open val toggleKnobOff: Color = Color(0xFF8D9BB0)
    open val headerButtonBorder: Color = Color(0xFF1E293B)
    open val headerButtonIcon: Color = Color(0xFFB9C5D5)
    open val pillIcon: Color = Color(0xFFC9D3E0)
    open val pillChevron: Color = Color(0xFFAAB6C6)
    open val pillSub: Color = Color(0xFF8F9DB2)
    open val kebab: Color = Color(0xFF8E9BB0)
    open val menuHover: Color = Color(0xFF172238)
    open val menuDivider: Color = Color(0xFF1D2839)
    open val chipBorder: Color = Color(0xFF1E293A)
    open val chipText: Color = Color(0xFFCFD8E4)
    open val chipIcon: Color = Color(0xFFA7B3C3)
    open val tabBorder: Color = Color(0xFF1D2839)
    open val tabText: Color = Color(0xFFBCC7D6)
    open val tabIcon: Color = Color(0xFF9AA7B9)
    open val secondaryButtonBorder: Color = Color(0xFF243149)
    open val linkButtonBorder: Color = Color(0xFF2A3A58)
    open val linkButtonHover: Color = Color(0xFF111C30)
    open val refuseBorder: Color = Color(0xFF2A3750)
    open val iconAction: Color = Color(0xFFB3BFCF)
    open val attachIcon: Color = Color(0xFFC3CDDB)
    open val codeChipText: Color = Color(0xFFC3CEDC)
    open val artifactChipBorder: Color = Color(0xFF1D2838)
    open val planBackground: Color = Color(0xFF0D1523)
    open val planIcon: Color = Color(0xFFA9BAD0)
    open val planKebab: Color = Color(0xFFA9B5C6)
    open val stepText: Color = Color(0xFFD3DBE6)
    open val metaText: Color = Color(0xFF9AA8BC)
    open val userText: Color = Color(0xFFEAF0F8)
    open val taskTileBorder: Color = Color(0xFF26334A)
    open val taskTileIcon: Color = Color(0xFFE9EEF6)
    open val workerIcon: Color = Color(0xFFDBE3EE)
    open val infoSubtitle: Color = Color(0xFFB9C4D3)
    open val infoCardHover: Color = Color(0xFF2B3A55)
    open val fileIcon: Color = Color(0xFF8B98AB)
    open val approvedText: Color = Color(0xFFBFF0D0)
    open val approvedMeta: Color = Color(0xFF6F8A7B)
    open val refusedText: Color = Color(0xFFFECACA)
    open val tagAndroidText: Color = Color(0xFF8FF0B4)
    open val navHover: Color = Color(0x0F8CA5D2)
    open val branchLine: Color = Color(0xFF2A3850)
    open val branchDot: Color = Color(0xFF56647C)
    open val auditTime: Color = Color(0xFF5D6A80)
    open val purpleLight: Color = Color(0xFFA78BFA)
    open val tempBannerText: Color = Color(0xFFE9E2FF)
    open val phoneFrame: Color = Color(0xFF263247)
    open val phoneScreen: Color = Color(0xFFF3F5FA)
    // Text
    open val textDefault: Color = Color(0xFFE8EEF8)
    open val textStrong: Color = Color(0xFFF2F6FB)
    open val textPrimary: Color = Color(0xFFEEF3FA)
    open val textBody: Color = Color(0xFFDFE7F2)
    open val textControl: Color = Color(0xFFE6EDF6)
    open val textSecondary: Color = Color(0xFFA3B0C2)
    open val textSecondaryAlt: Color = Color(0xFF93A1B5)
    open val textTertiary: Color = Color(0xFF8391A6)
    open val textMuted: Color = Color(0xFF7F8CA0)
    open val textFaint: Color = Color(0xFF6E7B8F)
    open val brandSubtitle: Color = Color(0xFFA1AFC3)
    open val devSubtitle: Color = Color(0xFF98A6BA)
    open val userTime: Color = Color(0xFF8B9AB0)
    open val navText: Color = Color(0xFFD3DCE8)
    open val navIcon: Color = Color(0xFFB7C3D3)
    open val navTextActive: Color = Color(0xFFFFFFFF)
    open val onAccent: Color = Color(0xFFFFFFFF)
    // Accent
    open val accent: Color = Color(0xFF2F8BFF)
    open val accentTop: Color = Color(0xFF3A8DFF)
    open val accentBottom: Color = Color(0xFF1F6BE8)
    open val accentText: Color = Color(0xFF4D9FFF)
    open val accentIcon: Color = Color(0xFF5AA7FF)
    open val accentLink: Color = Color(0xFF6CB6FF)
    open val accentLinkHover: Color = Color(0xFF9CCAFF)
    open val accentSoft: Color = Color(0xFF8CC2FF)
    open val accentBar: Color = Color(0xFF3D8FFF)
    open val spinnerArc: Color = Color(0xFFCFE6FF)
    open val logoCore: Color = Color(0xFF081224)
    open val logoEdge: Color = Color(0xFF0D2A55)
    open val logoRim: Color = Color(0xFF78BEFF)
    open val userBubbleTop: Color = Color(0xFF10254A)
    open val userBubbleBottom: Color = Color(0xFF0C1C3A)
    open val userBubbleBorder: Color = Color(0xFF214274)
    open val userBubbleHighlight: Color = Color(0xFF8CBEFF)
    open val avatarBackground: Color = Color(0xFFCFD9E8)
    open val avatarIcon: Color = Color(0xFF4E5F7A)
    open val codeTileStart: Color = Color(0xFF7F52FF)
    // Status (always with an icon and a text, spec 16.7)
    open val success: Color = Color(0xFF22C55E)
    open val successText: Color = Color(0xFF4ADE80)
    open val successLog: Color = Color(0xFF5EE08F)
    open val toolChipActiveIcon: Color = Color(0xFF34D27A)
    open val toolChipActiveText: Color = Color(0xFFE9F8EF)
    open val danger: Color = Color(0xFFE5463F)
    open val dangerDeep: Color = Color(0xFFC7302B)
    open val dangerBorder: Color = Color(0xFFFF8C80)
    open val dangerInner: Color = Color(0xFFD73A34)
    open val dangerText: Color = Color(0xFFF87171)
    open val dangerTextSoft: Color = Color(0xFFFCA5A5)
    open val warning: Color = Color(0xFFF5A524)
    open val warningText: Color = Color(0xFFFBBF24)
    open val warningLog: Color = Color(0xFFFCD34D)
    open val warningTitle: Color = Color(0xFFFDE8B8)
    open val warningSoft: Color = Color(0xFFFCD68A)
    open val purple: Color = Color(0xFF8B5CF6)
    open val purpleText: Color = Color(0xFFC4B5FD)
    open val purpleTag: Color = Color(0xFFD3C4FF)
    open val cyan: Color = Color(0xFF5FD4E8)
    open val cyanTint: Color = Color(0xFF22BEDC)
    open val amber: Color = Color(0xFFF5B544)
    open val stepUpcoming: Color = Color(0xFF3C4960)
    // Logs (hour, icon, text)
    open val logRunIcon: Color = Color(0xFF7D8AA0)
    open val logText: Color = Color(0xFFCDD6E2)
    open val logPlayText: Color = Color(0xFFA9CFFF)
    // Model initials (no provider logo)
    open val providerOpenAi: Color = Color(0xFFE6EBF2)
    open val providerAnthropic: Color = Color(0xFFF0A27A)
    open val providerGoogle: Color = Color(0xFF7FB2FF)
    open val providerLocal: Color = Color(0xFFB69CFF)
    // Syntax highlighting
    open val syntaxPlain: Color = Color(0xFFD3DBE7)
    open val syntaxKeyword: Color = Color(0xFFC792EA)
    open val syntaxType: Color = Color(0xFF6CB6FF)
    open val syntaxAnnotation: Color = Color(0xFFE8A95B)
    open val syntaxString: Color = Color(0xFFB9E08C)
    open val syntaxLiteral: Color = Color(0xFFF78C6C)
    open val syntaxLineNumber: Color = Color(0xFF46546B)
    // Gradients and tints of the design, built from the tokens above.
    val sidebarBrush get() = Brush.verticalGradient(listOf(sidebarTop, sidebarBottom))
    val accentButton get() = Brush.verticalGradient(listOf(accentTop, accentBottom))
    val dangerButton get() = Brush.verticalGradient(listOf(danger, dangerDeep))
    val progressBrush get() = Brush.horizontalGradient(listOf(accentBottom, accentText))
    val userBubble get() = Brush.verticalGradient(listOf(userBubbleTop, userBubbleBottom))
    val navActive get() = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.07f)))
    val approvalBg get() = Brush.verticalGradient(listOf(warning.copy(alpha = 0.10f), warning.copy(alpha = 0.03f)))
    val taskTile get() = Brush.verticalGradient(listOf(taskTileTop, taskTileBottom))
    val codeTile get() = Brush.linearGradient(listOf(codeTileStart, accentBar))
    val selectedBg get() = accent.copy(alpha = 0.16f)
    val selectedBorder get() = accentIcon.copy(alpha = 0.5f)
    val navActiveEdge get() = accentIcon.copy(alpha = 0.16f)
    val focusHalo get() = accent.copy(alpha = 0.15f)
    val successTint get() = success.copy(alpha = 0.12f)
    val successBorder get() = success.copy(alpha = 0.4f)
    val toolChipActiveBg get() = success.copy(alpha = 0.13f)
    val toolChipActiveBorder get() = toolChipActiveIcon.copy(alpha = 0.55f)
    val warningBorder get() = warning.copy(alpha = 0.45f)
    val purpleTint get() = purple.copy(alpha = 0.16f)
    val purpleBorder get() = purple.copy(alpha = 0.45f)
    val cyanBg get() = cyanTint.copy(alpha = 0.13f)
    val dangerGlow get() = danger.copy(alpha = 0.6f)
    val dangerEdge get() = dangerBorder.copy(alpha = 0.45f)
    val progressGlow get() = accentText.copy(alpha = 0.7f)
    val sendGlow get() = accent.copy(alpha = 0.8f)
}

/** The design's colors. */
object CortanaDarkPalette : CortanaPalette()

val CortanaDark: CortanaPalette = CortanaDarkPalette

/**
 * The owner's "Contraste élevé" setting on top of the design: secondary texts take the body color and
 * borders become visible, with the design's own values only (nothing invented).
 * (A class with properties, not constructor parameters: ~200 colors exceed the JVM's 255-slot signature limit.)
 */
object CortanaHighContrast : CortanaPalette() {
    override val textSecondary: Color = CortanaDarkPalette.textBody
    override val textSecondaryAlt: Color = CortanaDarkPalette.textBody
    override val textTertiary: Color = CortanaDarkPalette.textControl
    override val textMuted: Color = CortanaDarkPalette.textControl
    override val textFaint: Color = CortanaDarkPalette.textSecondary
    override val brandSubtitle: Color = CortanaDarkPalette.textBody
    override val devSubtitle: Color = CortanaDarkPalette.textBody
    override val userTime: Color = CortanaDarkPalette.textBody
    override val navIcon: Color = CortanaDarkPalette.navText
    override val placeholder: Color = CortanaDarkPalette.textSecondary
    override val surfaceBorder: Color = CortanaDarkPalette.textMuted
    override val controlBorder: Color = CortanaDarkPalette.textMuted
    override val planBorder: Color = CortanaDarkPalette.textMuted
    override val consoleBorder: Color = CortanaDarkPalette.textMuted
    override val elevatedBorder: Color = CortanaDarkPalette.textMuted
    override val divider: Color = CortanaDarkPalette.textFaint
    override val dividerAlt: Color = CortanaDarkPalette.textFaint
    override val tileBorder: Color = CortanaDarkPalette.textMuted
    override val syntaxLineNumber: Color = CortanaDarkPalette.textMuted
}

val LocalCortanaPalette = staticCompositionLocalOf { CortanaDark }

/** "Réduire les animations" (the app setting or the system's): every animation of the design stops (spec 16.6). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Developer mode (design: progressive disclosure, spec 16.9). */
val LocalDevMode = staticCompositionLocalOf { false }

object Cortana {
    val colors: CortanaPalette
        @Composable @ReadOnlyComposable get() = LocalCortanaPalette.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

object CortanaFonts {
    val Sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
    val Brand = FontFamily(Font(R.font.sora_semibold, FontWeight.SemiBold))
}

object CortanaType {
    private val S = CortanaFonts.Sans
    val Wordmark = TextStyle(fontFamily = CortanaFonts.Brand, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = 0.01.em)
    val ScreenTitle = TextStyle(fontFamily = S, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em)
    val PanelTitle = TextStyle(fontFamily = S, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    val SectionTitle = TextStyle(fontFamily = S, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp)
    val ItemTitle = TextStyle(fontFamily = S, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val Nav = TextStyle(fontFamily = S, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    val Body = TextStyle(fontFamily = S, fontSize = 15.sp, lineHeight = 25.sp)
    val Bubble = TextStyle(fontFamily = S, fontSize = 14.5.sp, lineHeight = 24.sp)
    val Step = TextStyle(fontFamily = S, fontSize = 14.5.sp)
    val Control = TextStyle(fontFamily = S, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val ControlSmall = TextStyle(fontFamily = S, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
    val Secondary = TextStyle(fontFamily = S, fontSize = 13.sp)
    val Caption = TextStyle(fontFamily = S, fontSize = 12.5.sp)
    val Overline = TextStyle(fontFamily = S, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.1.em) // + uppercase()
    val Numeric = TextStyle(fontFamily = S, fontSize = 14.sp, fontFeatureSettings = "tnum")
    val Code = TextStyle(fontFamily = CortanaFonts.Mono, fontSize = 13.sp, lineHeight = 19.4.sp)
    val Log = TextStyle(fontFamily = CortanaFonts.Mono, fontSize = 11.5.sp, lineHeight = 18.6.sp)
}

object CortanaShapes {
    val Xs = RoundedCornerShape(6.dp)      // tags, kbd
    val Sm = RoundedCornerShape(10.dp)     // chips, tabs, icon buttons
    val Md = RoundedCornerShape(12.dp)     // 44–48 dp buttons, pills, tiles
    val Field = RoundedCornerShape(13.dp)  // 52 dp field, Send button
    val Lg = RoundedCornerShape(14.dp)     // list cards, Plan card
    val Xl = RoundedCornerShape(16.dp)     // main cards, composer, large STOP
    val Bubble = RoundedCornerShape(18.dp) // user bubble
    val QueuedBubble = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 6.dp, bottomStart = 16.dp)
}

object CortanaDimens {
    val SidebarWidth = 288.dp
    val SidebarWidthCompact = 264.dp
    val ContextPanelWidth = 400.dp
    val ContextPanelWidthCompact = 360.dp
    val ContextPanelOverlayWidth = 424.dp
    val SecondaryColumnWidth = 388.dp
    val SettingsNavWidth = 256.dp
    val HeaderHeight = 56.dp
    val ControlHeight = 48.dp
    val NavItemHeight = 48.dp
    val ChipHeight = 38.dp
    val FilterChipHeight = 36.dp
    val TabHeight = 40.dp
    val InputHeight = 52.dp
    val ComposerHeight = 66.dp
    val StopLargeHeight = 84.dp
    val ActivityRailHeight = 54.dp
    val PlanStepHeight = 26.6.dp
    val AssistantMaxWidth = 640.dp
    val UserBubbleMaxWidth = 540.dp
    val QueuedBubbleMaxWidth = 460.dp
    val CodeCollapsedMaxHeight = 232.dp
    val CodeExpandedMaxHeight = 360.dp
    val LogoRing = 62.dp
    val AvatarRing = 44.dp
    val MinTouchTarget = 48.dp

    /** Adaptation to real sizes (README): 3 columns from 1200 dp, compact columns below 1400 dp. */
    val WideBreakpoint = 1400.dp
    val ThreeColumnBreakpoint = 1200.dp
}

object CortanaMotion {
    val Standard = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    const val Fast = 150          // chips, hover
    const val Toggle = 180        // switches
    const val SidebarCollapse = 220
    const val Overlay = 240       // drawer, portrait panel
    const val Scrim = 200
    const val Enter = 250         // entry: alpha 0→1, translationY 6dp→0
    const val Progress = 600
    const val SpinnerPeriod = 900
    const val ProgressIconPeriod = 1100
    const val DotsPeriod = 1200
    const val DotsStagger = 150
    const val PulsePeriod = 1800
    const val AvatarGlowPeriod = 2400
    const val LogoGlowPeriod = 3200
    const val PlanFlash = 1400
    const val CopiedFeedback = 1600
    const val CursorBlink = 1000
    const val WavePeriod = 900
    const val WaveStagger = 150
}

/**
 * Colors of the 2.0.0-rc4 workspace screen, still selectable in Réglages › Interface. They live here so
 * that no color is written in a component; the Cortana design itself uses [CortanaPalette].
 */
object Rc4WorkspaceColors {
    val highContrastLight = lightColorScheme(
        primary = Color(0xFF00315C), onPrimary = Color.White, primaryContainer = Color(0xFF00315C), onPrimaryContainer = Color.White,
        secondary = Color(0xFF1B1B1B), onSecondary = Color.White, secondaryContainer = Color(0xFFE0E0E0), onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF3B0764), onTertiary = Color.White, tertiaryContainer = Color(0xFFEDE0FF), onTertiaryContainer = Color.Black,
        background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
        surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color.Black, outline = Color.Black, error = Color(0xFF8C0009), onError = Color.White,
    )
    val light = lightColorScheme(
        primary = Color(0xFF0B5C8A), onPrimary = Color.White, primaryContainer = Color(0xFFCDE5FF), onPrimaryContainer = Color(0xFF001D32),
        secondary = Color(0xFF3A6A7E), tertiary = Color(0xFF6B5B95),
    )
    val dark = darkColorScheme(
        primary = Color(0xFF7FE3FF), onPrimary = Color(0xFF00344D), primaryContainer = Color(0xFF0B3D5C), onPrimaryContainer = Color(0xFFCDE5FF),
        secondary = Color(0xFFA3CDDF), tertiary = Color(0xFFCDBDFF),
    )
    val highContrastDark = highContrastLight.copy(
            background = Color.Black, onBackground = Color.White, surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color.White, outline = Color.White,
            primary = Color(0xFF9AD4FF), onPrimary = Color.Black, primaryContainer = Color(0xFF9AD4FF), onPrimaryContainer = Color.Black,
            secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color.White,
            surfaceContainerHigh = Color(0xFF111111), surfaceContainerHighest = Color(0xFF1A1A1A),
    )
    val warningDark = Color(0xFF4A3B00)
    val warningLight = Color(0xFFFFF1C2)
    val onWarningDark = Color(0xFFFFE08A)
    val onWarningLight = Color(0xFF3D2F00)
    val codeDark = Color(0xFF14171C)
    val codeLight = Color(0xFFF3F4F6)
    val onCodeDark = Color(0xFFE6E8EB)
    val onCodeLight = Color(0xFF1B1F24)
    val diffAddedDark = Color(0xFF12351F)
    val diffAddedLight = Color(0xFFE3F5E8)
    val diffRemovedDark = Color(0xFF3D1518)
    val diffRemovedLight = Color(0xFFFBE4E6)
}
