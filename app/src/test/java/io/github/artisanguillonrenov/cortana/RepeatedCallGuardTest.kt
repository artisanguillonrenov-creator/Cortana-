package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.orchestrator.RepeatedCallGuard
import io.github.artisanguillonrenov.cortana.core.orchestrator.RepeatedCallGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The step loop never re-dispatches an identical failed call without a relevant state change (pure JVM test). */
class RepeatedCallGuardTest {
    private val push = "repo.push|{\"workspace\":\"calc\"}"
    private val tests = "test.run|{\"workspace\":\"calc\"}"
    private val patch = "code.patch.apply|{\"ops\":1}"

    @Test fun identicalFailedCallIsBlockedThenTheStepStops() {
        val g = RepeatedCallGuard()
        assertEquals(Verdict.RUN, g.check("repo.push", push).verdict)
        g.record("repo.push", push, ok = false, stateChanged = false, ownerRefused = false, error = "Dépôt distant inconnu")
        val first = g.check("repo.push", push)
        assertEquals(Verdict.BLOCK_INFORM, first.verdict)
        assertTrue(first.reason!!.contains("Dépôt distant inconnu"))
        assertEquals(Verdict.BLOCK_STOP, g.check("repo.push", push).verdict)
    }

    @Test fun differentArgumentsAreANewCall() {
        val g = RepeatedCallGuard()
        g.record("repo.push", push, ok = false, stateChanged = false, ownerRefused = false)
        assertEquals(Verdict.RUN, g.check("repo.push", "repo.push|{\"remote\":\"upstream\",\"workspace\":\"calc\"}").verdict)
    }

    @Test fun testThenPatchThenTestStaysAllowed() {
        val g = RepeatedCallGuard()
        g.record("test.run", tests, ok = false, stateChanged = false, ownerRefused = false, error = "2 échecs")
        assertEquals(Verdict.RUN, g.check("code.patch.apply", patch).verdict)
        g.record("code.patch.apply", patch, ok = true, stateChanged = true, ownerRefused = false)
        assertEquals(Verdict.RUN, g.check("test.run", tests).verdict)
        g.record("test.run", tests, ok = false, stateChanged = false, ownerRefused = false, error = "1 échec")
        // A second fix, a third run: still fine, each time the code changed.
        g.record("code.patch.apply", "$patch#2", ok = true, stateChanged = true, ownerRefused = false)
        assertEquals(Verdict.RUN, g.check("test.run", tests).verdict)
    }

    @Test fun readOnlySuccessIsNotAStateChange() {
        val g = RepeatedCallGuard()
        g.record("test.run", tests, ok = false, stateChanged = false, ownerRefused = false)
        g.record("repo.status", "repo.status|{}", ok = true, stateChanged = false, ownerRefused = false)
        assertEquals(Verdict.BLOCK_INFORM, g.check("test.run", tests).verdict)
    }

    @Test fun ownerApprovalOrReauthorisationReallowsTheCall() {
        val g = RepeatedCallGuard()
        g.record("repo.fetch", "f", ok = false, stateChanged = false, ownerRefused = false)
        g.stateChanged() // e.g. the owner approved an action / a connection was re-authorised
        assertEquals(Verdict.RUN, g.check("repo.fetch", "f").verdict)
    }

    @Test fun anOwnerRefusalIsNeverAskedAgainInTheStep() {
        val g = RepeatedCallGuard()
        g.record("repo.push", push, ok = false, stateChanged = false, ownerRefused = true)
        // Even after a state change and with other arguments, the refused capability is not re-asked.
        g.record("code.patch.apply", patch, ok = true, stateChanged = true, ownerRefused = false)
        assertEquals(Verdict.BLOCK_INFORM, g.check("repo.push", "repo.push|{\"branch\":\"x\",\"workspace\":\"calc\"}").verdict)
        assertEquals(Verdict.BLOCK_STOP, g.check("repo.push", push).verdict)
    }

    @Test fun aSuccessClearsThePreviousFailure() {
        val g = RepeatedCallGuard()
        g.record("repo.fetch", "f", ok = false, stateChanged = false, ownerRefused = false)
        g.stateChanged()
        g.record("repo.fetch", "f", ok = true, stateChanged = true, ownerRefused = false)
        assertEquals(Verdict.RUN, g.check("repo.fetch", "f").verdict)
    }
}
