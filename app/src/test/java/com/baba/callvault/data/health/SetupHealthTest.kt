package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHealthTest {

    private val verifiedFacts = HealthFacts(lastVerifiedAt = 5_000L, verifiedFingerprint = "fp-1")

    @Test
    fun `nothing observed yet is unverified`() {
        assertEquals(SetupHealth.Unverified, SetupHealthDeriver.derive(HealthFacts(), "fp-1", null))
    }

    @Test
    fun `a verified call under an unchanged fingerprint is verified`() {
        assertEquals(SetupHealth.Verified(5_000L), SetupHealthDeriver.derive(verifiedFacts, "fp-1", null))
    }

    @Test
    fun `a changed fingerprint makes an earlier verification stale`() {
        assertEquals(SetupHealth.StaleAfterChange, SetupHealthDeriver.derive(verifiedFacts, "fp-2", null))
    }

    @Test
    fun `an unobserved call outranks everything else`() {
        val gap = CallGap(9_000L, "Feroza")
        val facts = verifiedFacts.copy(lastFailureAt = 6_000L, lastFailureReason = FailureReason.EMPTY_FILE)
        assertEquals(SetupHealth.CallNotRecorded(9_000L, "Feroza"), SetupHealthDeriver.derive(facts, "fp-2", gap))
    }

    @Test
    fun `a failure outranks a setup change`() {
        val facts = verifiedFacts.copy(lastFailureAt = 6_000L, lastFailureReason = FailureReason.DAEMON_DIED)
        assertEquals(
            SetupHealth.LastCallFailed(6_000L, FailureReason.DAEMON_DIED),
            SetupHealthDeriver.derive(facts, "fp-2", null)
        )
    }

    @Test
    fun `a later verified call clears an older failure`() {
        // recordVerified() already removes the failure keys; this guards the derive step too.
        val facts = verifiedFacts.copy(lastVerifiedAt = 8_000L, lastFailureAt = 6_000L, lastFailureReason = FailureReason.EMPTY_FILE)
        assertEquals(SetupHealth.Verified(8_000L), SetupHealthDeriver.derive(facts, "fp-1", null))
    }

    @Test
    fun `only the good states are not problems`() {
        assertFalse(SetupHealth.Verified(1L).isProblem)
        assertFalse(SetupHealth.Unverified.isProblem)
        assertTrue(SetupHealth.StaleAfterChange.isProblem)
        assertTrue(SetupHealth.LastCallFailed(1L, FailureReason.EMPTY_FILE).isProblem)
        assertTrue(SetupHealth.CallNotRecorded(1L, null).isProblem)
    }
}
