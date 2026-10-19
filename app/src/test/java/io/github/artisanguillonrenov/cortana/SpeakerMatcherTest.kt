package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.voice.SpeakerMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerMatcherTest {
    private fun embedding(x: Float, y: Float) =
        floatArrayOf(x, y, 0f, 0f, 0f, 0f, 0f, 0f)

    @Test fun identifiesDistinctEnrolledSpeakers() {
        val matcher = SpeakerMatcher()
        assertTrue(matcher.enroll("alice", listOf(embedding(1f, 0f), embedding(0.98f, 0.02f))))
        assertTrue(matcher.enroll("bob", listOf(embedding(0f, 1f), embedding(0.02f, 0.98f))))
        assertEquals("alice", matcher.identify(embedding(1f, 0f)).profileId)
        assertEquals("bob", matcher.identify(embedding(0f, 1f)).profileId)
        assertEquals(SpeakerMatcher.Outcome.MATCH, matcher.identify(embedding(0f, 1f)).outcome)
        assertEquals(2, matcher.profileCount())
    }

    @Test fun unknownAndAmbiguousNeverDefaultToOwner() {
        val matcher = SpeakerMatcher()
        assertEquals(SpeakerMatcher.Outcome.NO_PROFILES, matcher.identify(embedding(1f, 0f)).outcome)
        assertTrue(matcher.enroll("owner", listOf(embedding(1f, 0f), embedding(1f, 0f))))
        assertTrue(matcher.enroll("guest", listOf(embedding(0f, 1f), embedding(0f, 1f))))
        val unknown = matcher.identify(embedding(-1f, 0f))
        assertNull(unknown.profileId)
        assertEquals(SpeakerMatcher.Outcome.BELOW_THRESHOLD, unknown.outcome)
        val ambiguous = matcher.identify(embedding(0.71f, 0.71f))
        assertNull(ambiguous.profileId)
        assertEquals(SpeakerMatcher.Outcome.AMBIGUOUS, ambiguous.outcome)
    }

    @Test fun invalidOrMismatchedEmbeddingsFailClosed() {
        val matcher = SpeakerMatcher()
        assertFalse(matcher.enroll("owner", listOf(embedding(1f, 0f))))
        assertFalse(matcher.enroll("owner", listOf(embedding(1f, 0f), embedding(0f, 0f))))
        assertTrue(matcher.enroll("owner", listOf(embedding(1f, 0f), embedding(1f, 0f))))
        assertFalse(matcher.enroll("other", listOf(FloatArray(9) { 1f }, FloatArray(9) { 1f })))
        assertEquals(SpeakerMatcher.Outcome.INVALID_VECTOR, matcher.identify(embedding(Float.NaN, 0f)).outcome)
        assertEquals(SpeakerMatcher.Outcome.INVALID_VECTOR, matcher.identify(embedding(0f, 0f)).outcome)
        assertEquals(SpeakerMatcher.Outcome.INCOMPATIBLE_MODEL, matcher.identify(FloatArray(9) { 1f }).outcome)
        matcher.remove("owner")
        assertEquals(0, matcher.profileCount())
        assertEquals(SpeakerMatcher.Outcome.NO_PROFILES, matcher.identify(embedding(1f, 0f)).outcome)
    }

    @Test fun enrollmentAveragesSeveralSamplesAndNeverKeepsCallerArrays() {
        val matcher = SpeakerMatcher()
        val first = embedding(1f, 0f)
        assertTrue(matcher.enroll("owner", listOf(first, embedding(0.9f, 0.1f))))
        first[0] = -1f
        assertEquals("owner", matcher.identify(embedding(1f, 0f)).profileId)
        matcher.clear()
        assertEquals(SpeakerMatcher.Outcome.NO_PROFILES, matcher.identify(embedding(1f, 0f)).outcome)
    }
}
