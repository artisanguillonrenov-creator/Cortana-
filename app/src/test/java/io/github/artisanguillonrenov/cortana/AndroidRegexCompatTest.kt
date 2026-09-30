package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * D-20260928-066: these unit tests run on the JVM's regex engine, the app on Android's (ICU4C).
 * 2.0.0-rc1 crashed at startup on "\\{\\{([a-zA-Z0-9_]+)}}", valid for the JVM, refused by ICU.
 * Every pattern written in the app and the contracts is compiled with the real ICU4C library by
 * `tools/check_android_regex.py` (also run before every release build), and the list compiled on
 * the device by `StartupAndRegexEngineTest` must be current.
 */
class AndroidRegexCompatTest {
    @Test fun everyRegexOfTheAppCompilesWithIcuAsOnAndroid() {
        val tool = File("../tools/check_android_regex.py").canonicalFile
        val p = ProcessBuilder("python3", tool.path, "--check-asset").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        assumeFalse("ICU4C absent de cette machine (la construction release l'exige) : $out", out.contains("introuvable"))
        assertEquals(out, 0, code)
        val compiled = Regex("""(\d+) expressions compilées""").find(out)!!.groupValues[1].toInt()
        assertTrue(out, compiled > 300)
    }

    @Test fun skillPlaceholders() {
        assertEquals(setOf("nom", "ville_2"), SkillService.placeholders(mapOf("a" to "{{nom}} à {{ville_2}}", "b" to "{pas} {{ pas }} {{}} {{é}}")))
        assertEquals("Bonjour Marie, {Marie}", SkillService.substitute(mapOf("q" to "Bonjour {{nom}}, {{{nom}}}"), mapOf("nom" to "Marie"))!!["q"]!!.jsonPrimitive.content)
        assertNull(SkillService.substitute(mapOf("q" to "{{absent}}"), emptyMap()))
    }
}
