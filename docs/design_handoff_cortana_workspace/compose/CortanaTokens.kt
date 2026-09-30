package com.cortana.ui.theme // TODO: adapter au package réel du projet

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Tokens du design « Cortana Workspace » (voir README.md).
 * Règle : aucun composant ne code une couleur/taille en dur, tout passe par ces objets.
 * Conversion depuis le prototype : 1 px CSS = 1 dp, 1 px de police = 1 sp.
 */
object CortanaColors {
    // Surfaces
    val Background = Color(0xFF0A0F19)
    val BackgroundGlow = Color(0xFF101A30)
    val SidebarTop = Color(0xFF0D1525)
    val SidebarBottom = Color(0xFF0A111D)
    val SidebarBorder = Color(0xFF152033)
    val Surface = Color(0xFF0D1522)
    val SurfaceBorder = Color(0xFF1B2536)
    val Control = Color(0xFF0E1522)
    val ControlBorder = Color(0xFF1F2A3C)
    val ControlHover = Color(0xFF16213A)
    val Input = Color(0xFF0F1726)
    val FocusBorder = Color(0xFF2F6FD6)
    val Composer = Color(0xFF0C1320)
    val Sunken = Color(0xFF0A111D)
    val Console = Color(0xFF09101A)
    val ConsoleBorder = Color(0xFF182130)
    val CodeHeader = Color(0xFF0E1624)
    val Elevated = Color(0xFF101828)
    val ElevatedBorder = Color(0xFF243149)
    val Divider = Color(0xFF172131)
    val Badge = Color(0xFF172133)
    val BadgeBorder = Color(0xFF222E42)
    val Tile = Color(0xFF141E2F)
    val TileBorder = Color(0xFF223047)
    val SelectedSegment = Color(0xFF1F3050)
    val Scrim = Color(0x8C03060C) // rgba(3,6,12,.55)

    // Texte
    val TextStrong = Color(0xFFF2F6FB)
    val TextPrimary = Color(0xFFEEF3FA)
    val TextBody = Color(0xFFDFE7F2)
    val TextControl = Color(0xFFE6EDF6)
    val TextSecondary = Color(0xFFA3B0C2)
    val TextSecondaryAlt = Color(0xFF93A1B5)
    val TextTertiary = Color(0xFF8391A6)
    val TextMuted = Color(0xFF7F8CA0)
    val TextFaint = Color(0xFF6E7B8F)
    val NavText = Color(0xFFD3DCE8)
    val NavIcon = Color(0xFFB7C3D3)

    // Accent
    val Accent = Color(0xFF2F8BFF)
    val AccentTop = Color(0xFF3A8DFF)
    val AccentBottom = Color(0xFF1F6BE8)
    val AccentText = Color(0xFF4D9FFF)
    val AccentIcon = Color(0xFF5AA7FF)
    val AccentLink = Color(0xFF6CB6FF)
    val AccentSoft = Color(0xFF8CC2FF)
    val AccentBar = Color(0xFF3D8FFF)
    val SelectedBg = Accent.copy(alpha = 0.16f)
    val SelectedBorder = Color(0xFF5AA7FF).copy(alpha = 0.5f)
    val UserBubbleTop = Color(0xFF10254A)
    val UserBubbleBottom = Color(0xFF0C1C3A)
    val UserBubbleBorder = Color(0xFF214274)

    // Statuts (toujours accompagnés d'une icône + d'un texte)
    val Success = Color(0xFF22C55E)
    val SuccessText = Color(0xFF4ADE80)
    val ToolChipActiveIcon = Color(0xFF34D27A)
    val Danger = Color(0xFFE5463F)
    val DangerDeep = Color(0xFFC7302B)
    val DangerText = Color(0xFFF87171)
    val DangerTextSoft = Color(0xFFFCA5A5)
    val Warning = Color(0xFFF5A524)
    val WarningText = Color(0xFFFBBF24)
    val WarningTitle = Color(0xFFFDE8B8)
    val Purple = Color(0xFF8B5CF6)
    val PurpleText = Color(0xFFC4B5FD)
    val PurpleTag = Color(0xFFD3C4FF)
    val Cyan = Color(0xFF5FD4E8)
    val Amber = Color(0xFFF5B544)

    // Coloration syntaxique
    object Syntax {
        val Plain = Color(0xFFD3DBE7)
        val Keyword = Color(0xFFC792EA)
        val Type = Color(0xFF6CB6FF)
        val Annotation = Color(0xFFE8A95B)
        val Str = Color(0xFFB9E08C)
        val Literal = Color(0xFFF78C6C)
        val LineNumber = Color(0xFF46546B)
    }

    // Dégradés
    val SidebarBrush = Brush.verticalGradient(listOf(SidebarTop, SidebarBottom))
    val AccentButton = Brush.verticalGradient(listOf(AccentTop, AccentBottom))
    val DangerButton = Brush.verticalGradient(listOf(Danger, DangerDeep))
    val ProgressBrush = Brush.horizontalGradient(listOf(AccentBottom, AccentText))
    val UserBubble = Brush.verticalGradient(listOf(UserBubbleTop, UserBubbleBottom))
    val NavActive = Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.24f), Accent.copy(alpha = 0.07f)))
    val ApprovalBg = Brush.verticalGradient(listOf(Warning.copy(alpha = 0.10f), Warning.copy(alpha = 0.03f)))
}

object CortanaFonts {
    // TODO: brancher Geist / Geist Mono / Sora (res/font ou GoogleFont.Provider).
    val Sans: FontFamily = FontFamily.Default
    val Mono: FontFamily = FontFamily.Monospace
    val Brand: FontFamily = FontFamily.Default
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
    val Sm = RoundedCornerShape(10.dp)     // chips, onglets, boutons-icônes
    val Md = RoundedCornerShape(12.dp)     // boutons 44–48 dp, pills, tuiles
    val Field = RoundedCornerShape(13.dp)  // champ 52 dp, bouton Envoyer
    val Lg = RoundedCornerShape(14.dp)     // cartes de liste, carte Plan
    val Xl = RoundedCornerShape(16.dp)     // cartes principales, composer, grand STOP
    val Bubble = RoundedCornerShape(18.dp) // bulle utilisateur
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
    val CodeCollapsedMaxHeight = 232.dp
    val CodeExpandedMaxHeight = 360.dp
    val LogoRing = 62.dp
    val AvatarRing = 44.dp
    val MinTouchTarget = 48.dp
}

object CortanaMotion {
    val Standard = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    const val Fast = 150          // chips, survol
    const val Toggle = 180        // interrupteurs
    const val SidebarCollapse = 220
    const val Overlay = 240       // drawer, panneau portrait
    const val Scrim = 200
    const val Enter = 250         // apparition : alpha 0→1, translationY 6dp→0
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
}
