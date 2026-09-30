package io.github.artisanguillonrenov.cortana

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import io.github.artisanguillonrenov.cortana.ui.MainActivity
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Level A — what the JVM unit tests cannot see (D-20260928-066): 2.0.0-rc1 crashed in
 * `CortanaApp.onCreate` because Android's regex engine (ICU4C) rejects a pattern the JVM accepts.
 * These tests run on the device's own runtime and regex engine.
 */
@RunWith(AndroidJUnit4::class)
class StartupAndRegexEngineTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test fun applicationStartsAndTheMainScreenOpens() {
        // The instrumentation created CortanaApp (onCreate, AppContainer) before this test: reaching
        // this line means it did not crash.
        val app = instr.targetContext.applicationContext as CortanaApp
        assertNotNull(app.container)
        assertTrue(app.container.registry.all().isNotEmpty())
        ActivityScenario.launch(MainActivity::class.java).use { s -> assertEquals(Lifecycle.State.RESUMED, s.state) }
    }

    @Test fun skillPlaceholdersCompileAndSubstituteOnAndroid() {
        assertEquals(setOf("nom"), SkillService.placeholders(mapOf("q" to "Bonjour {{nom}}, {pas un paramètre}")))
        val out = SkillService.substitute(mapOf("q" to "Bonjour {{nom}}"), mapOf("nom" to "Marie"))
        assertEquals("Bonjour Marie", out!!["q"]!!.jsonPrimitive.content)
    }

    /** Every pattern written in the sources (tools/check_android_regex.py --write-asset), compiled as the app does. */
    @Test fun everyRegexOfTheAppCompilesWithAndroidsEngine() {
        val arr = JSONArray(instr.context.assets.open("regex_patterns.json").bufferedReader().readText())
        val failures = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            runCatching { Pattern.compile(o.getString("pattern"), o.getInt("flags")) }.exceptionOrNull()
                ?.let { "${o.getString("file")} ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue(arr.length() > 200)
    }

    /** Static initialisers (companion objects, objects, top-level values) run as they would on first use. */
    @Test fun everyClassOfTheAppInitialisesOnAndroid() {
        @Suppress("DEPRECATION")
        val names = dalvik.system.DexFile(instr.targetContext.packageCodePath).entries().toList()
            .filter { it.startsWith("io.github.artisanguillonrenov.cortana.") }
        val failures = names.mapNotNull { name ->
            try { Class.forName(name, true, instr.targetContext.classLoader); null }
            catch (e: ExceptionInInitializerError) { "$name : ${e.cause}" }
            catch (e: LinkageError) { "$name : $e" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("classes de l'application introuvables dans le dex", names.size > 100)
    }
}
