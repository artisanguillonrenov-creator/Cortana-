package io.github.artisanguillonrenov.cortana.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContractsTest {
    private fun step(id: String, vararg deps: String) = PlanStep(id, id.removePrefix("s").toIntOrNull() ?: 0, id, "faire $id", dependencies = deps.toList(), expectedOutcome = ExpectedOutcome("ok"))
    private fun plan(vararg steps: PlanStep) = Plan(planId = "p", taskId = "t", version = 1, objective = "o", strategy = PlanStrategy.DAG, steps = steps.toList(), createdAt = 1)

    @Test fun roundTripsEveryVersionedContract() {
        val req = TaskRequest(requestId = "r", source = TaskSource.CHAT, objective = "x", createdAt = 1, constraints = TaskConstraints(maxSteps = 3))
        assertEquals(req, Contracts.roundTrip(TaskRequest.serializer(), req))
        val p = plan(step("s1"), step("s2", "s1"))
        assertEquals(p, Contracts.roundTrip(Plan.serializer(), p))
        val cp = Checkpoint(checkpointId = "c", taskId = "t", planId = "p", planVersion = 1, state = TaskState.RUNNING, completedSteps = listOf("s1"), pendingSteps = listOf("s2"), reason = "step", createdAt = 2)
        assertEquals(cp, Contracts.roundTrip(Checkpoint.serializer(), cp))
        val ps = PatchSet(patchId = "x", workspaceId = "w", operations = listOf(PatchOperation.Replace("a.kt", "foo", "bar"), PatchOperation.Create("b.kt", "x")), generatedAt = 3)
        assertEquals(ps, Contracts.roundTrip(PatchSet.serializer(), ps))
        val sk = SkillDefinition(skillId = "k", name = "n", version = 1, description = "d", steps = listOf(SkillStep(1, "android.app.open", mapOf("name" to "{{app}}"))), createdAt = 1, updatedAt = 1)
        assertEquals(sk, Contracts.roundTrip(SkillDefinition.serializer(), sk))
        val v = VerificationResult(verificationId = "v", taskId = "t", status = VerificationStatus.PASSED, confidence = 1.0, evidence = listOf(VerificationEvidence("tool", "ok", true)), reason = "r", recommendedAction = RecommendedAction.CONTINUE)
        assertEquals(v, Contracts.roundTrip(VerificationResult.serializer(), v))
        val job = WorkerJob(jobId = "j", workspaceId = "w", kind = "exec", command = listOf("ls"))
        assertEquals(job, Contracts.roundTrip(WorkerJob.serializer(), job))
    }

    @Test fun rejectsMissingOrUnsupportedVersionAndGarbage() {
        fun expectCode(json: String, code: String) {
            try { Contracts.decode(TaskRequest.serializer(), json); fail("expected $code") } catch (e: ContractException) { assertEquals(code, e.error.code) }
        }
        expectCode("""{"requestId":"r","source":"chat","objective":"x","createdAt":1}""", "contract.version_missing")
        expectCode("""{"schemaVersion":"9.0","requestId":"r","source":"chat","objective":"x","createdAt":1}""", "contract.version_unsupported")
        expectCode("""{"schemaVersion":"1.0","requestId":"r","source":"nowhere","objective":"x","createdAt":1}""", "contract.invalid")
        expectCode("not json", "contract.malformed")
    }

    @Test fun planValidationDetectsCyclesUnknownDepsAndBudget() {
        assertTrue(PlanValidator.validate(plan(step("s1"), step("s2", "s1"))).isEmpty())
        assertTrue(PlanValidator.validate(plan(step("s1", "s2"), step("s2", "s1"))).any { it.contains("cycle") })
        assertTrue(PlanValidator.validate(plan(step("s1", "s9"))).any { it.contains("inconnue") })
        val big = plan(*(1..20).map { step("s$it") }.toTypedArray())
        assertTrue(PlanValidator.validate(big).any { it.contains("trop d'étapes") })
        assertEquals(listOf(listOf("s1", "s2"), listOf("s3")), PlanValidator.layers(plan(step("s1"), step("s2"), step("s3", "s1", "s2"))))
    }

    @Test fun readyStepsFollowDependencies() {
        var p = plan(step("s1"), step("s2", "s1"), step("s3"))
        assertEquals(listOf("s1", "s3"), p.readySteps().map { it.stepId })
        p = p.withStep(p.step("s1")!!.copy(status = StepStatus.SUCCEEDED))
        assertEquals(listOf("s2", "s3"), p.readySteps().map { it.stepId })
        assertFalse(p.isFinished())
    }

    @Test fun transitionTableIsExplicitAndTerminalIsFinal() {
        assertTrue(TaskTransitions.isAllowed(TaskState.RECEIVED, TaskState.PLANNING))
        assertTrue(TaskTransitions.isAllowed(TaskState.RUNNING, TaskState.VERIFYING))
        assertTrue(TaskTransitions.isAllowed(TaskState.RUNNING, TaskState.COMPLETED))
        assertTrue(TaskTransitions.isAllowed(TaskState.RUNNING, TaskState.INTERRUPTED))
        assertFalse(TaskTransitions.isAllowed(TaskState.RECEIVED, TaskState.VERIFYING))
        assertFalse(TaskTransitions.isAllowed(TaskState.COMPLETED, TaskState.RUNNING))
        assertFalse(TaskTransitions.isAllowed(TaskState.FAILED, TaskState.COMPLETED))
        TaskState.entries.forEach { assertEquals(it, TaskState.fromWire(it.wire)) }
    }
}
