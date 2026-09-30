package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPaths
import io.github.artisanguillonrenov.cortana.core.scheduler.CronExpression
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.SchemaValidator
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.canonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchemaCronFastPathTest {
    private val schema = S.obj("url" to S.str("u"), "n" to S.int("n", 1, 10), "mode" to S.str("m", listOf("a", "b")), required = listOf("url"))

    @Test fun schemaValidation() {
        fun v(json: String) = SchemaValidator.validate(schema, AppJson.parseToJsonElement(json))
        assertTrue(v("""{"url":"x","n":3,"mode":"a"}""").isEmpty())
        assertTrue(v("""{"url":"x","n":"3"}""").isEmpty()) // numeric strings tolerated
        assertEquals(1, v("""{"n":3}""").size)
        assertTrue(v("""{"url":"x","n":11}""").single().contains("≤"))
        assertTrue(v("""{"url":"x","mode":"z"}""").single().contains("l'un de"))
        assertTrue(v("""{"url":"x","evil":1}""").single().contains("pas un paramètre"))
        assertTrue(v("""{"url":"x","n":2.5}""").single().contains("entier"))
    }

    @Test fun canonicalJsonIsOrderIndependent() {
        assertEquals(canonicalJson(AppJson.parseToJsonElement("""{"b":1,"a":{"y":2,"x":1}}""")), canonicalJson(AppJson.parseToJsonElement("""{"a":{"x":1,"y":2},"b":1}""")))
    }

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    @Test fun cronNextWeekdayMorning() {
        val c = CronExpression.parse("0 8 * * 1-5")
        val fri = ZonedDateTime.of(2026, 9, 25, 9, 0, 0, 0, paris) // Friday 09:00
        assertEquals(ZonedDateTime.of(2026, 9, 28, 8, 0, 0, 0, paris), c.next(fri)) // Monday 08:00
    }

    @Test fun cronStepsAndDst() {
        assertEquals(ZonedDateTime.of(2026, 9, 27, 10, 15, 0, 0, paris), CronExpression.parse("*/15 * * * *").next(ZonedDateTime.of(2026, 9, 27, 10, 7, 0, 0, paris)))
        // 2026-03-29 02:30 does not exist in Paris (DST gap) → next valid 02:30 is on the 30th
        val n = CronExpression.parse("30 2 * * *").next(ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, paris))!!
        assertEquals(2, n.hour)
    }

    @Test fun scheduleSpecNext() {
        val now = 1_000_000_000_000L
        assertEquals(now + 60_000, ScheduleSpec(at = now + 60_000).nextAfter(now, paris))
        assertNull(ScheduleSpec(at = now - 1).nextAfter(now, paris))
        assertEquals(now + 15 * 60_000L, ScheduleSpec(everyMinutes = 15, startAt = now).nextAfter(now, paris))
    }

    @Test fun reminderFastPathsFrench() {
        val now = ZonedDateTime.of(2026, 9, 27, 14, 30, 0, 0, paris).toInstant().toEpochMilli()
        val r = FastPaths.parseReminder("Rappelle-moi dans 5 minutes de boire de l'eau", now, paris)!!
        assertEquals(now + 5 * 60_000, r.atMs)
        assertEquals("Boire de l'eau", r.message)
        assertEquals(now + 2 * 3_600_000L, FastPaths.parseReminder("rappelle moi dans deux heures d'appeler Paul", now, paris)!!.atMs)
        assertEquals(now + 30 * 60_000L, FastPaths.parseReminder("Rappelle-moi dans une demi-heure de sortir le linge", now, paris)!!.atMs)
        val at = FastPaths.parseReminder("Rappelle-moi à 18h15 de fermer les volets", now, paris)!!
        assertEquals(ZonedDateTime.of(2026, 9, 27, 18, 15, 0, 0, paris).toInstant().toEpochMilli(), at.atMs)
        val tomorrow = FastPaths.parseReminder("Rappelle-moi demain à 8h de prendre mes médicaments", now, paris)!!
        assertEquals(ZonedDateTime.of(2026, 9, 28, 8, 0, 0, 0, paris).toInstant().toEpochMilli(), tomorrow.atMs)
        val past = FastPaths.parseReminder("Rappelle-moi à 9h de courir", now, paris)!! // already past today → tomorrow
        assertEquals(ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, paris).toInstant().toEpochMilli(), past.atMs)
        assertEquals(now + 10 * 60_000L, FastPaths.parseReminder("Rappelle-moi de sortir le chien dans 10 minutes", now, paris)!!.atMs)
        assertNull(FastPaths.parseReminder("Quel temps fait-il ?", now, paris))
    }

    @Test fun explicitMemoryFastPath() {
        val m = FastPaths.parseExplicitMemory("Retiens que je préfère des réponses courtes")!!
        assertEquals("Je préfère des réponses courtes", m.text)
        assertEquals(MemoryTypes.PREFERENCE, m.type)
        assertEquals(MemoryTypes.PROFILE, FastPaths.parseExplicitMemory("Souviens-toi que j'habite à Lyon")!!.type)
        assertNull(FastPaths.parseExplicitMemory("Tu te souviens de quoi ?"))
        assertTrue(FastPaths.isExplicitMemoryRequest("retiens bien ceci : mon code postal est 69001"))
    }

    @Test fun parseAtFormats() {
        val now = ZonedDateTime.of(2026, 9, 27, 14, 30, 0, 0, paris).toInstant().toEpochMilli()
        assertNotNull(FastPaths.parseAt("2026-10-01T09:00", paris, now))
        assertNotNull(FastPaths.parseAt("2026-10-01 09:00", paris, now))
        assertEquals(ZonedDateTime.of(2026, 9, 27, 16, 0, 0, 0, paris).toInstant().toEpochMilli(), FastPaths.parseAt("16h", paris, now))
        assertNull(FastPaths.parseAt("bientôt", paris, now))
        assertEquals(listOf("example.com"), FastPaths.hostsIn("lis https://example.com/page et résume"))
    }
}
