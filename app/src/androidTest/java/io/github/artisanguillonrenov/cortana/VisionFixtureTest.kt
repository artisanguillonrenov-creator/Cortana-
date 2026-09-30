package io.github.artisanguillonrenov.cortana

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artisanguillonrenov.cortana.core.vision.ScreenContext
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.fixture.CanvasFixtureActivity
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level A/P gate of phase 16 on a real device: real AccessibilityService screenshot, real
 * Tesseract OCR, real gesture on a Canvas-drawn button that the accessibility tree cannot see.
 */
@RunWith(AndroidJUnit4::class)
class VisionFixtureTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as CortanaApp

    @Test fun canvasButtonIsFoundByOcrAndPressed() = runBlocking {
        val ctx = instr.targetContext
        val component = "${ctx.packageName}/${CortanaAccessibilityService::class.java.name}"
        instr.uiAutomation.executeShellCommand("settings put secure enabled_accessibility_services $component").close()
        instr.uiAutomation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = System.currentTimeMillis() + 15_000
        while (AccessibilityBridge.service.value == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertNotNull(AccessibilityBridge.service.value)
        val scenario = ActivityScenario.launch<CanvasFixtureActivity>(Intent(ctx, CanvasFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
        val c = app.container
        val snap = c.ui.observe()
        assertTrue("the tree must not expose the button", snap.nodes.none { it.text == "Valider" })
        val sctx = ScreenContext(snap.packageName, false, false, false, false, emptyList())
        val res = c.visual.locate("Valider", emptyList(), sctx)
        val t = res.target
        assertNotNull("OCR did not find Valider: ${res.tried}", t)
        assertEquals("ocr", t!!.source)
        c.ui.clickPoint(t.x, t.y, false)
        val check = c.visual.verify(res.captured, "Envoyé", null, sctx)
        assertTrue(check.note, check.changed == true && check.expectFound == true)
        scenario.onActivity { assertTrue(it.validated) }
    }
}
