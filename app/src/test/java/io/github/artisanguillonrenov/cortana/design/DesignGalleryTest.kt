package io.github.artisanguillonrenov.cortana.design

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.artisanguillonrenov.cortana.ui.components.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every component preview of the design to build/design-shots/components (one PNG each) at density 1
 * (1 px = 1 dp, like the prototype), for the side-by-side check of PROMPT step 7. It fails if a
 * preview throws or renders nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1200dp-h1400dp-mdpi")
class DesignGalleryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val previews: List<Pair<String, @Composable () -> Unit>> = listOf(
        "NavItemInactive" to { NavItemInactive() },
        "NavItemActive" to { NavItemActive() },
        "NavItemBadge" to { NavItemBadge() },
        "NavItemDot" to { NavItemDot() },
        "ToggleSizes" to { ToggleSizes() },
        "HeaderPills" to { HeaderPills() },
        "HeaderPillsActive" to { HeaderPillsActive() },
        "ToolChips" to { ToolChips() },
        "FilterChips" to { FilterChips() },
        "SegmentedHeader" to { SegmentedHeader() },
        "SegmentedSettings" to { SegmentedSettings() },
        "StopLargeRunning" to { StopLargeRunning() },
        "StopLargePaused" to { StopLargePaused() },
        "StopLargeDone" to { StopLargeDone() },
        "StopPanelStates" to { StopPanelStates() },
        "StopRailStates" to { StopRailStates() },
        "StatusChips" to { StatusChips() },
        "ProgressBars" to { ProgressBars() },
        "PlanCardRunning" to { PlanCardRunning() },
        "PlanCardWaiting" to { PlanCardWaiting() },
        "StepRows" to { StepRows() },
        "CodeBlockCollapsed" to { CodeBlockCollapsed() },
        "CodeBlockExpandedCopied" to { CodeBlockExpandedCopied() },
        "ApprovalPending" to { ApprovalPending() },
        "ApprovalGranted" to { ApprovalGranted() },
        "ApprovalRefused" to { ApprovalRefused() },
        "LiveLine" to { LiveLine() },
        "LogConsoleRunning" to { LogConsoleRunning() },
        "ContextTabsPreview" to { ContextTabsPreview() },
        "InfoCards" to { InfoCards() },
        "ComposerNormal" to { ComposerNormal() },
        "ComposerVoice" to { ComposerVoice() },
        "UserBubblePreview" to { UserBubblePreview() },
        "QueuedBubbles" to { QueuedBubbles() },
        "ModelMenuPreview" to { ModelMenuPreview() },
        "ActivityRailRunning" to { ActivityRailRunning() },
        "ActivityRailPaused" to { ActivityRailPaused() },
        "ConversationRows" to { ConversationRows() },
        "MemoryCardFree" to { MemoryCardFree() },
        "MemoryCardLocked" to { MemoryCardLocked() },
        "MemoryCardInactive" to { MemoryCardInactive() },
        "SettingsRows" to { SettingsRows() },
        "SidebarPieces" to { SidebarPieces() },
    )

    /** Regression: ~200 colors as constructor parameters exceeded the JVM's 255-slot limit (ClassFormatError at launch). */
    @Test fun palettesLoadAndContrastOnlyStrengthensSecondaryTexts() {
        val dark = io.github.artisanguillonrenov.cortana.ui.theme.CortanaDark
        val hc = io.github.artisanguillonrenov.cortana.ui.theme.CortanaHighContrast
        org.junit.Assert.assertEquals(dark.background, hc.background)
        org.junit.Assert.assertEquals(dark.accent, hc.accent)
        org.junit.Assert.assertEquals(dark.textBody, hc.textSecondary)
        org.junit.Assert.assertNotEquals(dark.textSecondary, hc.textSecondary)
    }

    @Test fun everyComponentPreviewRenders() {
        var current by mutableIntStateOf(0)
        rule.setContent { Box(Modifier.wrapContentSize().testTag("shot")) { previews[current].second() } }
        val out = File("build/design-shots/components").apply { mkdirs() }
        previews.forEachIndexed { i, (name, _) ->
            current = i
            rule.waitForIdle()
            val b = rule.onNodeWithTag("shot").fetchSemanticsNode().boundsInRoot
            val view = rule.activity.window.decorView
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(full))
            val w = b.width.toInt().coerceAtMost(full.width); val h = b.height.toInt().coerceAtMost(full.height)
            assertTrue("$name rendered nothing", w > 0 && h > 0)
            val shot = Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt(), w, h)
            File(out, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("DESIGN_SHOTS ${previews.size}")
    }
}
