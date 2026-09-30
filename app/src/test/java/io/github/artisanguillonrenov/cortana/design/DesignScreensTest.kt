package io.github.artisanguillonrenov.cortana.design

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.artisanguillonrenov.cortana.ui.cortana.DiscussionLandscape
import io.github.artisanguillonrenov.cortana.ui.cortana.DiscussionPortrait
import io.github.artisanguillonrenov.cortana.ui.cortana.DiscussionTablet1280
import io.github.artisanguillonrenov.cortana.ui.cortana.HistoryLandscape
import io.github.artisanguillonrenov.cortana.ui.cortana.HistoryPortrait
import io.github.artisanguillonrenov.cortana.ui.cortana.MemoryLandscape
import io.github.artisanguillonrenov.cortana.ui.cortana.SettingsLandscape
import io.github.artisanguillonrenov.cortana.ui.cortana.TasksLandscape
import io.github.artisanguillonrenov.cortana.ui.cortana.TasksPortrait
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Whole screens of the design at density 1 (1 px = 1 dp), for the side-by-side check with the
 * prototype (PROMPT step 7): build/design-shots/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/design-shots/screens").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Config(qualifiers = "w1448dp-h1086dp-mdpi") @Test fun discussionLandscape() { rule.setContent { DiscussionLandscape() }; shot("discussion_landscape") }
    @Config(qualifiers = "w1086dp-h1448dp-mdpi") @Test fun discussionPortrait() { rule.setContent { DiscussionPortrait() }; shot("discussion_portrait") }
    @Config(qualifiers = "w1280dp-h800dp-mdpi") @Test fun discussionTablet1280() { rule.setContent { DiscussionTablet1280() }; shot("discussion_1280x800") }
    @Config(qualifiers = "w1448dp-h1086dp-mdpi") @Test fun historyLandscape() { rule.setContent { HistoryLandscape() }; shot("history_landscape") }
    @Config(qualifiers = "w1448dp-h1086dp-mdpi") @Test fun tasksLandscape() { rule.setContent { TasksLandscape() }; shot("tasks_landscape") }
    @Config(qualifiers = "w1448dp-h1086dp-mdpi") @Test fun memoryLandscape() { rule.setContent { MemoryLandscape() }; shot("memory_landscape") }
    @Config(qualifiers = "w1448dp-h1086dp-mdpi") @Test fun settingsLandscape() { rule.setContent { SettingsLandscape() }; shot("settings_landscape") }
    @Config(qualifiers = "w1086dp-h1448dp-mdpi") @Test fun historyPortrait() { rule.setContent { HistoryPortrait() }; shot("history_portrait") }
    @Config(qualifiers = "w1086dp-h1448dp-mdpi") @Test fun tasksPortrait() { rule.setContent { TasksPortrait() }; shot("tasks_portrait") }
}
