package io.github.artisanguillonrenov.cortana

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.UiActionKind
import io.github.artisanguillonrenov.cortana.core.policy.UiTarget
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.executors.accessibility.Selector
import io.github.artisanguillonrenov.cortana.executors.accessibility.UiException
import io.github.artisanguillonrenov.cortana.fixture.FixtureActivity
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented accessibility tests against the bundled fixture Activity (§17 block 6).
 * Requires an emulator/device; enables the service through the shell (instrumentation has that right).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityFixtureTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext.applicationContext as CortanaApp

    @Before fun enableService() {
        val ctx = instr.targetContext
        val component = "${ctx.packageName}/${CortanaAccessibilityService::class.java.name}"
        instr.uiAutomation.executeShellCommand("settings put secure enabled_accessibility_services $component").close()
        instr.uiAutomation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = System.currentTimeMillis() + 15_000
        while (AccessibilityBridge.service.value == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertNotNull("accessibility service did not connect", AccessibilityBridge.service.value)
        ActivityScenario.launch<FixtureActivity>(Intent(ctx, FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
    }

    @Test fun observesFixtureAndClassifiesTargets() {
        val ui = app.container.ui
        val snap = ui.observe()
        assertTrue(snap.render().contains("Payer 9,99"))
        fun risk(text: String): Risk {
            val n = ui.resolveOne(Selector(text = text))
            return app.container.uiClassifier.classify(UiActionKind.CLICK, UiTarget(n.packageName, n.text, n.desc, n.resId, n.className), emptyList()).risk
        }
        assertEquals(Risk.L1, risk("Suivant"))
        assertEquals(Risk.L2, risk("Envoyer"))
        assertEquals(Risk.L3, risk("Payer 9,99 €"))
    }

    @Test fun clickTypeAndPasswordGuard() = runBlocking {
        val ui = app.container.ui
        ui.click(ui.resolveOne(Selector(text = "Suivant")))
        assertTrue(ui.observe().nodes.any { it.text == "Compteur : 1" })
        ui.type(ui.resolveOne(Selector(className = "EditText", index = 0)), "Bonjour", append = false)
        assertTrue(ui.observe().nodes.any { it.text == "Bonjour" })
        val pwd = ui.observe().nodes.first { it.password }
        try { ui.type(pwd, "secret", false); fail("password typing must be refused") } catch (e: UiException) { }
    }
}
