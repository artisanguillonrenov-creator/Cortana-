package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.SkillBundle
import io.github.artisanguillonrenov.cortana.contracts.SkillCondition
import io.github.artisanguillonrenov.cortana.contracts.SkillDefinition
import io.github.artisanguillonrenov.cortana.contracts.SkillLifecycle
import io.github.artisanguillonrenov.cortana.contracts.SkillStep
import io.github.artisanguillonrenov.cortana.contracts.TaskRequest
import io.github.artisanguillonrenov.cortana.contracts.TaskSource
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.core.skills.SkillLearner
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VNext phase 8 gates (doc 08 §5): learned, parameterized, replayed safely, invalidated after divergence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillsTest : CortanaTestBase() {

    private fun pause(s: io.github.artisanguillonrenov.cortana.core.memory.SessionEntity, drink: String, minutes: Int) {
        server.enqueue(toolCall("notify_owner", """{"title":"Pause","message":"$drink dans $minutes min"}"""))
        server.enqueue(toolCall("schedule_create", """{"kind":"reminder","name":"$drink","message":"$drink prêt","in_seconds":${minutes * 60}}""", id = "call_2"))
        server.enqueue(text("C'est prêt."))
        runAndWait(s, "Prépare ma pause $drink")
    }

    /**
     * The learner writes the candidate, then its dry-run validation in a second write: wait for the
     * validation to be recorded (lifecycle no longer CANDIDATE, or a validation date), otherwise the
     * test races the second write. After the deadline the last state seen is returned for the assertions.
     */
    private fun awaitSkill(): SkillEntity {
        val deadline = System.currentTimeMillis() + 5_000
        var seen: SkillEntity? = null
        while (System.currentTimeMillis() < deadline) {
            seen = runBlocking { c.skills.observeAll().first() }.firstOrNull()
            if (seen != null && (SkillService.lifecycleOf(seen) != SkillLifecycle.CANDIDATE || seen.lastValidatedAt != null)) return seen
            Thread.sleep(30)
        }
        return seen ?: throw AssertionError("no skill learned")
    }

    @Test fun repeatedProcedureBecomesAParameterizedCandidateThenReplaysSafely() {
        val s = session()
        pause(s, "café", 10)
        Thread.sleep(200)
        assertTrue("one run is not enough", runBlocking { c.skills.observeAll().first() }.isEmpty())
        pause(s, "thé", 5)
        val skill = awaitSkill()
        assertEquals(SkillLifecycle.TESTED, SkillService.lifecycleOf(skill)) // dry-run validation passed
        assertFalse("never active without the owner", skill.enabled)
        val def = runBlocking { c.skills.definition(skill.skillId)!! }
        assertEquals("notify.owner>schedule.create", def.signature)
        assertEquals(setOf("message", "in_seconds", "message_2", "name"), def.parameters.keys)
        assertEquals("Pause", def.steps[0].arguments["title"])                   // constant kept
        assertEquals("{{in_seconds}}", def.steps[1].arguments["in_seconds"])     // differing value → parameter
        assertTrue(def.triggerHints.any { it.contains("{name}") || it.contains("{{name}}") || it.contains("{{message") })
        assertTrue(runBlocking { c.outbox.isRecorded("skill-candidate:${skill.skillId}") })

        // Owner activates; the model replays it with new parameters.
        assertTrue(runBlocking { c.skills.activate(skill.skillId) })
        server.enqueue(toolCall("skill_run", """{"skill":"${skill.skillId}","params":{"message":"Chocolat dans 2 min","name":"chocolat","message_2":"chocolat prêt","in_seconds":"120"}}"""))
        server.enqueue(text("Pause chocolat programmée."))
        runAndWait(s, "Prépare ma pause chocolat")
        val firstRequest = (1..7).map { server.takeRequest().body.readUtf8() }.last()
        assertTrue("active skill suggested in context", firstRequest.contains(skill.skillId))
        val calls = runBlocking { c.db.tasks().toolCalls(lastTask().id) }
        assertEquals(listOf("skill.run", "notify.owner", "schedule.create"), calls.map { it.capability })
        assertTrue(calls.all { it.outcome == "ok" })
        val after = runBlocking { c.skills.get(skill.skillId)!! }
        assertEquals(1, after.successCount)
        assertTrue(after.confidence > 0.5)
        assertEquals(3, runBlocking { c.scheduler.all() }.size)
        assertTrue(messages(s).any { it.role == Roles.TOOL && it.text.contains("exécutée et vérifiée") })
        Thread.sleep(200)
        assertEquals("a replay is not a new trajectory", 1, runBlocking { c.skills.observeAll().first() }.size)
    }

    private fun imported(def: SkillDefinition): SkillEntity = runBlocking {
        val bundle = ContractJson.encodeToString(SkillBundle.serializer(), SkillBundle(skill = def, exportedAt = 1))
        c.skills.import(bundle).getOrThrow()
    }

    private fun skill(vararg steps: SkillStep, params: Map<String, String> = emptyMap(), pre: List<SkillCondition> = emptyList(), app: String? = null) =
        SkillDefinition(skillId = "x", name = "Essai", version = 3, description = "procédure de test", parameters = params, steps = steps.toList(),
            preconditions = pre, appPackage = app, appVersion = app?.let { "1.0" }, createdAt = 1, updatedAt = 1)

    private fun activeImported(def: SkillDefinition): SkillEntity {
        val e = imported(def)
        assertTrue(runBlocking { c.skills.validate(e.skillId) }.ok)
        assertTrue(runBlocking { c.skills.activate(e.skillId) })
        return e
    }

    @Test fun missingTargetStopsTheReplayAndRepeatedFailuresDegradeTheSkill() {
        val s = session()
        val e = activeImported(skill(
            SkillStep(1, "notify.owner", mapOf("message" to "ok"), preconditions = listOf(SkillCondition("package_foreground", "com.whatsapp"))),
            SkillStep(2, "notify.owner", mapOf("message" to "fin")),
        ))
        repeat(SkillService.MAX_CONSECUTIVE_FAILURES) {
            server.enqueue(toolCall("skill_run", """{"skill":"${e.skillId}"}"""))
            server.enqueue(text("Je continue à la main."))
            runAndWait(s, "Lance l'essai")
            assertTrue(runBlocking { c.db.tasks().toolCalls(lastTask().id) }.none { it.capability == "notify.owner" }) // nothing executed
        }
        val after = runBlocking { c.skills.get(e.skillId)!! }
        assertEquals(SkillLifecycle.DEGRADED, SkillService.lifecycleOf(after))
        assertFalse(after.enabled)
        assertTrue(after.confidence < 0.5)
        assertTrue(after.lastFailureReason!!.contains("échecs consécutifs"))
        // A degraded skill cannot run until re-validated and re-activated.
        assertTrue(runBlocking { c.skills.prepare(e.skillId, emptyMap(), setOf("notify.owner")) }.isFailure)
    }

    @Test fun updatedOrMissingAppInvalidatesBeforeAnyStep() = runBlocking {
        val e = imported(skill(SkillStep(1, "notify.owner", mapOf("message" to "x")), app = "com.example.absent"))
        val report = c.skills.validate(e.skillId)
        assertFalse(report.ok)
        assertTrue(report.problems.single().contains("absente"))
        // Force-activate path is closed: validation did not pass.
        assertFalse(c.skills.activate(e.skillId))
    }

    @Test fun sensitiveStepsStillAskTheOwner() {
        val s = session()
        runBlocking { c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "explicit") }
        val e = activeImported(skill(SkillStep(1, "memory.forget", mapOf("query" to "{{quoi}}")), params = mapOf("quoi" to "souvenir à oublier")))
        server.enqueue(toolCall("skill_run", """{"skill":"${e.skillId}","params":{"quoi":"thé"}}"""))
        server.enqueue(text("Vous avez refusé."))
        runAndWait(s, "Oublie le thé avec la procédure", approve = false)
        val calls = runBlocking { c.db.tasks().toolCalls(lastTask().id) }
        assertEquals("refused", calls.single { it.capability == "memory.forget" }.outcome)
        assertEquals(1, runBlocking { c.skills.get(e.skillId)!! }.failureCount)
        assertEquals(1, runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.size)
    }

    @Test fun parametersAreRequiredAndCoordinateOnlySkillsAreNeverValidated() = runBlocking {
        val e = activeImported(skill(SkillStep(1, "notify.owner", mapOf("message" to "{{texte}}")), params = mapOf("texte" to "message")))
        val missing = c.skills.prepare(e.skillId, emptyMap(), setOf("notify.owner"))
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("texte"))
        val ok = c.skills.prepare(e.skillId, mapOf("texte" to "Bonjour"), setOf("notify.owner")).getOrThrow()
        assertEquals("Bonjour", ok.steps.single().arguments["message"].toString().trim('"'))
        val coords = imported(skill(SkillStep(1, "android.ui.click_point", mapOf("x" to "10", "y" to "20"), coordinateFallback = true)))
        val r = c.skills.validate(coords.skillId)
        assertFalse(r.ok)
        assertTrue(r.problems.any { it.contains("coordonnées") })
    }

    @Test fun exportImportIsVersionedAndNeverCarriesStatsOrActivation() = runBlocking {
        val e = activeImported(skill(SkillStep(1, "notify.owner", mapOf("message" to "Bonjour"))))
        c.skills.recordReplay(e.skillId, 1, null, true, null, null)
        val json = c.skills.export(e.skillId)!!
        val bundle = ContractJson.decodeFromString(SkillBundle.serializer(), json)
        assertEquals("cortana.skill", bundle.format)
        assertEquals(1, bundle.bundleVersion)
        assertEquals(0, bundle.skill.successCount)
        assertFalse(bundle.skill.enabled)
        val again = c.skills.import(json).getOrThrow()
        assertTrue(again.skillId != e.skillId)
        assertEquals(SkillLifecycle.CANDIDATE, SkillService.lifecycleOf(again))
        assertFalse(again.enabled)
        assertTrue(c.skills.import(json.replace("\"bundleVersion\":1", "\"bundleVersion\":2")).isFailure)
        assertTrue(c.skills.import(json.replace("notify.owner", "evil.capability")).isFailure)
    }

    @Test fun screenNodesBecomeSemanticSelectorsAndTypedValueBecomesAParameter() = runBlocking {
        val s = session()
        fun observation() = "Application : com.whatsapp (activité : Home)\n[3] EditText \"Message\" id=entry (0,0,10,10) [saisie]\n[5] ImageButton desc=\"Envoyer\" id=send (0,0,1,1) [clic]"
        suspend fun uiTask(text: String): String {
            val t = c.stateMachine.create(TaskRequest(requestId = text, source = TaskSource.CHAT, objective = "Envoie « $text » sur WhatsApp", createdAt = 1), s.id, "interactive")
            var at = System.currentTimeMillis()
            c.conversations.addMessage(s.id, Roles.TOOL, observation(), taskId = t.id, toolCallsJson = """{"toolCallId":"o","name":"android_ui_observe","capability":"android.ui.observe","ok":true}""")
            Thread.sleep(5); at = System.currentTimeMillis() + 1
            c.db.tasks().insertToolCall(ToolCallEntity("a$text", t.id, "s1", "android.ui.type", """{"node":3,"value":"$text"}""", null, "{}", "ok", "ok", at))
            c.db.tasks().insertToolCall(ToolCallEntity("b$text", t.id, "s1", "android.ui.click", """{"node":5}""", null, "{}", "ok", "ok", at + 1))
            return t.id
        }
        assertNull(c.skillLearner.observe(uiTask("Bonjour"), "Envoie « Bonjour » sur WhatsApp", listOf("android.ui.observe")))
        val learned = c.skillLearner.observe(uiTask("À ce soir"), "Envoie « À ce soir » sur WhatsApp", listOf("android.ui.observe"))
        assertNotNull(learned)
        val def = c.skills.definition(learned!!.skillId)!!
        assertEquals(mapOf("resource_id" to "entry", "value" to "{{value}}"), def.steps[0].arguments)
        assertEquals(mapOf("resource_id" to "send"), def.steps[1].arguments)
        assertEquals(SkillCondition("package_foreground", "com.whatsapp"), def.steps[0].preconditions.single())
        assertEquals("com.whatsapp", def.appPackage)
        assertTrue(def.triggerHints.any { it.contains("{{value}}") })
        // Web-tainted work is never learned from.
        assertNull(c.skillLearner.observe(uiTask("Salut"), "Envoie « Salut » sur WhatsApp", listOf("android.ui.observe", "web.fetch:exemple.fr")))
    }

    @Test fun observationParserPrefersStableSelectors() {
        val o = SkillLearner.parseObservation("<donnees_non_fiables source=\"android.ui.observe\">\nApplication : com.app\n[1] Button \"OK\" (0,0,1,1) [clic]\n[2] View desc=\"Menu\" (0,0,1,1)\n[4] Button \"Go\" id=go_btn (0,0,1,1)\n</donnees_non_fiables>")
        assertEquals("com.app", o.packageName)
        assertEquals(mapOf("text" to "OK"), o.nodes[1]!!.selector())
        assertEquals(mapOf("content_description" to "Menu"), o.nodes[2]!!.selector())
        assertEquals(mapOf("resource_id" to "go_btn"), o.nodes[4]!!.selector())
    }
}
